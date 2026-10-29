package dev.warden.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps Warden's own run evidence away from an OpenCode implementer.
 *
 * <p>On 2026-10-28 a GonkaRouter implementer spent its relay turns reading
 * {@code .warden/runs/} - earlier runs' prompts, reviews and verdicts - instead of the task.
 * That evidence is not the implementer's input, and reading the reviewer's earlier findings
 * would let it argue with the review instead of doing the work.
 *
 * <p>Two layers. OpenCode permission rules deny read, edit and shell access to the run tree on
 * every platform. Where bubblewrap works (Linux), the process also runs in a mount namespace in
 * which the run tree and the Warden home are empty: a shell command cannot reach them at all.
 * Only this dispatch's own exchange directory stays visible, for the dispatch file and the
 * artifact the implementer must write.
 */
final class RunArtifactShield {
    static final String RUNS = ".warden/runs";
    private static volatile Boolean bubblewrap;

    private final List<Path> hidden;
    private final Path exchange;
    private final boolean namespace;

    private RunArtifactShield(List<Path> hidden, Path exchange, boolean namespace) {
        this.hidden = List.copyOf(hidden);
        this.exchange = exchange;
        this.namespace = namespace;
    }

    /**
     * @param projectRoot the tree the implementer works in
     * @param home the user's Warden home (connections, verification receipts), may be null
     * @param exchange this dispatch's own directory; it must stay reachable
     * @param kept paths the process needs (its executable); a hidden path never covers them
     */
    static RunArtifactShield plan(Path projectRoot, Path home, Path exchange, List<Path> kept, boolean sandbox) {
        Path root = real(projectRoot);
        Path io = real(exchange);
        List<Path> needed = new ArrayList<>(List.of(root, io));
        for (Path path : kept) if (path != null) needed.add(real(path));
        List<Path> hidden = new ArrayList<>();
        for (Path candidate : new Path[] {projectRoot.resolve(RUNS), home}) {
            if (candidate == null || !Files.isDirectory(candidate)) continue;
            Path path = real(candidate);
            // Never blank out something the dispatch itself stands in, for example a home
            // that contains the project in a test fixture.
            boolean covers = false;
            for (Path need : needed) if (!need.equals(io) && need.startsWith(path)) covers = true;
            if (!covers && !hidden.contains(path)) hidden.add(path);
        }
        return new RunArtifactShield(hidden, io, sandbox && bubblewrapWorks());
    }

    /** "mount_namespace" when the OS enforces it, else "permission_rules". */
    String mode() { return namespace ? "mount_namespace" : "permission_rules"; }

    List<String> hiddenPaths() { return hidden.stream().map(Path::toString).toList(); }

    /** The command, wrapped so hidden paths are empty tmpfs mounts for this process tree. */
    List<String> wrap(List<String> command) {
        if (!namespace) return command;
        List<String> wrapped = new ArrayList<>(List.of(
                "bwrap", "--dev-bind", "/", "/", "--unshare-pid", "--proc", "/proc", "--die-with-parent"));
        for (Path path : hidden) wrapped.addAll(List.of("--tmpfs", path.toString()));
        // Under a hidden tree the exchange directory is bound back after the tmpfs.
        if (hidden.stream().anyMatch(exchange::startsWith))
            wrapped.addAll(List.of("--bind", exchange.toString(), exchange.toString()));
        wrapped.add("--");
        wrapped.addAll(command);
        return List.copyOf(wrapped);
    }

    /**
     * OpenCode rules. The last matching rule wins, so the catch-all comes first, the run tree
     * is denied after it, and this dispatch's own exchange directory is allowed last.
     */
    static Map<String, Object> permissions(Path projectRoot, Path exchange, Path home) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("*", "deny");
        rules.put("read", pathRules(projectRoot, exchange));
        rules.put("edit", pathRules(projectRoot, exchange));
        rules.put("write", "allow");
        rules.put("apply_patch", "allow");
        Map<String, Object> bash = new LinkedHashMap<>();
        bash.put("*", "allow");
        bash.put("*.warden/runs*", "deny");
        bash.put("*~/.warden*", "deny");
        bash.put("*$HOME/.warden*", "deny");
        if (home != null) bash.put("*" + slash(real(home)) + "*", "deny");
        rules.put("bash", bash);
        return rules;
    }

    private static Map<String, Object> pathRules(Path projectRoot, Path exchange) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("*", "allow");
        rules.put(RUNS + "/*", "deny");
        rules.put("*/" + RUNS + "/*", "deny");
        rules.put(slash(real(projectRoot).resolve(RUNS)) + "/*", "deny");
        Path root = real(projectRoot);
        Path io = real(exchange);
        rules.put(slash(io) + "/*", "allow");
        // OpenCode matches paths inside the project in their project-relative form, so the
        // exchange directory, which lives under the run tree, needs that form allowed as well.
        if (io.startsWith(root) && !io.equals(root)) rules.put(slash(root.relativize(io)) + "/*", "allow");
        return rules;
    }

    private static String slash(Path path) { return path.toString().replace('\\', '/'); }

    private static Path real(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (Exception missing) {
            return absolute;
        }
    }

    static boolean bubblewrapWorks() {
        Boolean known = bubblewrap;
        if (known != null) return known;
        boolean works = false;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")
                && !"0".equals(System.getenv("WARDEN_OPENCODE_SANDBOX"))) {
            try {
                Process probe = new ProcessBuilder("bwrap", "--dev-bind", "/", "/", "--unshare-pid",
                        "--proc", "/proc", "--die-with-parent", "--", "true")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                works = probe.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        && probe.exitValue() == 0;
                if (!works) probe.destroyForcibly();
            } catch (Exception unavailable) {
                works = false;
            }
        }
        bubblewrap = works;
        return works;
    }
}
