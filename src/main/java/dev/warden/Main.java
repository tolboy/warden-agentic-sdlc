package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.ProjectInitializer;
import dev.warden.gate.GateRunner;
import dev.warden.execution.orca.OrcaClient;
import dev.warden.json.Json;
import dev.warden.ledger.LedgerReader;
import dev.warden.process.ProcessRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point. Command dispatch only — every command lives in its own class so that adding
 * one cannot change the behaviour of another.
 */
public final class Main {

    public static final String VERSION = "0.1.0-dev";

    public static void main(String[] args) {
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

    private static int doctor() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        ProcessRunner processes = new ProcessRunner();
        Map<String, Object> orca = new OrcaClient(processes).status(Path.of("."));
        boolean javaSupported = Runtime.version().feature() >= 21;
        boolean projectConfig = java.nio.file.Files.isRegularFile(Path.of(".warden/project.yaml"));
        boolean orcaAvailable = Boolean.TRUE.equals(orca.get("available"));
        result.put("ok", javaSupported && orcaAvailable);
        result.put("warden_version", VERSION);
        result.put("java_version", System.getProperty("java.version"));
        result.put("java_feature", (long) Runtime.version().feature());
        result.put("java_21_or_newer", javaSupported);
        result.put("project_config_found", projectConfig);
        result.put("orca", orca);
        result.put("role_execution_ready", false);
        result.put("note", "Orca readiness is verified; mutating role lifecycle remains disabled until live conformance");
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
        return "run-" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14);
    }

    private static void usage() {
        System.out.println("""
                warden - deterministic SDLC gates for an AI worktree; never lands changes.

                  warden doctor                verify Java and live Orca readiness
                  warden init [--base-ref REF] create a conservative project starter config
                  warden validate <task>       validate and resolve the project task contract
                  warden gates <task>          preflight and machine gates only; spends nothing
                  warden ledger                aggregate local evidence and experiment dimensions

                Vendor configuration lives in ~/.warden/, project configuration in .warden/.
                Planned commands are documented, but not exposed until their adapters conform.
                See spec/SPEC.md and docs/ARCHITECTURE.md.""");
    }
}
