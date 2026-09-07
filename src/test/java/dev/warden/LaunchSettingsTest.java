package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.TaskSpec;
import dev.warden.execution.RoleExecutor;
import dev.warden.execution.orca.*;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LaunchSettingsTest implements Suite {
    public String name() { return "launch-settings"; }
    private static final String PROFILE = """
            version: 1
            profile: orca-review
            role: reviewer
            vendor: claude
            command: claude
            model: opus
            effort: max
            runner: orca
            read_only: true
            """;
    private static Profile profile() { return Profile.parse(PROFILE, "test"); }

    public void run(Check check) throws Exception {
        Profile p = profile();
        check.eq("explicit effort parsed", "max", p.effort());
        check.eq("orca uses the real prompt transport", "workspace_file", p.promptDelivery());
        check.rejects("Orca cannot lose allowedTools", "cannot forward args", () -> Profile.parse(
                PROFILE + "args: [\"--allowedTools\", \"Read\"]\n", "test"));
        check.rejects("Orca cannot lose sandbox", "cannot forward args", () -> Profile.parse(
                PROFILE + "args: [\"-s\", \"read-only\"]\n", "test"));
        check.rejects("Orca cannot claim stdin", "requires prompt_delivery", () -> Profile.parse(
                PROFILE + "prompt_delivery: stdin\n", "test"));
        check.rejects("Orca effort requires model", "requires an explicit model", () -> Profile.parse(
                PROFILE.replace("model: opus\n", ""), "test"));
        check.rejects("direct cannot label effort it does not send", "requires {{effort}}", () -> Profile.parse(
                PROFILE.replace("runner: orca", "runner: direct"), "test"));
        Profile direct = Profile.parse(PROFILE.replace("runner: orca", "runner: direct")
                + "args: [\"--effort\", \"{{effort}}\", \"--allowedTools\", \"Read\"]\n", "test");
        check.eq("direct tool grant survives effort declaration", "Read", direct.args().get(3));
        check.eq("launch flags pin both options", List.of("--agent", "claude", "--model", "opus", "--effort", "max"), OrcaLaunch.arguments(p));
        check.that("matching receipt accepted", OrcaLaunch.verify(p, envelope(Map.of("launch", launch("max")))).ok());
        check.that("missing receipt is not verification", !OrcaLaunch.verify(p, envelope(Map.of())).ok());
        check.that("downgraded effort rejected", !OrcaLaunch.verify(p, envelope(Map.of("launch", launch("high")))).ok());
        check.that("string start_options supported", OrcaLaunch.verify(p, envelope(Map.of("worker",
                Map.of("start_options", Json.write(Map.of("launch", launch("max"))))))).ok());
        check.eq("launch verification is not provider inference proof", false,
                OrcaLaunch.verify(p, envelope(Map.of("launch", launch("max")))).evidence().get("provider_execution_verified"));
        exercise(check, "max", true, false, false, "ok");
        exercise(check, "high", true, false, false, "role_orca_launch_unverified");
        exercise(check, "max", false, false, false, "role_orca_launch_unsupported");
        exercise(check, "max", true, true, false, "role_violated_read_only");
        exercise(check, "high", true, false, true, "role_orca_lifecycle_unaccounted");
    }

    private static Map<String, Object> launch(String effective) {
        return Map.of("requested", Map.of("agent", "claude", "model", "opus", "effort", "max"),
                "effective", Map.of("agent", "claude", "model", "opus", "effort", effective));
    }
    private static Map<String, Object> envelope(Map<String, Object> result) { return Map.of("ok", true, "result", result); }
    private static OrcaClient.Rpc rpc(Map<String, Object> result) {
        var body = envelope(result);
        return new OrcaClient.Rpc(true, 0, false, 0, body, result, Json.write(body), "");
    }

    private static void exercise(Check check, String effort, boolean capability, boolean writes,
                                 boolean uncertainStop, String expected) throws Exception {
        Path root = Files.createTempDirectory("warden-orca-launch-");
        ProcessRunner processes = new ProcessRunner();
        for (List<String> command : List.of(List.of("git", "init", "-q"),
                List.of("git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                        "commit", "--allow-empty", "-qm", "fixture"))) {
            if (!processes.run(command, root, Duration.ofSeconds(15)).ok()) throw new IllegalStateException("git fixture failed");
        }
        Path run = root.resolve(".warden/runs/run-1"); Files.createDirectories(run);
        Path prompt = run.resolve("prompt.md"); Files.writeString(prompt, "Review only; do not edit.");
        List<List<String>> calls = new ArrayList<>();
        OrcaClient client = new OrcaClient((directory, timeout, args) -> {
            calls.add(args);
            String op = String.join(" ", args.subList(0, Math.min(2, args.size())));
            if (args.get(0).equals("status")) return rpc(Map.of("app", Map.of("running", true), "runtime",
                    Map.of("reachable", true, "capabilities", capability
                            ? List.of("orchestration.contract.v1", "orchestration.worker-launch-preferences.v1") : List.of("orchestration.contract.v1"))));
            return switch (op) {
                case "worktree current" -> rpc(Map.of("worktree", Map.of("id", "repo::" + root)));
                case "orchestration worker-list" -> rpc(Map.of("workers", List.of()));
                case "terminal create", "terminal show" -> rpc(Map.of("terminal", Map.of("handle", "coordinator")));
                case "orchestration run-create", "orchestration run-use" -> rpc(Map.of("run", Map.of("id", "orca-run")));
                case "orchestration task-create" -> rpc(Map.of("task", Map.of("id", "task")));
                case "orchestration worker-start" -> rpc(Map.of("ready", true, "dispatchId", "dispatch", "taskId", "task", "launch", launch(effort)));
                case "orchestration worker-show" -> rpc(Map.of("dispatch", Map.of("id", "dispatch", "task_id", "task", "status", "dispatched"),
                        "worker", Map.of("agent_terminal_handle", "worker", "startOptions", Map.of("launch", launch(effort)))));
                case "orchestration worker-stop" -> {
                    if (uncertainStop) throw new java.io.IOException("stop unconfirmed");
                    yield rpc(Map.of("stopped", true));
                }
                case "orchestration check" -> {
                    if (args.contains("--ack")) yield rpc(Map.of());
                    if (writes) Files.writeString(root.resolve("unauthorized.txt"), "changed");
                    yield rpc(Map.of("delivery", Map.of("id", "delivery", "messages", List.of(Map.of(
                            "type", "worker_done", "taskId", "task", "dispatchId", "dispatch", "outcome", "succeeded",
                            "payload", Map.of("warden_artifact", Map.of("role", "reviewer", "task_id", "task",
                                    "status", "completed", "verdict", "pass", "summary", "reviewed", "findings", List.of())))))));
                }
                case "orchestration worker-release" -> rpc(Map.of("released", true, "releaseState", "released"));
                case "orchestration worker-read", "terminal rename", "terminal close" -> rpc(Map.of());
                default -> throw new IllegalStateException("unexpected call " + args);
            };
        });
        var task = new TaskSpec.ResolvedTask("task", "review", List.of(), "low", "HEAD", List.of(), List.of(), List.of(), null, null, null, 0, 1);
        var request = new RoleExecutor.Request("run-1", "run-1", "reviewer", profile(), task,
                "HEAD", root, run, prompt, null, "", "reviewer", List.of());
        var result = new OrcaExecutor(client, new GitRepository(root, processes)).execute(request);
        check.eq("executor " + expected, expected, result.code());
        if (!capability) check.eq("unsupported runtime makes no mutation", 1, calls.size());
        else {
            List<String> start = calls.stream().filter(a -> a.contains("worker-start")).findFirst().orElseThrow();
            check.eq("actual dispatch carries max", "max", start.get(start.indexOf("--effort") + 1));
            var saved = new OrcaLifecycle(root, "run-1").read().workers().values().iterator().next();
            check.eq("restart keeps the requested effort", "max", saved.launchContract().get("effort"));
            if (uncertainStop) check.that("failed fencing retains coordinator", calls.stream().noneMatch(a -> a.contains("close")));
            if (expected.equals("ok")) {
                check.eq("receipt durably stored", "matched", saved.launchEvidence().get("status"));
                check.eq("worker released after success", false, saved.active());
                // Simulate a controller restart with changed effort against the still-owned dispatch.
                new OrcaLifecycle(root, "run-1").mutate(OrcaLifecycle.of(s -> s.withWorker(
                        saved.at(OrcaLifecycle.State.ACTIVE, null, java.time.Instant.now()))));
                calls.clear();
                Profile changed = Profile.parse(PROFILE.replace("effort: max", "effort: high"), "test");
                var resumed = new OrcaExecutor(client, new GitRepository(root, processes)).execute(new RoleExecutor.Request(
                        "run-1", "run-1", "reviewer", changed, task, "HEAD", root, run, prompt, null, "", "reviewer", List.of()));
                check.eq("changed effort cannot relabel a resumed worker", "role_orca_launch_contract_changed", resumed.code());
                check.that("changed contract launches no duplicate", calls.stream().noneMatch(a -> a.contains("worker-start")));
            }
        }
    }
}
