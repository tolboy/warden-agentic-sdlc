package dev.warden;

import dev.warden.execution.orca.OrcaClient;
import dev.warden.execution.orca.OrcaDesktop;
import dev.warden.json.Json;
import dev.warden.run.Workspace;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Safety of the partial automatic projection; these are not desktop visual acceptance. */
public final class OrcaDesktopTest implements Suite {
    @Override public String name() { return "orca-desktop"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-desktop-").toRealPath();
        var interactive = new OrcaDesktop.Policy(true, false, false);
        Fake ready = new Fake(root);
        List<String> warnings = new ArrayList<>();
        var board = OrcaDesktop.attach(ready.client(), root, interactive, warnings::add);
        check.that("attach does not contaminate run reservation", !Files.exists(root.resolve(".warden")));
        board.watch(root.resolve("ignored"), "first");
        board.note("implement · grok-implement · grok-4.7 high");
        board.state(Workspace.State.RUNNING);
        board.working("implement", 1000);
        board.state(Workspace.State.WAITING_FOR_HUMAN);
        long count = ready.calls.size();
        board.working("late heartbeat", 2000);
        check.eq("late heartbeat cannot restore a running card", count, (long) ready.calls.size());
        Map<String, Object> receipt = receipt(root, "first");
        check.eq("a card is distinguished from an overview", "workspace_card", receipt.get("projection"));
        check.eq("overview is explicitly unavailable", "unavailable", receipt.get("overview"));
        check.eq("ownership gap is durable", "observer_ownership_unsupported", receipt.get("reason"));
        check.eq("no invented resource ownership", List.of(), receipt.get("resources"));
        check.eq("the receipt is scoped to its run", "warden:first", receipt.get("owner"));
        check.eq("requested state does not claim confirmed delivery", "waiting_for_human", receipt.get("last_requested_state"));
        check.eq("one operator explanation for the limitation", 1, warnings.size());
        check.that("all RPC budgets are bounded", ready.timeouts.stream().allMatch(t -> t.compareTo(Duration.ofSeconds(3)) <= 0));
        board.watch(root.resolve("ignored"), "second");
        check.that("a heartbeat/watch repeat cannot create another resource", !Files.exists(root.resolve(".warden/runs/second")));
        check.that("direct activity is never turned into a worker or fake settlement", ready.calls.stream().noneMatch(
                args -> args.contains("orchestration") || args.contains("terminal") || args.contains("agent")));

        Fake wrongPath = new Fake(Files.createTempDirectory("warden-other-root-").toRealPath());
        var wrong = OrcaDesktop.attach(wrongPath.client(), root, interactive, text -> {});
        wrong.watch(root, "wrong-path");
        wrong.note("must not touch another card");
        check.eq("an unrelated current workspace is refused", "execution_workspace_unregistered", receipt(root, "wrong-path").get("reason"));
        check.that("unmanaged execution does not create a checkout or alter registration", wrongPath.calls.stream().noneMatch(a -> a.contains("create") || a.contains("set") || a.contains("add")));

        Fake cold = new Fake(root);
        cold.running = false;
        var started = OrcaDesktop.attach(cold.client(), root, interactive, text -> {});
        started.watch(root, "cold");
        started.note("one start only");
        check.eq("closed desktop gets one start", 1L, cold.calls.stream().filter(a -> a.equals(List.of("open"))).count());
        check.eq("readiness is checked after that start", 2L, cold.calls.stream().filter(a -> a.equals(List.of("status"))).count());
        check.eq("startup attempt is saved", true, receipt(root, "cold").get("startup_attempted"));

        Fake refused = new Fake(root);
        refused.running = false;
        refused.failOpen = true;
        var unavailable = OrcaDesktop.attach(refused.client(), root, interactive, text -> {});
        unavailable.watch(root, "cold-failed");
        unavailable.note("no retry");
        unavailable.state(Workspace.State.RUNNING);
        check.eq("a startup timeout is a CLI fallback", "desktop_start_failed", receipt(root, "cold-failed").get("reason"));
        check.eq("failed startup is not retried", 2, refused.calls.size());

        Fake lost = new Fake(root);
        var disconnected = OrcaDesktop.attach(lost.client(), root, interactive, warnings::add);
        disconnected.watch(root, "lost");
        lost.failProjection = true;
        disconnected.note("attempt one");
        int afterLoss = lost.calls.size();
        disconnected.state(Workspace.State.WAITING_FOR_HUMAN);
        disconnected.note("final status");
        disconnected.working("attempt", 3000);
        check.eq("connection loss opens the circuit", afterLoss, lost.calls.size());
        check.eq("projection failure survives reconnect/restart inspection", "projection_failed", receipt(root, "lost").get("reason"));
        check.that("operator sees projection loss after initial limitation", warnings.stream().anyMatch(s -> s.contains("projection_failed")));
        check.that("no diagnostic echoes auth-bearing CLI error text", !Files.readString(root.resolve(".warden/runs/lost/desktop.json")).contains("SECRET"));

        for (var entry : Map.of("quiet", new OrcaDesktop.Policy(true, true, false),
                "headless", new OrcaDesktop.Policy(true, false, true),
                "noninteractive", new OrcaDesktop.Policy(false, false, false)).entrySet()) {
            Fake fake = new Fake(root);
            List<String> silent = new ArrayList<>();
            var skipped = OrcaDesktop.attach(fake.client(), root, entry.getValue(), silent::add);
            skipped.watch(root, entry.getKey());
            skipped.note("no desktop");
            skipped.state(Workspace.State.RUNNING);
            check.eq(entry.getKey() + " never probes or launches desktop", 0, fake.calls.size());
            check.eq(entry.getKey() + " adds no status lines", 0, silent.size());
            check.eq(entry.getKey() + " has an inspectable skip reason", entry.getKey(), receipt(root, entry.getKey()).get("reason"));
        }
        for (String name : List.of("CI", "GITHUB_ACTIONS", "GITLAB_CI", "TF_BUILD", "BUILDKITE", "SSH_CONNECTION", "SSH_TTY")) {
            check.that(name + " suppresses desktop startup", OrcaDesktop.Policy.detect(true, false, Map.of(name, "true")).headless());
        }
        check.that("false CI markers are not mistaken for CI", !OrcaDesktop.Policy.detect(true, false, Map.of("CI", "false")).headless());
        check.that("dumb terminals are headless", OrcaDesktop.Policy.detect(true, false, Map.of("TERM", "dumb")).headless());

        var absent = OrcaDesktop.attach(new OrcaClient((cwd, timeout, args) -> {
            throw new java.io.IOException("SECRET auth helper output");
        }), root, interactive, text -> {});
        absent.watch(root, "missing");
        check.eq("missing Orca is ordinary CLI fallback", "desktop_probe_failed", receipt(root, "missing").get("reason"));
        check.that("exception text is not published", !Files.readString(root.resolve(".warden/runs/missing/desktop.json")).contains("SECRET"));

        var invalid = OrcaDesktop.attach(new Fake(root).client(), root, interactive, text -> {});
        invalid.watch(root, "../escaped");
        check.that("invalid run identity cannot escape evidence", !Files.exists(root.resolve(".warden/escaped")));
        // No close path exists: original, transferred and user-taken-over terminals are all
        // preserved by construction. This is containment, not proof of supported cleanup.
        check.that("no automatic terminal close, including after disconnect", List.of(ready, wrongPath, cold, refused, lost).stream()
                .flatMap(f -> f.calls.stream()).noneMatch(a -> a.contains("close")));
    }

    private static Map<String, Object> receipt(Path root, String id) throws Exception {
        return Json.parseObject(Files.readString(root.resolve(".warden/runs/" + id + "/desktop.json")));
    }

    private static final class Fake {
        final Path root;
        final List<List<String>> calls = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        boolean running = true;
        boolean failOpen;
        boolean failProjection;
        Fake(Path root) { this.root = root; }
        OrcaClient client() { return new OrcaClient((cwd, timeout, args) -> {
            calls.add(args);
            timeouts.add(timeout);
            Map<String, Object> result;
            if (args.equals(List.of("status"))) {
                result = Map.of("app", Map.of("running", running), "runtime", Map.of(
                        "reachable", running, "state", running ? "ready" : "stopped", "runtimeId", "test-runtime", "appVersion", "1.4.233"));
            } else if (args.equals(List.of("open"))) {
                if (failOpen) return new OrcaClient.Rpc(false, -1, true, 3000, Map.of(), Map.of(), "", "SECRET");
                running = true;
                result = Map.of();
            } else if (args.equals(List.of("worktree", "current"))) {
                result = Map.of("worktree", Map.of("id", "repo::" + root, "path", root.toString()));
            } else {
                if (failProjection) throw new java.io.IOException("SECRET provider authentication output");
                result = Map.of();
            }
            return new OrcaClient.Rpc(true, 0, false, 1, Map.of("ok", true, "result", result), result, "", "");
        }); }
    }
}
