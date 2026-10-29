package dev.warden;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Connection;
import dev.warden.config.ProfileVerifier;
import dev.warden.config.UserConfig;
import dev.warden.config.UserSetup;
import dev.warden.execution.RoleExecutor;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Explicit API substitutions exercise the real resolver, dispatch budget and durable receipts. */
public final class ApiFallbackTest implements Suite {
    @Override public String name() { return "api-fallback"; }

    @Override public void run(Check check) throws Exception {
        try (Fixture fixture = new Fixture()) {
            absentPolicyStops(check, fixture);
            orderedAttemptsAndAccounting(check, fixture);
            failureAllowlist(check, fixture);
            boundsAndBudget(check, fixture);
            eligibilityStillApplies(check, fixture);
            confirmedContinuation(check, fixture);
            mixedFallbackCannotEscape(check, fixture);
        }
    }

    private void absentPolicyStops(Check check, Fixture f) throws Exception {
        f.policy(null, "auto", 2);
        Calls noList = new Calls(Map.of("primary", "role_api_provider_error"));
        var stopped = f.run("no-list", noList, new Gate(5));
        check.eq("global auto alone does not authorize an API substitution",
                "role_api_provider_error", stopped.code());
        check.eq("an API role without a list sends only the selected call",
                List.of("primary"), noList.profiles);

        f.policy(List.of("backup-one", "backup-two"), "stop", 2);
        Calls stop = new Calls(Map.of("primary", "role_api_timeout"));
        var byPolicy = f.run("stop-policy", stop, new Gate(5));
        check.eq("the explicit stop mode keeps the endpoint failure", "role_api_timeout", byPolicy.code());
        check.eq("a fallback list grants no permission past stop mode", List.of("primary"), stop.profiles);
    }

    private void orderedAttemptsAndAccounting(Check check, Fixture f) throws Exception {
        f.policy(List.of("backup-one", "backup-two"), "auto", 2);
        Map<String, Object> relay = Map.of("upstream_attempts", List.of(
                Map.of("delivery", "unknown", "failure_phase", "headers")));
        Calls calls = new Calls(Map.of("primary", "role_api_network_error",
                "backup-one", "role_api_provider_error"), Map.of("relay", relay));
        Gate gate = new Gate(3);
        var result = f.run("ordered", calls, gate);
        check.that("the final declared API successor completes the role", result.ok());
        check.eq("fallback order overrides the reversed rotating roster",
                List.of("primary", "backup-one", "backup-two"), calls.profiles);
        check.eq("every fallback is independently admitted and settled", 3, gate.admitted);
        check.eq("failed attempts settle before the next admission", 3, gate.settled);
        check.eq("failed and successful API calls all contribute reported cost", 0.375,
                result.details().get("attempts_cost_usd"));
        List<?> attempts = (List<?>) result.details().get("vendor_attempts");
        check.eq("all three attempts survive in the final receipt", 3, attempts.size());
        check.eq("the first attempt preserves its API failure", "role_api_network_error",
                ((Map<?, ?>) attempts.get(0)).get("code"));
        check.eq("a failed coding relay's structured evidence survives fallback", relay,
                ((Map<?, ?>) attempts.get(0)).get("relay"));
        check.eq("both substitutions survive in the audit trail", 2,
                ((List<?>) result.details().get("role_failovers")).size());
        check.that("a failed endpoint is not marked as spent quota", result.rejected().values().stream()
                .noneMatch("quota_exhausted_this_run"::equals));
    }

    private void failureAllowlist(Check check, Fixture f) throws Exception {
        f.policy(List.of("backup-one", "backup-two"), "auto", 2);
        int index = 0;
        for (String code : List.of("role_api_network_error", "role_api_timeout",
                "role_api_rate_limited", "role_api_provider_error")) {
            Calls calls = new Calls(Map.of("primary", code));
            var result = f.run("allowed-" + index++, calls, new Gate(3));
            check.that("a declared fallback handles " + code, result.ok());
            check.eq("exactly one successor is used for " + code,
                    List.of("primary", "backup-one"), calls.profiles);
        }
        for (String code : List.of("role_api_auth_invalid", "role_api_model_mismatch",
                "role_api_profile_changed", "role_api_output_truncated", "role_api_protocol_error",
                "role_api_interrupted", "role_api_model_identity_unknown", "role_api_connection_disabled",
                "role_api_artifact_failure", "role_api_response_unreadable", "role_api_request_invalid",
                "role_artifact_unparseable", "role_artifact_incomplete",
                "role_artifact_schema_violation", "role_harness_process_failed")) {
            Calls calls = new Calls(Map.of("primary", code));
            var result = f.run("forbidden-" + index++, calls, new Gate(3));
            check.eq("unsafe API failure is retained: " + code, code, result.code());
            check.eq("unsafe API failure dispatches no replacement: " + code,
                    List.of("primary"), calls.profiles);
        }
        Calls backoffCancel = new Calls(Map.of("primary", "role_api_network_error"),
                Map.of("api_retry_stopped", "cancelled"));
        var cancelled = f.run("backoff-cancel", backoffCancel, new Gate(3));
        check.that("cancelled API backoff stops even when the underlying failure was transient", !cancelled.ok());
        check.eq("cancellation cannot dispatch a different endpoint", List.of("primary"), backoffCancel.profiles);
    }

    private void boundsAndBudget(Check check, Fixture f) throws Exception {
        f.policy(List.of("backup-one", "backup-two"), "auto", 1);
        Calls bounded = new Calls(Map.of("primary", "role_api_provider_error",
                "backup-one", "role_api_timeout"));
        var stopped = f.run("bound", bounded, new Gate(5));
        check.eq("the stage stops with its last endpoint failure at the switch bound",
                "role_api_timeout", stopped.code());
        check.eq("one substitution means at most two API dispatches",
                List.of("primary", "backup-one"), bounded.profiles);
        check.eq("the receipt names the successor denied by the stage bound", "backup-two",
                stopped.details().get("failover_bound"));

        f.policy(List.of("backup-one", "backup-two"), "auto", 2);
        Calls budgeted = new Calls(Map.of("primary", "role_api_provider_error"));
        Gate gate = new Gate(1);
        var noRoom = f.run("budget", budgeted, gate);
        check.eq("exhausted dispatch budget preserves the endpoint diagnosis",
                "role_api_provider_error", noRoom.code());
        check.eq("an available successor cannot bypass the dispatch budget",
                List.of("primary"), budgeted.profiles);
        check.eq("budget refusal still settles the completed API call", 1, gate.settled);
        check.eq("budget refusal names the unspent successor", "backup-one",
                noRoom.details().get("failover_declined_by_budget"));
    }

    private void eligibilityStillApplies(Check check, Fixture f) throws Exception {
        f.profile("backup-one", "writer", true);
        f.profile("backup-two", "backupvendor", false);
        f.profile("backup-three", "independentvendor", true);
        f.policy(List.of("backup-one", "backup-two", "backup-three"), "auto", 3);
        Calls calls = new Calls(Map.of("primary", "role_api_provider_error"));
        var result = f.run("eligible", calls, new Gate(4));
        check.that("the first eligible declared successor completes the reading", result.ok());
        check.eq("same-writer and unverified fallback profiles are never dispatched",
                List.of("primary", "backup-three"), calls.profiles);

        f.profile("backup-three", "writer", true);
        Calls none = new Calls(Map.of("primary", "role_api_provider_error"));
        var unavailable = f.run("no-eligible", none, new Gate(4));
        check.eq("no eligible successor preserves the provider failure", "role_api_provider_error",
                unavailable.code());
        check.eq("exhausting the explicit list cannot escape to an unrelated profile",
                List.of("primary"), none.profiles);
        check.that("rejected fallback eligibility is explained", unavailable.details().get("failover_rejected")
                instanceof Map<?, ?> rejected && rejected.containsKey("backup-one")
                && rejected.containsKey("backup-two") && rejected.containsKey("backup-three"));
        f.profile("backup-one", "vendorone", true);
        f.profile("backup-two", "vendortwo", true);
    }

    @SuppressWarnings("unchecked")
    private void confirmedContinuation(Check check, Fixture f) throws Exception {
        f.policy(List.of("backup-one", "backup-two"), "confirm", 1);
        Calls first = new Calls(Map.of("primary", "role_api_provider_error"));
        var pending = f.run("confirm-before", first, new Gate(3));
        check.eq("confirmation stops before the successor is called",
                "role_failover_requires_confirmation", pending.code());
        check.eq("confirmation has spent only the selected API call", List.of("primary"), first.profiles);
        Map<String, Object> question = (Map<String, Object>) pending.details().get("failover_pending");
        check.eq("the pending switch names the first declared successor", "backup-one", question.get("to_profile"));
        check.eq("pending evidence carries the failed API profile", List.of("primary"), question.get("exhausted_profiles"));
        Calls resumedCalls = new Calls(Map.of("backup-one", "role_api_timeout"));
        var resumed = f.runner(resumedCalls, new Gate(3)).honourConfirmedVendorFailure(question)
                .run(new ConfigLoader().load(f.project, "hello"), UserConfig.load(f.home),
                        "reviewer", "confirm-after", "writer", null, false, 0);
        check.eq("the confirmed successor's failure is the continuation outcome", "role_api_timeout", resumed.code());
        check.eq("continuation neither retries the first endpoint nor skips the approved successor",
                List.of("backup-one"), resumedCalls.profiles);
        check.eq("the confirmed dispatch spends the stage substitution bound", "backup-two",
                resumed.details().get("failover_bound"));
        List<?> switches = (List<?>) resumed.details().get("role_failovers");
        check.eq("the confirmed switch is recorded as human authorized", "human",
                ((Map<?, ?>) switches.get(0)).get("authorized_by"));
    }

    @SuppressWarnings("unchecked")
    private void mixedFallbackCannotEscape(Check check, Fixture f) throws Exception {
        f.cliProfile("backup-one", "vendorone");
        f.cliProfile("backup-two", "vendortwo");
        f.policy(List.of("backup-one"), "auto", 3);
        Calls cliFailure = new Calls(Map.of("primary", "role_api_provider_error",
                "backup-one", "role_command_failed"));
        var stopped = f.run("mixed-cli", cliFailure, new Gate(5));
        check.eq("a listed CLI failure retains its diagnosis", "role_command_failed", stopped.code());
        check.eq("a listed CLI failure cannot widen the API fallback list",
                List.of("primary", "backup-one"), cliFailure.profiles);

        f.policy(List.of("backup-one", "backup-two"), "auto", 3);
        Calls quota = new Calls(Map.of("primary", "role_api_network_error",
                "backup-one", "role_quota_exhausted"));
        var pending = f.run("mixed-quota-before", quota, new Gate(5));
        check.eq("quota on a listed CLI fallback asks before switching",
                "role_failover_requires_confirmation", pending.code());
        Map<String, Object> question = (Map<String, Object>) pending.details().get("failover_pending");
        check.eq("quota confirmation retains the explicit API list", List.of("backup-one", "backup-two"),
                question.get("api_fallback_profiles"));
        // TaskLoop restores spent subscriptions globally, transient failures for this
        // stage only, and the approved successor. Exercise that same RoleRunner input.
        Map<String, Object> carried = new java.util.LinkedHashMap<>(question);
        carried.put("exhausted_profiles", question.get("vendor_failed_profiles"));
        Calls after = new Calls(Map.of("backup-two", "role_command_failed"));
        var resumed = f.runner(after, new Gate(5)).excluding(java.util.Set.of("backup-one"))
                .honourConfirmedVendorFailure(carried)
                .run(new ConfigLoader().load(f.project, "hello"), UserConfig.load(f.home),
                        "reviewer", "mixed-quota-after", "writer", null, false, 0);
        check.eq("quota-confirmed CLI failure remains the outcome", "role_command_failed", resumed.code());
        check.eq("quota continuation cannot escape to an unlisted roster member",
                List.of("backup-two"), after.profiles);
    }

    private static final class Calls {
        final Map<String, String> failures;
        final Map<String, Object> extraEvidence;
        final List<String> profiles = new ArrayList<>();
        Calls(Map<String, String> failures) { this(failures, Map.of()); }
        Calls(Map<String, String> failures, Map<String, Object> extraEvidence) {
            this.failures = failures;
            this.extraEvidence = extraEvidence;
        }
        RoleExecutor.Result execute(RoleExecutor.Request request) {
            profiles.add(request.profile().name());
            String failure = failures.get(request.profile().name());
            Map<String, Object> artifact = failure == null ? Map.of("role", "reviewer", "task_id", "hello",
                    "status", "completed", "verdict", "pass", "summary", "fixture reading", "findings", List.of()) : null;
            Map<String, Object> evidence = new java.util.LinkedHashMap<>(extraEvidence);
            evidence.put("cost_usd", 0.125);
            return new RoleExecutor.Result(failure == null, failure == null ? "role_completed" : failure,
                    Duration.ofMillis(1), "", artifact, evidence);
        }
    }

    private static final class Gate implements RoleRunner.DispatchGate {
        final int limit;
        int admitted;
        int settled;
        Gate(int limit) { this.limit = limit; }
        @Override public void requireDispatch() {
            if (admitted != settled) throw new IllegalStateException("previous call not settled");
            if (admitted >= limit) throw new IllegalStateException("dispatch budget exceeded");
            admitted++;
        }
        @Override public boolean hasRoom() { return admitted < limit; }
        @Override public void settleAttempt(Object cost, Double bound) { settled++; }
    }

    private static final class Fixture implements AutoCloseable {
        final Path scratch = Files.createTempDirectory("warden-api-fallback-");
        final Path project = scratch.resolve("project");
        final Path home = scratch.resolve("home");
        Fixture() throws Exception {
            new UserSetup().run(home);
            Files.createDirectories(home.resolve("connections"));
            Files.writeString(home.resolve("connections/fixture.yaml"),
                    new Connection("fixture", "custom", "openai_chat", "http://127.0.0.1:9/v1", null, true).toYaml());
            Files.createDirectories(project.resolve(".warden/tasks"));
            Files.createDirectories(project.resolve("src"));
            Files.writeString(project.resolve("src/base.txt"), "base");
            Files.writeString(project.resolve(".gitignore"), ".warden/\n");
            Files.writeString(project.resolve(".warden/project.yaml"),
                    "version: 1\nproject: api-fallback\nbase_ref: HEAD\nchecks: {fast: ['java -version']}\n");
            Files.writeString(project.resolve(".warden/tasks/hello.yaml"),
                    "version: 1\nid: hello\ngoal: Review the fixture candidate\nrisk: medium\nscope: [src]\nacceptance: ['java -version']\n");
            git("init", "--quiet");
            git("config", "user.email", "test@example.invalid");
            git("config", "user.name", "Warden Test");
            git("add", ".");
            git("commit", "--quiet", "-m", "test: API fallback fixture");
            profile("primary", "primaryvendor", true);
            profile("backup-one", "vendorone", true);
            profile("backup-two", "vendortwo", true);
            profile("unlisted", "unlistedvendor", true);
        }
        void profile(String name, String vendor, boolean verified) throws Exception {
            Path file = home.resolve("profiles/" + name + ".yaml");
            Files.writeString(file, """
                    version: 1
                    profile: %s
                    role: reviewer
                    model_vendor: %s
                    model: fixture/%s
                    runner: api
                    connection: fixture
                    read_only: true
                    limits: {wall_clock_minutes: 1, max_output_tokens: 256}
                    prompt_template: prompts/reviewer.md
                    json_schema: schemas/reviewer.json
                    verification:
                      what_to_check: [synthetic text transport]
                    """.formatted(name, vendor, name));
            if (verified) new ProfileVerifier(new ProcessRunner()).stamp(file,
                    UserConfig.load(home).profiles().get(name), LocalDate.of(2026, 10, 29));
        }
        void cliProfile(String name, String vendor) throws IOException {
            Files.writeString(home.resolve("profiles/" + name + ".yaml"), """
                    version: 1
                    profile: %s
                    role: reviewer
                    vendor: %s
                    command: java
                    args: ['-version']
                    read_only: true
                    prompt_template: prompts/reviewer.md
                    json_schema: schemas/reviewer.json
                    verification: {verified_on: '2026-10-29'}
                    """.formatted(name, vendor));
        }
        void policy(List<String> fallback, String mode, int max) throws IOException {
            String roster = Files.exists(home.resolve("profiles/backup-three.yaml"))
                    ? "primary, backup-two, unlisted, backup-three, backup-one"
                    : "primary, backup-two, unlisted, backup-one";
            Files.writeString(home.resolve("policy.yaml"), """
                    version: 1
                    roles:
                      reviewer:
                        profiles: [%s]
                        strategy: rotate
                        require_independent_vendor: true
                    %s
                    failover: {on_vendor_failure: %s, max_per_stage: %d}
                    """.formatted(roster, fallback == null ? "" : "    api_fallback_profiles: " + Json.write(fallback), mode, max));
        }
        RoleRunner runner(Calls calls, Gate gate) {
            return new RoleRunner(new ProcessRunner(), gate).atStage("review")
                    .withExecutors((profile, processes, git) -> calls::execute);
        }
        RoleRunner.Outcome run(String id, Calls calls, Gate gate) throws Exception {
            return runner(calls, gate).run(new ConfigLoader().load(project, "hello"), UserConfig.load(home),
                    "reviewer", id, "writer", null, false, 0);
        }
        private void git(String... args) throws Exception {
            var command = new ArrayList<String>(); command.add("git"); command.addAll(List.of(args));
            var result = new ProcessRunner().run(command, project, Duration.ofSeconds(20));
            if (!result.ok()) throw new IOException("fixture git command failed");
        }
        @Override public void close() throws IOException {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(scratch.toAbsolutePath().normalize()))
                        throw new IOException("cleanup outside API fallback scratch directory");
                    if (!Files.isSymbolicLink(path)) path.toFile().setWritable(true);
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
