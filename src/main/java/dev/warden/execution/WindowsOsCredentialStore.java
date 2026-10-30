package dev.warden.execution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Current-user Windows Credential Manager. The secret is written to the helper's stdin.
 * It is not a process argument, an environment value, or a file. There is no plaintext fallback.
 */
final class WindowsOsCredentialStore implements OsCredentialStore {
    private static final Pattern TARGET = Pattern.compile("warden/[a-z][a-z0-9_-]{0,63}");
    private static final int MAX_SECRET_BYTES = 2048;
    private static final long TIMEOUT_SECONDS = 30;
    private static final Object LOCK = new Object();
    private static Path helperDir;
    private static CurrentUser currentUser;
    private static List<String> lastCommand = List.of();

    private volatile Boolean available;
    private static volatile String lastFailure;

    private static final String SCRIPT = """
            param([Parameter(Mandatory=$true)][string]$Op, [string]$Target)
            $ErrorActionPreference = 'Stop'
            $ProgressPreference = 'SilentlyContinue'
            Add-Type -TypeDefinition @"
            using System;
            using System.Runtime.InteropServices;
            public static class WardenCred {
                [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
                public struct CREDENTIAL {
                    public uint Flags;
                    public uint Type;
                    public string TargetName;
                    public string Comment;
                    public long LastWritten;
                    public uint CredentialBlobSize;
                    public IntPtr CredentialBlob;
                    public uint Persist;
                    public uint AttributeCount;
                    public IntPtr Attributes;
                    public string TargetAlias;
                    public string UserName;
                }
                [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
                public static extern bool CredWrite(ref CREDENTIAL credential, uint flags);
                [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
                public static extern bool CredRead(string target, uint type, uint reservedFlag, out IntPtr credential);
                [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
                public static extern bool CredDelete(string target, uint type, uint flags);
                [DllImport("advapi32.dll")]
                public static extern void CredFree(IntPtr cred);
                public static int Probe() {
                    IntPtr p;
                    bool ok = CredRead("WardenOsStoreProbe", 1, 0, out p);
                    if (ok) { CredFree(p); return 0; }
                    return Marshal.GetLastWin32Error() == 1168 ? 0 : 3;
                }
                public static int Put(string target, byte[] blob) {
                    if (blob == null || blob.Length == 0 || blob.Length > 2048) return 4;
                    var cred = new CREDENTIAL();
                    cred.Type = 1;
                    cred.TargetName = target;
                    cred.Comment = "Warden connection credential";
                    cred.Persist = 2;
                    cred.CredentialBlobSize = (uint)blob.Length;
                    cred.CredentialBlob = Marshal.AllocHGlobal(blob.Length);
                    cred.UserName = "warden";
                    try {
                        Marshal.Copy(blob, 0, cred.CredentialBlob, blob.Length);
                        return CredWrite(ref cred, 0) ? 0 : 3;
                    } finally {
                        if (cred.CredentialBlob != IntPtr.Zero) Marshal.FreeHGlobal(cred.CredentialBlob);
                    }
                }
                public static byte[] Get(string target, out int code) {
                    code = 3;
                    IntPtr p;
                    if (!CredRead(target, 1, 0, out p)) {
                        code = Marshal.GetLastWin32Error() == 1168 ? 2 : 3;
                        return null;
                    }
                    try {
                        var cred = Marshal.PtrToStructure<CREDENTIAL>(p);
                        int n = (int)cred.CredentialBlobSize;
                        if (n < 0 || n > 2048 || cred.CredentialBlob == IntPtr.Zero) { code = 3; return null; }
                        byte[] blob = new byte[n];
                        if (n > 0) Marshal.Copy(cred.CredentialBlob, blob, 0, n);
                        code = 0;
                        return blob;
                    } finally { CredFree(p); }
                }
                public static int Delete(string target) {
                    if (CredDelete(target, 1, 0)) return 0;
                    return Marshal.GetLastWin32Error() == 1168 ? 2 : 3;
                }
            }
            "@
            if ($Op -eq 'probe') { exit [WardenCred]::Probe() }
            if ($Target -notmatch '^warden/[a-z][a-z0-9_-]{0,63}$') { exit 4 }
            if ($Op -eq 'put') {
                $ms = New-Object System.IO.MemoryStream
                $stdin = [Console]::OpenStandardInput()
                $buf = New-Object byte[] 1024
                while ($true) {
                    $n = $stdin.Read($buf, 0, $buf.Length)
                    if ($n -le 0) { break }
                    $ms.Write($buf, 0, $n)
                }
                exit [WardenCred]::Put($Target, $ms.ToArray())
            }
            if ($Op -eq 'get') {
                $code = 0
                $blob = [WardenCred]::Get($Target, [ref]$code)
                if ($code -ne 0) { exit $code }
                if ($blob -ne $null -and $blob.Length -gt 0) {
                    [Console]::OpenStandardOutput().Write($blob, 0, $blob.Length)
                }
                exit 0
            }
            if ($Op -eq 'delete') { exit [WardenCred]::Delete($Target) }
            exit 4
            """;

    @Override public boolean available() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            try {
                Outcome probe = invoke("probe", null, null);
                available = probe.code == 0;
                if (!available) lastFailure = "probe exit " + probe.code;
            } catch (RuntimeException failure) {
                available = false;
                lastFailure = failureReason(lastFailure, failure);
            }
            return available;
        }
    }

    @Override public String read(String target) {
        requireTarget(target);
        if (!available()) throw closed();
        Outcome outcome = invoke("get", target, null);
        if (outcome.code == 2) return null;
        if (outcome.code != 0 || outcome.overflow) throw closed();
        return new String(outcome.stdout, StandardCharsets.UTF_8);
    }

    @Override public void write(String target, String secret) {
        requireTarget(target);
        byte[] bytes = secretBytes(secret);
        if (!available()) throw closed();
        Outcome outcome = invoke("put", target, bytes);
        if (outcome.code != 0 || contains(outcome.stdout, bytes) || contains(outcome.stderr, bytes)) {
            lastFailure = "put exit " + outcome.code;
            throw closed();
        }
    }

    @Override public void delete(String target) {
        requireTarget(target);
        if (!available()) throw closed();
        Outcome outcome = invoke("delete", target, null);
        if (outcome.code != 0 && outcome.code != 2) throw closed();
    }

    static String script() { return SCRIPT; }

    static Path helperDirectory() { return helper(); }

    static List<String> lastCommandForTest() { return lastCommand; }

    static String lastFailureForTest() { return lastFailure; }

    static String failureReason(String previous, RuntimeException failure) {
        String message = failure.getMessage();
        return previous != null && closed().getMessage().equals(message) ? previous : message;
    }

    private static void requireTarget(String target) {
        if (target == null || !TARGET.matcher(target).matches()) throw closed();
    }

    private static byte[] secretBytes(String secret) {
        if (secret == null || secret.isEmpty() || secret.length() > MAX_SECRET_BYTES) throw closed();
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != secret.length() || bytes.length > MAX_SECRET_BYTES) throw closed();
        return bytes;
    }

    private static Outcome invoke(String operation, String target, byte[] secret) {
        if (secret != null && secret.length > MAX_SECRET_BYTES) throw closed();
        Path dir = helper();
        Path powershell = powershell();
        List<String> command = new ArrayList<>();
        command.add(powershell.toString());
        command.add("-NoProfile");
        command.add("-NonInteractive");
        command.add("-ExecutionPolicy");
        command.add("Bypass");
        command.add("-File");
        command.add(dir.resolve("store.ps1").toString());
        command.add(operation);
        if (target != null) command.add(target);
        if (secret != null) {
            for (String argument : command) {
                if (contains(argument.getBytes(StandardCharsets.UTF_8), secret)) throw closed();
            }
        }
        synchronized (LOCK) { lastCommand = List.copyOf(command); }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        try {
            Process process = builder.start();
            Capture stdout = new Capture();
            Capture stderr = new Capture();
            Thread outThread = drain(process.getInputStream(), stdout);
            Thread errThread = drain(process.getErrorStream(), stderr);
            try (OutputStream stdin = process.getOutputStream()) {
                if (secret != null) stdin.write(secret);
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                destroy(process);
                lastFailure = "credential helper timed out";
                throw closed();
            }
            outThread.join(1000);
            errThread.join(1000);
            return new Outcome(process.exitValue(), stdout.bytes, stderr.bytes, stdout.overflow || stderr.overflow);
        } catch (IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            lastFailure = "credential helper invocation failed: " + failure.getMessage();
            throw closed();
        }
    }

    private static Path helper() {
        synchronized (LOCK) {
            try {
                if (currentUser == null) currentUser = resolveCurrentUser();
                if (helperDir == null || !Files.isDirectory(helperDir)) helperDir = createHelper();
                verifyOwnerOnly(helperDir);
                Path script = helperDir.resolve("store.ps1");
                if (!Files.isRegularFile(script) || !SCRIPT.equals(Files.readString(script))) {
                    throw new IOException("credential helper script changed");
                }
                return helperDir;
            } catch (IOException | RuntimeException failure) {
                helperDir = null;
                lastFailure = failure.getMessage();
                throw closed();
            }
        }
    }

    private static Path createHelper() throws IOException {
        Path dir = Files.createTempDirectory("warden-os-store-");
        try {
            lockToOwner(dir);
            verifyOwnerOnly(dir);
            Files.writeString(dir.resolve("store.ps1"), SCRIPT, StandardCharsets.UTF_8);
            verifyOwnerOnly(dir);
            return dir;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            deleteTree(dir);
            throw new IOException("credential helper directory cannot be locked: " + failure.getMessage(), failure);
        }
    }

    private static void lockToOwner(Path dir) throws IOException, InterruptedException {
        // Elevated administrators can create directories owned by the Administrators group.
        // Both ownership and the grant must identify the process user, never that group.
        String user = "*" + currentUser.sid();
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw new IOException("credential helper directory cannot be locked");
        Path icacls = Path.of(root, "System32", "icacls.exe");
        runAclCommand(icacls, dir, "/setowner", user);
        runAclCommand(icacls, dir, "/inheritance:r", "/grant:r", user + ":(OI)(CI)F");
        // icacls /grant:r only replaces this user's entries; explicit entries for other principals
        // (for example the Administrators group on an elevated runner) survive it. Write the whole
        // DACL so it holds exactly one entry, then keep it protected from parent inheritance.
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("credential helper directory has no ACL");
        view.setAcl(List.of(currentUserOnlyEntry(currentUser.principal())));
        runAclCommand(icacls, dir, "/inheritance:r");
    }

    static AclEntry currentUserOnlyEntry(UserPrincipal user) {
        return AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(user)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
    }

    private static void runAclCommand(Path icacls, Path dir, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(icacls.toString(), dir.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
            destroy(process);
            throw new IOException("credential helper directory cannot be locked");
        }
    }

    private static CurrentUser resolveCurrentUser() throws IOException {
        // Query the Windows access token and emit UTF-8 explicitly so non-ASCII account
        // names work regardless of the runner's console code page.
        Process process = new ProcessBuilder(powershell().toString(), "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
                + "$u = [System.Security.Principal.WindowsIdentity]::GetCurrent(); "
                + "[Console]::WriteLine($u.User.Value); [Console]::WriteLine($u.Name)")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Capture output = new Capture();
        Thread reader = drain(process.getInputStream(), output);
        try {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("credential helper current user lookup timed out");
            }
            reader.join(1000);
            if (process.exitValue() != 0 || reader.isAlive() || output.overflow) {
                throw new IOException("credential helper current user lookup failed");
            }
            String[] identity = parseCurrentUser(new String(output.bytes, StandardCharsets.UTF_8));
            UserPrincipal principal = Path.of(".").getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(identity[1]);
            aclGrantPrincipal(principal); // Fail closed if lookup unexpectedly resolves a group.
            return new CurrentUser(identity[0], principal);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("credential helper current user lookup interrupted", failure);
        } finally {
            if (process.isAlive()) destroy(process);
        }
    }

    static String[] parseCurrentUser(String output) throws IOException {
        String[] lines = output.strip().split("\\R");
        if (lines.length != 2 || !lines[0].matches("S-1-(?:\\d+-)+\\d+")
                || !qualifiedPrincipalName(lines[1]) || lines[1].indexOf('\\') < 0) {
            throw new IOException("credential helper current user identity is invalid");
        }
        return lines;
    }

    private static void verifyOwnerOnly(Path dir) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) throw new IOException("credential helper directory has no ACL");
        verifyCurrentUserOnly(currentUser.principal(), view.getOwner(), view.getAcl());
    }

    static void verifyCurrentUserOnly(UserPrincipal user, UserPrincipal owner, List<AclEntry> entries) throws IOException {
        if (!samePrincipal(user, owner)) {
            throw new IOException("credential helper directory owner is not the current user");
        }
        if (entries.isEmpty()) throw new IOException("credential helper directory ACL is empty");
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW || !samePrincipal(user, entry.principal())) {
                String who = entry.principal() == null ? "unknown" : entry.principal().getName();
                throw new IOException("credential helper directory ACL is not limited to the current user ("
                        + entry.type() + " " + who + ")");
            }
        }
    }

    static String aclGrantPrincipal(UserPrincipal owner) throws IOException {
        if (owner instanceof GroupPrincipal) {
            throw new IOException("credential helper directory owner is not an individual user");
        }
        String name = owner == null ? null : owner.getName();
        if (!qualifiedPrincipalName(name)) {
            throw new IOException("credential helper directory owner is not fully qualified");
        }
        // icacls requires '*' before a numeric SID instead of resolving it as an account name.
        return name.matches("(?i)S-\\d+(?:-\\d+)+") ? "*" + name : name;
    }

    private static boolean qualifiedPrincipalName(String name) {
        if (name == null || name.isBlank() || name.indexOf('"') >= 0 || name.indexOf(':') >= 0
                || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('/') >= 0) return false;
        if (name.matches("(?i)S-\\d+(?:-\\d+)+")) return true;
        int slash = name.indexOf('\\');
        return slash > 0 && slash == name.lastIndexOf('\\') && slash < name.length() - 1;
    }

    static boolean samePrincipal(UserPrincipal owner, UserPrincipal entry) {
        if (owner == null || entry == null || owner instanceof GroupPrincipal || entry instanceof GroupPrincipal) return false;
        if (owner.equals(entry)) return true;
        String left = owner.getName();
        String right = entry.getName();
        return qualifiedPrincipalName(left) && qualifiedPrincipalName(right) && left.equalsIgnoreCase(right);
    }

    private static Path powershell() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) throw closed();
        Path path = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        if (!Files.isRegularFile(path)) throw closed();
        return path;
    }

    private static Thread drain(InputStream input, Capture capture) {
        Thread thread = new Thread(() -> capture.read(input), "warden-os-store");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void destroy(Process process) {
        process.descendants().forEach(java.lang.ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void deleteTree(Path dir) {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) { /* the helper contains no credential */ }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0 || haystack.length < needle.length) return false;
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static IllegalStateException closed() {
        return new IllegalStateException("connection credential source is unavailable");
    }

    private static final class Capture {
        private byte[] bytes = new byte[0];
        private boolean overflow;

        private void read(InputStream input) {
            try (input) {
                ByteArrayOutputStream kept = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int total = 0;
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (total < MAX_SECRET_BYTES) {
                        int keep = Math.min(count, MAX_SECRET_BYTES - total);
                        kept.write(buffer, 0, keep);
                        total += keep;
                        if (keep < count) overflow = true;
                    } else overflow = true;
                }
                bytes = kept.toByteArray();
            } catch (IOException failure) {
                overflow = true;
            }
        }
    }

    private record Outcome(int code, byte[] stdout, byte[] stderr, boolean overflow) {}

    private record CurrentUser(String sid, UserPrincipal principal) {}
}
