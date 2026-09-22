package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * `~/.warden/policy.yaml` — which profiles may fill which role.
 *
 * `requireIndependentVendor` is a hard constraint. A reviewer sharing a vendor with the
 * implementer shares its blind spots, which is the only defensible reason to run more than
 * one vendor at all. When it cannot be satisfied the resolver fails rather than quietly
 * handing the code back to its own author for review.
 *
 * The optional `workflow:` block declares the order those roles run in and the conditions
 * under which each runs at all — see {@link Workflow}. Omitting it keeps the built-in chain.
 *
 * `failover.on_quota_exhausted` decides whether a spent subscription may be routed around
 * without asking. It defaults to `confirm`, not `auto`: swapping vendors mid-run changes who
 * wrote the code and can leave one vendor reviewing its own work.
 */
public record Policy(Map<String, RoleSpec> roles, Set<String> reviewRequiredForRisk,
                     Workflow workflow, boolean workflowDeclared, String failoverMode,
                     String repairReserve, Escalation escalation, RateLimitRetry rateLimitRetry) {

    public Policy(Map<String, RoleSpec> roles, Set<String> reviewRequiredForRisk,
                  Workflow workflow, boolean workflowDeclared, String failoverMode,
                  String repairReserve, Escalation escalation) {
        this(roles, reviewRequiredForRisk, workflow, workflowDeclared, failoverMode,
                repairReserve, escalation, RateLimitRetry.OFF);
    }

    /**
     * The bounded retry a transient rate limit gets before the run stops for a person.
     *
     * Off by default (`max_attempts: 0`): a rate-limited call stops the run as `rate_limited`
     * and a `retry` continuation keeps every verdict, as before. Declared, the same profile is
     * dispatched again after a pause — `backoff_seconds`, doubled per attempt, or the seconds
     * the vendor asked for when its message named them — at most `max_attempts` more times.
     * Every attempt is a vendor call: counted, journaled, charged, and admitted through the
     * same dispatch gate as any other, so a retry cannot spend what the chain has not got.
     * The spent subscription is a different kind and is never retried here.
     */
    public record RateLimitRetry(long maxAttempts, long backoffSeconds) {
        public static final RateLimitRetry OFF = new RateLimitRetry(0, 30);
    }

    public Policy(Map<String, RoleSpec> roles, Set<String> reviewRequiredForRisk,
                  Workflow workflow, boolean workflowDeclared, String failoverMode,
                  String repairReserve) {
        this(roles, reviewRequiredForRisk, workflow, workflowDeclared, failoverMode,
                repairReserve, null);
    }

    /**
     * The opt-in adaptive ladder: after {@code afterBlockingReviews} readings that objected
     * with blocking product findings in one task, the repair goes to the next rung's writer
     * and the objecting stage is re-read by that rung's reader, instead of another repair by
     * the same writer. Rungs are climbed forward only, one per transition, and a rung's reader
     * reads as a co-author (`peer_review`) of the candidate; whatever independent readings the
     * chain still owes are resolved against every writer afterwards.
     *
     * Absent by default. A policy that does not declare it keeps the short loop: repair by
     * the same writer until `max_fix_attempts`, then stop for a person.
     */
    public record Escalation(long afterBlockingReviews, List<Rung> rungs) {
        public Escalation {
            rungs = List.copyOf(rungs);
        }

        public record Rung(String implementer, String reviewer) {
            public String label() { return implementer + " -> " + reviewer; }
        }

        public Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("after_blocking_reviews", afterBlockingReviews);
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (Rung rung : rungs) {
                rows.add(Map.of("implementer", rung.implementer(), "reviewer", rung.reviewer()));
            }
            value.put("rungs", rows);
            return value;
        }
    }

    public record RoleSpec(String role, List<String> profiles, String strategy,
                           boolean requireIndependentVendor, PeerPair peer) {
        public RoleSpec(String role, List<String> profiles, String strategy,
                        boolean requireIndependentVendor) {
            this(role, profiles, strategy, requireIndependentVendor, null);
        }
    }

    /**
     * An explicitly declared same-vendor pair: one writer, one reader, two different models.
     *
     * The rule that runs two vendors exists because a model reviewing its own output shares
     * its own blind spots. An operator with one subscription may still want a second reading,
     * and the plan admits that as a weaker assurance called `same_vendor_peer` — never as
     * independence — provided the pair is named in advance and the two are different models,
     * not the same model at another effort. `require_independent_vendor: false` used to admit
     * any same-vendor reader with no mark on the verdict; now it admits only this pair.
     *
     * A pair cannot be declared beside `require_independent_vendor: true`. A strict policy
     * stays strict; contradictory settings are refused rather than resolved in the reader's
     * favour.
     */
    public record PeerPair(String implementer, String reviewer) {

        /**
         * Why the pair cannot be trusted against the loaded profiles, or null when it can.
         * Checked at resolution rather than at parse, because the policy is parsed before the
         * profiles it names are known to exist.
         */
        public String problem(Map<String, Profile> profiles, String readerRole) {
            Profile writer = profiles.get(implementer);
            Profile reader = profiles.get(reviewer);
            if (writer == null) return "implementer profile '" + implementer + "' not found";
            if (reader == null) return "reviewer profile '" + reviewer + "' not found";
            if (writer.readOnly()) return "'" + implementer + "' is read-only and cannot be the writer";
            if (!readerRole.equals(reader.role())) {
                return "'" + reviewer + "' fills role '" + reader.role() + "', not '" + readerRole + "'";
            }
            if (!writer.vendor().equals(reader.vendor())) {
                return "the pair spans two vendors (" + writer.vendor() + ", " + reader.vendor()
                        + "); that is ordinary independence, not a same-vendor peer";
            }
            if (writer.model() == null || reader.model() == null) {
                return "both profiles must pin a model; a peer is a different model, and an "
                        + "unpinned one is whatever the CLI defaults to";
            }
            if (writer.model().equals(reader.model())) {
                return "both profiles pin model '" + writer.model() + "'; a different effort is "
                        + "not a different model";
            }
            return null;
        }

        public String label() { return implementer + " -> " + reviewer; }
    }

    public static final Set<String> STRATEGIES = Set.of("rotate", "first");

    private static final Set<String> TOP_LEVEL =
            Set.of("version", "roles", "review", "workflow", "failover", "budget", "escalation", "retry");
    private static final Set<String> RETRY_KEYS = Set.of("rate_limited", "note");
    private static final Set<String> RATE_LIMIT_KEYS = Set.of("max_attempts", "backoff_seconds", "note");
    private static final Set<String> ESCALATION_KEYS = Set.of("after_blocking_reviews", "rungs", "note");
    private static final Set<String> RUNG_KEYS = Set.of("implementer", "reviewer");
    private static final Set<String> FAILOVER_KEYS = Set.of("on_quota_exhausted", "note");
    private static final Set<String> BUDGET_KEYS = Set.of("repair_reserve", "note");

    /**
     * How much of the remaining chain a repair round has to be able to pay for before it is
     * allowed to start.
     *
     * `full` reserves the repair and every stage still owed, so a run either finishes or does
     * not begin the attempt. `partial` reserves the repair and at least one stage that will
     * judge what it produces, and lets the run make paid progress it cannot complete —
     * finishing a review whose verdict a continuation reuses for nothing, at the price of
     * possibly buying a regression instead.
     *
     * `full` is the default because the trade is the operator's to make and the conservative
     * side of it is the one that cannot spend money on an outcome nobody asked for. Neither
     * mode is silent: both record the arithmetic in `budget_plan` before the first dispatch.
     */
    public static final Set<String> REPAIR_RESERVES = Set.of("full", "partial");

    /**
     * What happens when the vendor filling a role reports a spent subscription and another
     * eligible profile exists.
     *
     * `confirm` is the default because a failover is a change of who is doing the work, and
     * on a two-vendor roster it can quietly cost the run its independent reviewer: the last
     * vendor standing would be reviewing its own output. That is a judgement about the value
     * of the result, which is the operator's to make.
     */
    public static final Set<String> FAILOVER_MODES = Set.of("confirm", "auto", "stop");
    private static final Set<String> ROLE_KEYS = Set.of(
            "profiles", "strategy", "require_independent_vendor", "same_vendor_peer");
    private static final Set<String> PEER_KEYS = Set.of("implementer", "reviewer");
    private static final Set<String> REVIEW_KEYS = Set.of("required_for_risk", "note");

    public static Policy parse(String yamlText, String source) {
        Values root = Values.of(Yaml.parse(yamlText), source);
        root.rejectUnknownKeys(TOP_LEVEL);
        root.requireVersion(1);

        Values rolesNode = root.optMap("roles");
        Map<String, RoleSpec> roles = new LinkedHashMap<>();
        for (String roleName : rolesNode.keys()) {
            if (!Profile.ROLES.contains(roleName)) {
                root.collector().add("roles." + roleName + " is not a known role; expected one of " + Profile.ROLES);
                continue;
            }
            Values spec = rolesNode.optMap(roleName).rejectUnknownKeys(ROLE_KEYS);
            List<String> profiles = spec.requireStringList("profiles");
            String strategy = spec.requireEnum("strategy", STRATEGIES, "rotate");
            boolean independent = spec.optBool("require_independent_vendor", roleName.equals("reviewer"));
            PeerPair peer = null;
            if (spec.has("same_vendor_peer")) {
                Values pair = spec.optMap("same_vendor_peer").rejectUnknownKeys(PEER_KEYS);
                String writer = pair.requireString("implementer");
                String reader = pair.requireString("reviewer");
                if (independent) {
                    root.collector().add("roles." + roleName + ": require_independent_vendor: true "
                            + "and same_vendor_peer conflict; a strict policy stays strict, so "
                            + "drop one of them");
                } else if (writer != null && reader != null) {
                    if (!profiles.contains(reader)) {
                        root.collector().add("roles." + roleName + ".same_vendor_peer.reviewer '"
                                + reader + "' must be one of this role's profiles " + profiles);
                    }
                    peer = new PeerPair(writer, reader);
                }
            }
            roles.put(roleName, new RoleSpec(roleName, profiles, strategy, independent, peer));
        }
        if (roles.isEmpty()) {
            root.collector().add("roles must declare at least one role");
        }

        Values review = root.optMap("review").rejectUnknownKeys(REVIEW_KEYS);
        List<String> requiredForRisk = review.optStringList("required_for_risk", List.of("medium", "high"));
        for (String risk : requiredForRisk) {
            if (!ProjectConfig.RISK_LEVELS.contains(risk)) {
                root.collector().add("review.required_for_risk contains '" + risk
                        + "', expected one of " + ProjectConfig.RISK_LEVELS);
            }
        }

        // A policy that says nothing about order gets the documented loop. Declaring the
        // block replaces the chain wholesale rather than patching it: a workflow assembled
        // from a default plus overrides is one nobody can read off the file in front of them.
        Values failover = root.optMap("failover").rejectUnknownKeys(FAILOVER_KEYS);
        String failoverMode = failover.requireEnum("on_quota_exhausted", FAILOVER_MODES, "confirm");

        Values budget = root.optMap("budget").rejectUnknownKeys(BUDGET_KEYS);
        String repairReserve = budget.requireEnum("repair_reserve", REPAIR_RESERVES, "full");

        boolean workflowDeclared = root.has("workflow");
        Workflow workflow = workflowDeclared ? Workflow.parse(root, "workflow") : Workflow.builtIn();

        RateLimitRetry rateLimitRetry = RateLimitRetry.OFF;
        if (root.has("retry")) {
            Values retry = root.optMap("retry").rejectUnknownKeys(RETRY_KEYS);
            Values rate = retry.optMap("rate_limited").rejectUnknownKeys(RATE_LIMIT_KEYS);
            rateLimitRetry = new RateLimitRetry(rate.optInt("max_attempts", 0, 0, 5),
                    rate.optInt("backoff_seconds", 30, 1, 900));
        }

        Escalation escalation = null;
        if (root.has("escalation")) {
            Values ladder = root.optMap("escalation").rejectUnknownKeys(ESCALATION_KEYS);
            long after = ladder.optInt("after_blocking_reviews", 2, 1, 10);
            List<Escalation.Rung> rungs = new java.util.ArrayList<>();
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (Values rung : ladder.mapList("rungs")) {
                rung.rejectUnknownKeys(RUNG_KEYS);
                String writer = rung.requireString("implementer");
                String reader = rung.requireString("reviewer");
                if (writer == null || reader == null) continue;
                if (writer.equals(reader)) {
                    root.collector().add("escalation.rungs: '" + writer + "' cannot both write and "
                            + "read its own rung");
                    continue;
                }
                if (!seen.add(writer)) {
                    root.collector().add("escalation.rungs: '" + writer + "' writes on two rungs; "
                            + "a ladder climbs forward through different writers");
                    continue;
                }
                rungs.add(new Escalation.Rung(writer, reader));
            }
            if (rungs.isEmpty()) {
                root.collector().add("escalation.rungs must declare at least one rung with an "
                        + "implementer and a reviewer");
            } else {
                escalation = new Escalation(after, rungs);
            }
        }

        root.throwIfAny();
        return new Policy(roles, Set.copyOf(requiredForRisk), workflow, workflowDeclared,
                failoverMode, repairReserve, escalation, rateLimitRetry);
    }

    public boolean reviewRequired(String risk) {
        return reviewRequiredForRisk.contains(risk);
    }
}
