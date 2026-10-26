package dev.warden;

import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Uses synthetic environment values in a nested JVM, never a live provider credential. */
public final class ProcessEnvironmentTest implements Suite {
    private static final String ONE = "WARDEN_TEST_ISOLATION_KEY_ONE";
    private static final String TWO = "WARDEN_TEST_ISOLATION_KEY_TWO";
    private static final String KEEP = "WARDEN_TEST_ISOLATION_KEEP";
    private static final String VALUE_ONE = "synthetic-fixture-secret-one";
    private static final String VALUE_TWO = "synthetic-fixture-secret-two";
    private static final String VALUE_KEEP = "ordinary-fixture-setting";

    @Override public String name() { return "process-env"; }

    @Override public void run(Check check) throws Exception {
        Path scratch = Files.createTempDirectory("warden-process-environment-");
        String parentOne = System.getenv(ONE);
        String parentTwo = System.getenv(TWO);
        ProcessBuilder fixture = new ProcessBuilder(command("fixture"));
        fixture.directory(scratch.toFile());
        fixture.environment().put(ONE, VALUE_ONE);
        fixture.environment().put(TWO, VALUE_TWO);
        fixture.environment().put(KEEP, VALUE_KEEP);
        Process child = fixture.start();
        if (!child.waitFor(45, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            check.that("nested environment fixture completes within its deadline", false);
            return;
        }
        check.eq("nested environment fixture exits cleanly", 0, child.exitValue());
        if (child.exitValue() != 0) return;
        Map<String, Object> report = Json.parseObject(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        for (String assertion : List.of("default_inherits", "first_copy_removes_only_one",
                "union_removes_both", "unrelated_preserved", "normal_environment_preserved",
                "parent_unchanged", "base_runner_unchanged", "preview_clean", "raw_clean",
                "vendor_path_clean", "empty_copy_preserves_default")) {
            check.eq(assertion.replace('_', ' '), true, report.get(assertion));
        }
        check.that("creating a fixture environment does not change the test JVM",
                Objects.equals(parentOne, System.getenv(ONE)) && Objects.equals(parentTwo, System.getenv(TWO)));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "probe".equals(args[0])) {
            System.out.println("ONE=" + value(ONE));
            System.out.println("TWO=" + value(TWO));
            System.out.println("KEEP=" + value(KEEP));
            System.out.println("PATH_PRESENT=" + pathPresent());
            System.err.println("ERR_ONE=" + value(ONE));
            System.err.println("ERR_TWO=" + value(TWO));
            return;
        }
        if (args.length != 1 || !"fixture".equals(args[0])) throw new IllegalArgumentException("fixture mode required");
        Path directory = Path.of("").toAbsolutePath();
        ProcessRunner original = new ProcessRunner();
        List<String> probe = command("probe");
        ProcessRunner.Result normal = original.run(probe, directory, Duration.ofSeconds(15));
        Set<String> names = new LinkedHashSet<>(Set.of(removalName(ONE)));
        ProcessRunner first = original.withoutEnvironment(names);
        names.clear(); // A caller retaining a mutable set cannot change the runner later.
        ProcessRunner union = first.withoutEnvironment(Set.of(removalName(TWO)));
        ProcessRunner.Result firstResult = first.run(probe, directory, Duration.ofSeconds(15));
        Path out = directory.resolve("scrubbed.stdout.txt");
        Path err = directory.resolve("scrubbed.stderr.txt");
        ProcessRunner.Result both = union.run(probe, directory, Duration.ofSeconds(15),
                ProcessRunner.DEFAULT_CAPTURE_BYTES, null, out, err);
        Path vendorOut = directory.resolve("vendor.stdout.txt");
        Path vendorErr = directory.resolve("vendor.stderr.txt");
        ProcessRunner.Result vendor = union.runVendor(probe, directory, Duration.ofSeconds(15),
                ProcessRunner.DEFAULT_CAPTURE_BYTES, null, vendorOut, vendorErr, ProcessRunner.DEFAULT_RAW_BYTES);
        ProcessRunner.Result originalAgain = original.run(probe, directory, Duration.ofSeconds(15));
        ProcessRunner.Result emptyCopy = original.withoutEnvironment(Set.of())
                .run(probe, directory, Duration.ofSeconds(15));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("default_inherits", normal.ok() && hasBoth(normal.stdout()));
        report.put("first_copy_removes_only_one", firstResult.ok()
                && firstResult.stdout().contains("ONE=<absent>") && firstResult.stdout().contains(VALUE_TWO));
        report.put("union_removes_both", both.ok() && both.stdout().contains("ONE=<absent>")
                && both.stdout().contains("TWO=<absent>"));
        report.put("unrelated_preserved", both.stdout().contains("KEEP=" + VALUE_KEEP));
        report.put("normal_environment_preserved", both.stdout().contains("PATH_PRESENT=" + pathPresent()));
        report.put("parent_unchanged", VALUE_ONE.equals(System.getenv(ONE))
                && VALUE_TWO.equals(System.getenv(TWO)) && VALUE_KEEP.equals(System.getenv(KEEP)));
        report.put("base_runner_unchanged", originalAgain.ok() && hasBoth(originalAgain.stdout()));
        report.put("preview_clean", clean(both.stdout() + both.stderr() + String.join(" ", both.command())));
        report.put("raw_clean", clean(Files.readString(out) + Files.readString(err)));
        report.put("vendor_path_clean", vendor.ok() && clean(vendor.stdout() + vendor.stderr()
                + Files.readString(vendorOut) + Files.readString(vendorErr))
                && vendor.stdout().contains("KEEP=" + VALUE_KEEP));
        report.put("empty_copy_preserves_default", emptyCopy.ok() && hasBoth(emptyCopy.stdout()));
        // Only booleans cross back to the test runner; fixture credentials never enter an
        // assertion failure detail or the outer test's terminal output.
        System.out.print(Json.write(report));
    }

    private static String removalName(String name) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name.toLowerCase(java.util.Locale.ROOT) : name;
    }

    private static String value(String name) {
        String value = System.getenv(name);
        return value == null ? "<absent>" : value;
    }

    private static boolean pathPresent() {
        return System.getenv().keySet().stream().anyMatch("PATH"::equalsIgnoreCase);
    }

    private static boolean clean(String value) { return !value.contains(VALUE_ONE) && !value.contains(VALUE_TWO); }
    private static boolean hasBoth(String value) { return value.contains(VALUE_ONE) && value.contains(VALUE_TWO); }

    private static List<String> command(String mode) {
        String executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString();
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(File.pathSeparator));
        return List.of(executable, "-cp", classpath, ProcessEnvironmentTest.class.getName(), mode);
    }
}
