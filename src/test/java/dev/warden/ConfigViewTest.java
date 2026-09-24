package dev.warden;

import dev.warden.config.Policy;
import dev.warden.config.RosterCommand;
import dev.warden.config.UserConfig;
import dev.warden.dashboard.ConfigView;
import dev.warden.dashboard.Dashboard;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;
import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The panel's view of the settings a run would start with (phase 3a, read-only).
 *
 * The acceptance is that it tells the operator what `warden roster` and a dry run would: the
 * same candidates in the same order, the same profile chosen for each stage, and the same
 * reason for every candidate that is out — the resolver's, never one of the panel's own.
 */
public final class ConfigViewTest implements Suite {

    @Override public String name() { return "config-view"; }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-config-view-");
        try {
            Path home = home(sandbox.resolve("home"));
            Path project = project(sandbox.resolve("project"));
            UserConfig user = UserConfig.load(home);
            Map<String, Object> view = ConfigView.of(project, user, ConfigViewTest::available);

            sameAsTheResolver(check, project, home, user, view);
            valuesSayWhereTheyComeFrom(check, view);
            orcaRolesSayWhatIsUnknown(check, view);
            attemptsAndFailovers(check);
            servedOnDemand(check, project);
            aTaskResolvesItsOwnDispatch(check, sandbox);
        } finally {
            deleteTree(sandbox);
        }
    }

    @SuppressWarnings("unchecked")
    private void sameAsTheResolver(Check check, Path project, Path home, UserConfig user,
                                   Map<String, Object> view) throws Exception {
        Map<String, Object> review = stage(view, "review");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) review.get("candidates");

        RosterCommand.Outcome roster = new RosterCommand().run(home, new String[] {"roster"});
        List<String> rostered = new ArrayList<>();
        for (Object role : (List<?>) roster.report().get("roles")) {
            if (!"reviewer".equals(((Map<?, ?>) role).get("role"))) continue;
            for (Object profile : (List<?>) ((Map<?, ?>) role).get("profiles")) {
                rostered.add(String.valueOf(((Map<?, ?>) profile).get("profile")));
            }
        }
        check.eq("the panel lists the reviewers `warden roster` lists, in its order", rostered,
                candidates.stream().map(row -> String.valueOf(row.get("profile"))).toList());

        check.eq("a reader is judged against the writer the chain would dispatch", "impl-a",
                ((Map<?, ?>) review.get("judged_against")).get("profile"));
        Map<String, String> reasons = new LinkedHashMap<>();
        for (Map<String, Object> row : candidates) {
            reasons.put(String.valueOf(row.get("profile")), (String) row.get("rejected_after_writer"));
        }
        check.eq("the resolver's reason for each reviewer, and none for the ones it admits",
                java.util.Arrays.asList("same_vendor_as_writer", "profile_unverified", "executable_not_found",
                        null, null),
                new ArrayList<>(reasons.values()));
        check.eq("the same vendor alone is admitted: the refusal is about the writer", Boolean.TRUE,
                candidates.get(0).get("eligible"));

        RoleRunner roles = new RoleRunner(new ProcessRunner());
        Policy policy = user.policy();
        var workflow = policy.workflow();
        var reviewStage = workflow.stages().stream().filter(s -> s.name().equals("review")).findFirst().orElseThrow();
        RoleResolver.Writers writer = new RoleResolver.Writers(java.util.Set.of("vendor-a"),
                java.util.Set.of("impl-a"), true, false);
        var peeked = roles.peek(project, user, "review", "reviewer", writer, workflow.rotationPositionOf(reviewStage));
        check.eq("and the profile a dry run would dispatch is the one the panel marks", peeked.name(),
                review.get("would_run"));
        var implement = workflow.stages().stream().filter(s -> s.name().equals("implement")).findFirst().orElseThrow();
        check.eq("for the writer too", roles.peek(project, user, "implement", "implementer",
                RoleResolver.Writers.NONE, workflow.rotationPositionOf(implement)).name(),
                stage(view, "implement").get("would_run"));

        // A stage nobody can fill: the preflight's reasons, word for word.
        Path thin = home(home.resolveSibling("thin-home"));
        String policyText = Files.readString(thin.resolve("policy.yaml"))
                .replace("[rev-same, rev-unverified, rev-missing, rev-ok, rev-orca]",
                        "[rev-same, rev-unverified, rev-missing]");
        Files.writeString(thin.resolve("policy.yaml"), policyText);
        UserConfig thinUser = UserConfig.load(thin);
        Map<String, Object> thinView = ConfigView.of(project, thinUser, ConfigViewTest::available);
        Map<String, Object> blocked = stage(thinView, "review");
        check.eq("a stage nobody can fill says so", "no_eligible_profile", blocked.get("unresolved"));
        Map<String, String> preflight = roles.explainFill(thinUser, "review", "reviewer", writer,
                thinUser.policy().workflow().rotationPositionOf(reviewStage));
        Map<String, String> shown = new LinkedHashMap<>();
        for (Object row : (List<?>) blocked.get("candidates")) {
            shown.put(String.valueOf(((Map<?, ?>) row).get("profile")),
                    String.valueOf(((Map<?, ?>) row).get("rejected_after_writer")));
        }
        check.eq("with the reasons the preflight gives", preflight, shown);
        check.eq("a mechanical stage is shown as one", "gates", stage(view, "gates").get("kind"));
    }

    @SuppressWarnings("unchecked")
    private void valuesSayWhereTheyComeFrom(Check check, Map<String, Object> view) {
        Map<String, Map<String, Object>> limits = new LinkedHashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) view.get("limits")) {
            limits.put(String.valueOf(row.get("key")), row);
        }
        check.eq("a policy value the operator wrote is theirs", "personal",
                limits.get("failover.on_quota_exhausted").get("source"));
        check.eq("with its value", "stop", limits.get("failover.on_quota_exhausted").get("value"));
        check.eq("one nobody wrote is Warden's default", "default", limits.get("review.contract_gaps").get("source"));
        check.eq("and says which default", "gate", limits.get("review.contract_gaps").get("value"));

        Map<String, Object> task = ((List<Map<String, Object>>) view.get("tasks")).getFirst();
        check.eq("the task is listed", "hello", task.get("task"));
        check.eq("a budget the task sets is the task's", Map.of("value", 7L, "source", "task"),
                task.get("max_role_runs"));
        check.eq("one it leaves out is the default", Map.of("value", 20.0, "source", "default"),
                task.get("max_cost_usd"));
        check.eq("a strict cap is shown as strict", Map.of("value", "strict", "source", "task"),
                task.get("cost_cap"));
        check.eq("a project default is the project's", Map.of("value", 3L, "source", "project"),
                task.get("max_fix_attempts"));
        List<Map<String, Object>> overlay = (List<Map<String, Object>>) task.get("overlay");
        Map<String, String> refused = new LinkedHashMap<>();
        for (Map<String, Object> row : overlay) refused.put(String.valueOf(row.get("stage")), (String) row.get("refused"));
        Map<String, String> expected = new java.util.HashMap<>();
        // The reviewer this task gets, rev-ok, has no {{effort}} in its args, so the preflight
        // refuses `effort: high` for it; the panel used to check only that the stage existed.
        expected.put("review", "run_override_undeliverable");
        expected.put("browser", "run_override_not_a_role_stage");
        expected.put("nope", "run_override_unknown_stage");
        check.eq("a task overlay is shown with what the preflight will say about it", expected, refused);
        check.eq("the stage table says it is the policy's answer, before any task", "policy",
                view.get("stages_basis"));
    }

    @SuppressWarnings("unchecked")
    private void orcaRolesSayWhatIsUnknown(Check check, Map<String, Object> view) {
        Map<String, Object> orca = ((List<Map<String, Object>>) stage(view, "review").get("candidates")).getLast();
        check.eq("an Orca role's cost is unknown, not zero", false, orca.get("cost_reported"));
        check.eq("and a model it does not set is the agent's default", "agent_default", orca.get("model_note"));
    }

    private void attemptsAndFailovers(Check check) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("workflow_run_id", "run-1");
        view.put("stage", "review");
        view.put("runner", "orca");
        view.put("model", null);
        view.put("view_state", "failed");
        view.put("agent_blocked_on", "folder_trust");
        view.put("resolution", "Answer Yes once in an Orca tab in this worktree");
        view.put("agent_screen_tail", "secret screen");
        Map<String, Object> attempt = Dashboard.publicAttempt(view);
        check.eq("an Orca attempt with no price says its cost is unknown", false, attempt.get("cost_known"));
        check.eq("and that the model was the agent's own", "agent_default", attempt.get("model_note"));
        check.eq("a worker that never started says what it waited on", "folder_trust", attempt.get("agent_blocked_on"));
        check.eq("and Warden's sentence for the one click that clears it",
                "Answer Yes once in an Orca tab in this worktree", attempt.get("resolution"));
        check.that("while the agent's own screen stays out", !attempt.containsKey("agent_screen_tail"));

        List<Map<String, Object>> steps = List.of(
                Map.of("stage", "review", "profile", "codex-review", "vendor", "openai",
                        "failed_over_from", List.of("claude-review")),
                Map.of("stage", "look", "code", "role_quota_exhausted",
                        "exhausted_profiles", List.of("claude-visual")),
                Map.of("stage", "review-second", "failover_pending", Map.of("from_profile", "grok-review",
                        "to_profile", "codex-review", "to_vendor", "openai")));
        List<Map<String, Object>> failovers = Dashboard.failovers(steps);
        check.eq("a switch, a spent subscription and a switch awaiting a person are all shown",
                List.of("switched", "exhausted", "awaiting_confirmation"),
                failovers.stream().map(row -> row.get("kind")).toList());
        check.eq("naming who was spent", List.of("claude-review"), failovers.get(0).get("from_profiles"));
        check.eq("and who took over", "codex-review", failovers.get(0).get("profile"));
    }

    private void servedOnDemand(Check check, Path project) throws Exception {
        try (var dashboard = new Dashboard(project, 0); var client = HttpClient.newHttpClient()) {
            dashboard.start();
            HttpResponse<String> config = client.send(HttpRequest.newBuilder(URI.create(dashboard.url() + "api/config"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            check.eq("the settings are served on their own route", 200, config.statusCode());
            check.contains("as the stage view", config.body(), "\"stages\"");
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(dashboard.url())).build(),
                    HttpResponse.BodyHandlers.ofString());
            check.contains("and the page has a place for them", page.body(), "Состав по стадиям");
        }
    }

    /**
     * The stage table answers for the policy. A task that pins a writer in `use:`, or allows
     * a same-vendor peer in `review_assurance`, runs something else, and the panel showed the
     * policy's pick as what would run and `peer_not_allowed_by_task` for a reader the task
     * admits. Each task now carries its own dispatch, from the preflight's calls.
     */
    @SuppressWarnings("unchecked")
    private void aTaskResolvesItsOwnDispatch(Check check, Path sandbox) throws Exception {
        Path home = Files.createDirectories(sandbox.resolve("peer-home/profiles")).getParent();
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [impl-a, impl-b], strategy: first, require_independent_vendor: false }
                  reviewer:
                    profiles: [rev-same, rev-ok]
                    strategy: first
                    require_independent_vendor: false
                    same_vendor_peer: { implementer: impl-a, reviewer: rev-same }
                review: { required_for_risk: [medium, high] }
                """);
        String launcher = yaml(Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString());
        profile(home, "impl-a", "implementer", "vendor-a", launcher, false, true, "model: writer-model\n");
        profile(home, "impl-b", "implementer", "vendor-b", launcher, false, true, "model: other-writer\n");
        profile(home, "rev-same", "reviewer", "vendor-a", launcher, true, true, "model: reader-model\n");
        profile(home, "rev-ok", "reviewer", "vendor-d", launcher, true, true, "model: outside-model\n");
        Path project = project(sandbox.resolve("peer-project"));
        Files.delete(project.resolve(".warden/tasks/hello.yaml"));
        String task = """
                version: 1
                id: %s
                goal: Say hello
                risk: medium
                scope: app
                """;
        Files.writeString(project.resolve(".warden/tasks/plain.yaml"), task.formatted("plain"));
        Files.writeString(project.resolve(".warden/tasks/peer.yaml"), task.formatted("peer")
                + "review_assurance: same_vendor_peer\n");
        Files.writeString(project.resolve(".warden/tasks/pinned.yaml"), task.formatted("pinned")
                + "use:\n  implement: { profile: impl-b }\n");
        Files.writeString(project.resolve(".warden/tasks/missing.yaml"), task.formatted("missing")
                + "use:\n  implement: { profile: no-such }\n");
        UserConfig user = UserConfig.load(home);
        Map<String, Object> view = ConfigView.of(project, user, ConfigViewTest::available);

        check.eq("the policy's table picks the outside reviewer: no task has allowed the peer", "rev-ok",
                stage(view, "review").get("would_run"));
        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) view.get("tasks")) {
            tasks.put(String.valueOf(row.get("task")), row);
        }
        check.eq("a task with no settings of its own runs what the policy names", "rev-ok",
                dispatched(tasks.get("plain"), "review").get("profile"));

        Map<String, Object> peer = dispatched(tasks.get("peer"), "review");
        check.eq("a task that allows the peer runs it", "rev-same", peer.get("profile"));
        var workflow = user.policy().workflow();
        var reviewStage = workflow.stages().stream().filter(s -> s.name().equals("review")).findFirst().orElseThrow();
        var peeked = new RoleRunner(new ProcessRunner()).peek(project, user, "review", "reviewer",
                new RoleResolver.Writers(java.util.Set.of("vendor-a"), java.util.Set.of("impl-a"), true, true),
                workflow.rotationPositionOf(reviewStage));
        check.eq("which is what the resolver gives the loop for it", peeked == null ? null : peeked.name(),
                peer.get("profile"));

        check.eq("a task that pins another writer shows that writer", "impl-b",
                dispatched(tasks.get("pinned"), "implement").get("profile"));
        check.eq("while the policy's table still names its own", "impl-a", stage(view, "implement").get("would_run"));
        check.eq("and its readers are judged against the pinned writer", "rev-same",
                dispatched(tasks.get("pinned"), "review").get("profile"));

        Map<String, Object> missing = dispatched(tasks.get("missing"), "implement");
        check.eq("a pin nobody can fill leaves the stage empty", null, missing.get("profile"));
        check.eq("with the resolver's reason", Map.of("no-such", "profile_not_found"), missing.get("unresolved"));
        Map<String, Object> pinRow = ((List<Map<String, Object>>) tasks.get("missing").get("overlay")).getFirst();
        check.eq("and the overlay row says the run will be refused there", "profile_not_found", pinRow.get("refused"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dispatched(Map<String, Object> task, String stage) {
        for (Map<String, Object> row : (List<Map<String, Object>>) task.get("would_run")) {
            if (stage.equals(row.get("stage"))) return row;
        }
        throw new IllegalStateException("no dispatch row for " + stage + " in " + task.get("task"));
    }

    // ------------------------------------------------------------------ fixture

    /** Absolute commands only, so availability is a file check here and in the resolver alike. */
    private static boolean available(dev.warden.config.Profile profile) {
        if ("orca".equals(profile.runner())) return true;
        return Files.isExecutable(Path.of(profile.command()));
    }

    private static Path home(Path home) throws Exception {
        Files.createDirectories(home.resolve("profiles"));
        Files.writeString(home.resolve("policy.yaml"), """
                version: 1
                roles:
                  implementer: { profiles: [impl-a], strategy: first, require_independent_vendor: false }
                  reviewer: { profiles: [rev-same, rev-unverified, rev-missing, rev-ok, rev-orca], strategy: first, require_independent_vendor: true }
                review: { required_for_risk: [medium, high] }
                failover: { on_quota_exhausted: stop }
                """);
        String java = yaml(Path.of(System.getProperty("java.home"), "bin", WINDOWS ? "java.exe" : "java").toString());
        String missing = yaml(Path.of(System.getProperty("java.home"), "bin", "no-such-vendor-cli").toString());
        profile(home, "impl-a", "implementer", "vendor-a", java, false, true, "");
        profile(home, "rev-same", "reviewer", "vendor-a", java, true, true, "");
        profile(home, "rev-unverified", "reviewer", "vendor-b", java, true, false, "");
        profile(home, "rev-missing", "reviewer", "vendor-c", missing, true, true, "");
        profile(home, "rev-ok", "reviewer", "vendor-d", java, true, true, "");
        profile(home, "rev-orca", "reviewer", "vendor-e", "grok", true, true,
                "runner: orca\nprompt_delivery: workspace_file\n");
        return home;
    }

    private static void profile(Path home, String name, String role, String vendor, String command,
                                boolean readOnly, boolean verified, String extra) throws Exception {
        Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                version: 1
                profile: %s
                role: %s
                vendor: %s
                command: %s
                read_only: %s
                %s""".formatted(name, role, vendor, command, readOnly, extra)
                + (verified ? "verification:\n  verified_on: \"2026-09-23\"\n" : ""));
    }

    private static Path project(Path project) throws Exception {
        Files.createDirectories(project.resolve(".warden/tasks"));
        Files.writeString(project.resolve(".warden/project.yaml"), """
                version: 1
                project: panel
                checks: { fast: ["git diff --check"] }
                scopes: { app: ["src"] }
                defaults: { checks: fast, risk: medium, max_fix_attempts: 3 }
                """);
        Files.writeString(project.resolve(".warden/tasks/hello.yaml"), """
                version: 1
                id: hello
                goal: Say hello
                risk: medium
                scope: app
                budgets: { max_role_runs: 7, cost_cap: strict }
                use:
                  review: { effort: high }
                  browser: { host: orca }
                  nope: { effort: low }
                """);
        return project;
    }

    private static String yaml(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Map<String, Object> stage(Map<String, Object> view, String name) {
        for (Object row : (List<?>) view.get("stages")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> stage = (Map<String, Object>) row;
            if (name.equals(stage.get("stage"))) return stage;
        }
        throw new IllegalStateException("no stage " + name);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            }
        }
    }
}
