package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Profile;
import dev.warden.config.ProjectInitializer;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.gate.GateRunner;
import dev.warden.gate.VisualQaRunner;
import dev.warden.execution.orca.OrcaClient;
import dev.warden.json.Json;
import dev.warden.ledger.LedgerReader;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.run.DoCommand;
import dev.warden.run.TaskLoop;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point. Command dispatch only — every command lives in its own class so that adding
 * one cannot change the behaviour of another.
 */
public final class Main {

    public static final String VERSION = "0.1.0-dev";
    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    public static void main(String[] args) {
        useUtf8ForOutput();
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            System.exit(args.length == 0 ? 2 : 0);
        }
        if (args[0].equals("--version")) {
            System.out.println("warden " + VERSION);
            return;
        }
        try {
            int exit = switch (args[0]) {
                case "validate" -> validate(args);
                case "init" -> init(args);
                case "gates" -> gates(args);
                case "visual-qa" -> visualQa(args);
                case "setup" -> setup();
                case "profiles" -> profiles();
                case "role" -> role(args);
                case "run" -> runLoop(args);
                case "do" -> doIntent(args);
                case "doctor" -> doctor();
                case "ledger" -> ledger();
                default -> {
                    System.err.println("warden: unknown command '" + args[0] + "'");
                    usage();
                    yield 2;
                }
            };
            if (exit != 0) System.exit(exit);
        } catch (Exception failure) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("ok", false);
            error.put("code", "warden_error");
            error.put("message", String.valueOf(failure.getMessage()));
            System.err.println(Json.write(error));
            System.exit(1);
        }
    }

    /**
     * Every report Warden writes to disk is UTF-8; its console streams follow the Windows
     * ANSI codepage unless told otherwise, so a Russian goal read correctly from a file was
     * still printed as question marks. The file was right and the screen was wrong, which is
     * the harder version of the bug to notice.
     */
    private static void useUtf8ForOutput() {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static int validate(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("validate requires a task id or YAML path");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("project", loaded.project().project());
        result.put("task_id", loaded.resolved().id());
        result.put("risk", loaded.resolved().risk());
        result.put("scope_paths", loaded.resolved().scopePaths());
        result.put("acceptance_commands", loaded.resolved().acceptanceCommands());
        System.out.println(Json.write(result));
        return 0;
    }

    private static int init(String[] args) throws Exception {
        String baseRef = option(args, "--base-ref", "origin/main");
        ProjectInitializer.Result initialized = new ProjectInitializer().initialize(Path.of("."), baseRef);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("detected_build", initialized.detectedBuild());
        result.put("project_file", initialized.projectFile().toString());
        result.put("example_task", initialized.exampleTask().toString());
        result.put("checks", initialized.checks());
        result.put("next", "review files, then run warden validate example");
        System.out.println(Json.write(result));
        return 0;
    }

    private static int gates(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("gates requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        GateRunner.Outcome outcome = new GateRunner(new ProcessRunner()).run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    private static int visualQa(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("visual-qa requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        VisualQaRunner.Outcome outcome = new VisualQaRunner(new ProcessRunner()).run(loaded, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("report", outcome.report().toString());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /** Create a starter ~/.warden so the first dry run explains itself. Never overwrites. */
    private static int setup() throws Exception {
        UserSetup.Result outcome = new UserSetup().run(UserConfig.defaultHome());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("home", outcome.home().toString());
        result.put("created", outcome.created());
        result.put("skipped_existing", outcome.skipped());
        result.put("next", "warden profiles, then warden role reviewer <task> --dry-run");
        System.out.println(Json.write(result));
        return 0;
    }

    /**
     * What the resolver can see. An unverified profile is listed with the probe that would
     * verify it, because "why did nothing run" is the question this command exists to answer.
     */
    private static int profiles() throws Exception {
        UserConfig user = UserConfig.load();
        List<Object> rows = new ArrayList<>();
        for (Profile profile : user.profiles().values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("profile", profile.name());
            row.put("role", profile.role());
            row.put("vendor", profile.vendor());
            row.put("command", profile.command());
            row.put("runner", profile.runner());
            row.put("read_only", profile.readOnly());
            row.put("verified", profile.verified());
            row.put("eligible", profile.verified());
            if (!profile.verified()) {
                row.put("blocked_because", "profile is unverified; fill verification.verified_on "
                        + "only after running its probe");
            }
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", user.policyPresent() && !user.profiles().isEmpty());
        result.put("home", user.home().toString());
        result.put("policy_present", user.policyPresent());
        result.put("profiles", rows);
        result.put("problems", user.problems());
        result.put("dangling_policy_references", user.danglingProfileReferences());
        if (!user.policyPresent()) result.put("hint", "run `warden setup` to create a starter configuration");
        System.out.println(Json.write(result));
        return Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
    }

    /**
     * Run one role. `--dry-run` resolves the vendor and writes the exact prompt without
     * spending anything — the cheapest way to find out whether the setup is right.
     */
    private static int role(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("role requires a role name and a task, "
                    + "for example: warden role reviewer my-task --dry-run");
        }
        String roleName = args[1];
        String taskSelector = args[2];
        boolean dryRun = hasFlag(args, "--dry-run");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        String implementerVendor = option(args, "--implementer-vendor", null);
        String contextPath = option(args, "--context", null);

        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), taskSelector);
        UserConfig user = UserConfig.load();
        RoleRunner.Outcome outcome = new RoleRunner(new ProcessRunner()).run(
                loaded, user, roleName, runId, implementerVendor,
                contextPath == null ? null : Path.of(contextPath), dryRun);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("code", outcome.code());
        result.put("role", outcome.role());
        result.put("profile", outcome.profile());
        result.put("vendor", outcome.vendor());
        result.put("rejected_profiles", outcome.rejected());
        result.put("run_id", runId);
        if (outcome.report() != null) result.put("report", outcome.report().toString());
        if (outcome.details() != null) result.put("details", outcome.details());
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /**
     * The bounded loop. It stops at the human gate and never lands anything: the only two
     * outcomes are `human_gate` and `human_escalation`.
     */
    private static int runLoop(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("run requires a task id or YAML path");
        String runId = option(args, "--run-id", loadedDefaultRunId());
        boolean dryRun = hasFlag(args, "--dry-run");
        ConfigLoader.Loaded loaded = new ConfigLoader().load(Path.of("."), args[1]);
        UserConfig user = UserConfig.load();
        TaskLoop.Outcome outcome = new TaskLoop(new ProcessRunner()).run(loaded, user, runId, dryRun);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        result.put("reason", outcome.reason());
        result.put("next_action", outcome.nextAction());
        result.put("run_id", runId);
        result.put("summary", String.valueOf(outcome.summary()));
        result.put("steps", outcome.summaryReport().get("steps"));
        System.out.println(Json.write(result));
        return outcome.ok() ? 0 : 1;
    }

    /**
     * Goal plus project. Isolates via Orca, writes a task, runs the loop, stops at the human
     * gate. Never lands.
     */
    private static int doIntent(String[] args) throws Exception {
        DoCommand.Options options = DoCommand.parse(args);
        UserConfig user = UserConfig.load();
        DoCommand.Outcome outcome = new DoCommand(new ProcessRunner()).run(options, user);
        System.out.println(Json.write(outcome.report()));
        return outcome.ok() ? 0 : 1;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String argument : args) if (argument.equals(name)) return true;
        return false;
    }

    private static int doctor() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        ProcessRunner processes = new ProcessRunner();
        Map<String, Object> orca = new OrcaClient(processes).status(Path.of("."));
        boolean javaSupported = Runtime.version().feature() >= 21;
        boolean projectConfig = java.nio.file.Files.isRegularFile(Path.of(".warden/project.yaml"));
        boolean orcaAvailable = Boolean.TRUE.equals(orca.get("available"));
        boolean machineGatesReady = javaSupported && projectConfig;
        UserConfig user = UserConfig.load();
        List<String> eligible = user.profiles().values().stream()
                .filter(Profile::verified).map(Profile::name).toList();
        List<String> unverified = user.profiles().values().stream()
                .filter(profile -> !profile.verified()).map(Profile::name).toList();
        boolean roleExecutionReady = user.policyPresent() && user.policy() != null
                && !eligible.isEmpty() && user.danglingProfileReferences().isEmpty();
        result.put("ok", machineGatesReady);
        result.put("warden_version", VERSION);
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_feature", (long) Runtime.version().feature());
        result.put("java_21_or_newer", javaSupported);
        // Where a Russian or otherwise non-ASCII goal silently turns into question marks.
        // Reported unconditionally: an operator should learn this from `doctor`, not from a
        // task contract that came out mangled.
        Map<String, Object> encoding = new LinkedHashMap<>();
        encoding.put("sun_jnu_encoding", System.getProperty("sun.jnu.encoding"));
        encoding.put("native_encoding", System.getProperty("native.encoding"));
        encoding.put("file_encoding", System.getProperty("file.encoding"));
        boolean argsSafe = dev.warden.run.DoCommand.argumentsCanCarryNonAscii();
        encoding.put("non_ascii_arguments_safe", argsSafe);
        if (!argsSafe) {
            encoding.put("consequence", "non-ASCII text in a command-line argument reaches Warden as "
                    + "'?'. No JVM flag changes this. Use `warden do --goal-file <utf8 file>`, or "
                    + "enable the Windows setting \"Use Unicode UTF-8 for worldwide language support\".");
        }
        result.put("argument_encoding", encoding);
        result.put("project_config_found", projectConfig);
        result.put("machine_gates_ready", machineGatesReady);
        result.put("orca", orca);
        Map<String, Object> userSummary = new LinkedHashMap<>();
        userSummary.put("home", user.home().toString());
        userSummary.put("policy_present", user.policyPresent());
        userSummary.put("profiles_loaded", (long) user.profiles().size());
        userSummary.put("verified_profiles", eligible);
        userSummary.put("unverified_profiles", unverified);
        userSummary.put("problems", user.problems());
        userSummary.put("dangling_policy_references", user.danglingProfileReferences());
        result.put("user_config", userSummary);
        result.put("role_execution_ready", roleExecutionReady);
        if (!roleExecutionReady) {
            result.put("role_execution_blocked_by", !user.policyPresent()
                    ? "no ~/.warden/policy.yaml — run `warden setup`"
                    : eligible.isEmpty()
                        ? "every profile is unverified; run its probe, then set verification.verified_on"
                        : "policy references profiles that do not load: " + user.danglingProfileReferences());
        }
        result.put("direct_cli_ready", roleExecutionReady);
        result.put("orca_adapter", Map.of(
                "available", orcaAvailable,
                "orchestration_contract", Boolean.TRUE.equals(orca.get("orchestration_contract")),
                "creates_worktrees", false,
                "completion", "worker_done or dispatch-show settlement only; terminal text is not evidence"));
        result.put("note", machineGatesReady
                ? (roleExecutionReady
                    ? "Machine gates and Direct CLI role execution are ready. Orca is an optional runner "
                        + "and only attaches to an existing worktree."
                    : "Machine gates are ready; role execution waits on a verified profile in ~/.warden.")
                : "Machine gates need Java 21+ and .warden/project.yaml in the target repository");
        System.out.println(Json.write(result));
        return Boolean.TRUE.equals(result.get("ok")) ? 0 : 1;
    }

    private static int ledger() throws Exception {
        Path root = new ConfigLoader().findProjectRoot(Path.of("."));
        System.out.println(Json.write(new LedgerReader().summarize(root)));
        return 0;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) return args[index + 1];
        }
        return fallback;
    }

    private static String loadedDefaultRunId() {
        return "run-" + RUN_ID_TIME.format(Instant.now());
    }

    private static void usage() {
        System.out.println("""
                warden - deterministic SDLC gates for an AI worktree; never lands changes.

                  warden setup                 create a starter ~/.warden (never overwrites)
                  warden doctor                verify Java, vendor profiles and live Orca readiness
                  warden profiles              which profiles load, which are eligible, and why not
                  warden init [--base-ref REF] create a conservative project starter config
                  warden validate <task>       validate and resolve the project task contract
                  warden gates <task>          preflight and machine gates only; spends nothing
                  warden visual-qa <task>      launch preview, screenshot, assert control visibility
                  warden role <role> <task>    run one role; add --dry-run to spend nothing
                  warden do \"<goal>\" [--project DIR] [--scope NAME] [--goal-file FILE]
                                           the whole workflow; stops at the human gate
                  warden run <task>            the bounded loop; stops at the human gate
                  warden ledger                aggregate local evidence and experiment dimensions

                The command to use is `warden do`. Everything else is a piece of that loop.
                Vendor configuration lives in ~/.warden/, project configuration in .warden/.
                Warden never merges. See spec/SPEC.md and docs/ARCHITECTURE.md.""");
    }
}
