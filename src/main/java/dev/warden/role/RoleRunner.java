package dev.warden.role;

import dev.warden.config.ConfigLoader;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.execution.Executors;
import dev.warden.execution.RoleExecutor;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.Findings;
import dev.warden.ledger.HomeCorpus;
import dev.warden.ledger.MeasurementContext;
import dev.warden.process.ProcessRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One role, start to finish: resolve which vendor fills it, render the prompt, run it, and
 * record what happened.
 *
 * `--dry-run` performs everything except the vendor call. It is the cheapest way to answer
 * "is my setup right" — it shows which profile would run, which were rejected and why, and
 * writes the exact prompt that would have been sent, all without spending a token.
 *
 * <h2>Failing over when a subscription is spent</h2>
 *
 * An exhausted subscription is the one failure that is not about the work. Handing the same
 * failure back to the same vendor is right for a broken build and wrong for a spent plan: the
 * retry refuses identically, and the loop spends its remaining budget discovering that. So a
 * run classified {@code role_quota_exhausted} re-resolves the role with that profile excluded
 * and dispatches the next eligible one.
 *
 * The exclusion is held in this instance and nowhere else. A loop reuses one runner, so a
 * vendor that ran out during the implement step is not tried again at review time; a fresh
 * `warden role` invocation starts clean, because a quota that reset overnight should not stay
 * disabled by a file left behind yesterday. Persisted exhaustion state would be the kind of
 * stale fact that is only discovered when it is already wrong.
 *
 * Failover is not free of consequence for a writing role: an implementer can be cut off after
 * it has already edited the tree. That is recorded rather than smoothed over, and the vendor
 * that inherits the work is told, in its prompt, that it is continuing someone else's.
 *
 * <h2>Failing over when the call produced no artifact</h2>
 *
 * A network error, a timeout, a permission refusal, a location or eligibility refusal
 * ({@code role_vendor_unavailable}), or an artifact that is missing or invalid is also not
 * a verdict on the work. {@code failover.on_vendor_failure} decides whether another profile
 * may take the role. It defaults to {@code stop}, so a policy that never mentions it ends
 * the role exactly as before. {@code auto} switches, {@code confirm}
 * stops for the same human decision a spent subscription does, and {@code max_per_stage}
 * (default 1) is how many substitutions that stage may make, including a switch confirmed
 * on {@code --continue}. The continuation keeps that count and every profile that already
 * failed the stage for the dispatch that honours the switch, and that dispatch spends both.
 * A later re-run of the stage in the same process does not inherit either. A valid artifact is
 * kept even when the process exits non-zero. A review finding, a gate failure and a rejection
 * never enter this path: those are judgements, and routing around them would hide the defect. The
 * failed attempt stays in {@code vendor_attempts}, with its code, the tail of what the
 * vendor printed, and {@code start_error} when the process could not be launched.
 */
public final class RoleRunner {

    public record Outcome(
            boolean ok,
            String code,
            String role,
            String profile,
            String vendor,
            Map<String, String> rejected,
            Path report,
            Map<String, Object> details) {}

    /**
     * Consulted immediately before each vendor dispatch, and after a failover decision has
     * been taken. Throwing refuses the dispatch.
     *
     * The seam exists so that a bounded loop's budget is enforced per vendor call rather than
     * per role: without it, a role that failed over twice would spend three times what its
     * caller had counted, and the overrun would be discovered by paying for it.
     *
     * {@link #hasRoom()} is asked before a failover {@code continue}, so a one-call bootstrap
     * can return the spent-subscription outcome instead of throwing from inside the next
     * iteration after a prompt for a call that must not happen has already been written.
     * The loop's budget still uses a method reference and keeps the default, so it still
     * refuses at {@link #requireDispatch} — existing stop reasons stay the same.
     */
    @FunctionalInterface
    public interface DispatchGate {
        void requireDispatch();

        default boolean hasRoom() { return true; }

        /**
         * The longest the next call may run, or null when nothing but the profile bounds it.
         *
         * Asked right after {@link #requireDispatch} admitted the call. A chain with an
         * execution deadline answers with what is left of it, and the call's wall clock is
         * lowered to that — never raised — so a vendor that would have been allowed an hour
         * cannot carry the chain an hour past the time the operator gave it.
         */
        default java.time.Duration wallClockCap() { return null; }

        /**
         * Refuse a retry pause that cannot fit before the next call's minimum execution
         * allowance. Asked after the preceding attempt is journaled and settled, before
         * sleeping; it must not reserve another dispatch. Standalone roles have no chain
         * deadline and leave the default unchanged.
         */
        default void requireRetryWait(long pauseMillis) { }

        /**
         * The declared per-call bound of the profile about to run. Asked immediately before
         * {@link #requireDispatch} so a strict money cap reserves who routing actually
         * selected — a {@code --use} pin, an Orca twin, a substitution — rather than the
         * largest name on the policy list.
         */
        default void reserve(Double declaredBound) { }

        /**
         * Charge a completed vendor attempt before the next dispatch of this role is
         * considered. A rate-limit retry and a failover are further calls; leaving their
         * predecessors unaccounted until {@code RoleRunner.run} returns let them share one
         * outstanding bound and overrun a strict cap. An unpriced attempt is charged at
         * {@code declaredBound} when the cap is strict.
         */
        default void settleAttempt(Object costUsd, Double declaredBound) { }

        /**
         * Give back the call {@link #requireDispatch} admitted, in place of
         * {@link #settleAttempt}, when the executor proved no vendor was asked anything: an
         * Orca worker fenced while its agent sat on a first-run question. Only that proof
         * counts. A call whose outcome is merely unknown may still have been served and
         * billed, and stays charged.
         */
        default void release() { }
    }

    /**
     * The profile this resolution just chose, so a beat can name who is actually inside the
     * role without asking the resolver a second time.
     *
     * Resolution advances rotation state and consults the run's exclusion list. A second
     * lookup from the loop would rotate twice and could name a profile that was not the one
     * dispatched. Throwing here is swallowed: naming the vendor is a retelling, not a reason
     * to stop a paid call.
     */
    @FunctionalInterface
    public interface Occupied {
        void by(String profile, String vendor);
    }

    private static final DispatchGate ALWAYS = () -> { };
    private static final Occupied NOBODY = (profile, vendor) -> { };

    private final ProcessRunner processes;
    private final DispatchGate gate;
    private final String pinnedDiffBase;
    private final String workflowRunId;
    private final Map<String, String> authorizedFailover;
    private final dev.warden.run.Progress progress;
    private Occupied occupied = NOBODY;
    private String stageName;
    /** How the runner waits between rate-limit retries; the suite replaces it. */
    private java.util.function.LongConsumer sleeper = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    };

    /** The same runner, pausing through {@code sleeper} instead of the clock. */
    public RoleRunner withSleeper(java.util.function.LongConsumer sleeper) {
        this.sleeper = sleeper == null ? millis -> { } : sleeper;
        return this;
    }

    /** How a profile becomes an executor; the suite hands Orca profiles a fake Orca. */
    private Executors.Factory executors = Executors::forProfile;

    public RoleRunner withExecutors(Executors.Factory executors) {
        this.executors = executors == null ? Executors::forProfile : executors;
        return this;
    }
    /** 1-based dispatch of this runner for the current role. A later {@link #run} of the
     *  same role (the planner's protocol retry) must not reuse the first call's report name. */
    private int evidenceDispatch = 1;
    /** Monotonic across vendor attempts that share a role and a run directory, including
     *  failover and a later {@link #forDispatch} of that same call. Reset when either
     *  changes so a reused loop runner still names the first prompt of a later stage
     *  {@code reviewer.md} in that stage's own directory. {@code evidenceDispatch + attempt - 1}
     *  collides when a first dispatch already used {@code attempt-2} and a retry starts
     *  at attempt 1 in the same directory. */
    private int nextEvidenceSerial = 1;
    private String evidenceSerialKey;

    public RoleRunner atStage(String name) { stageName = name; return this; }

    /**
     * Name this {@link #run} as the Nth dispatch of the role (1-based). The first keeps the
     * historical {@code planner} / {@code role-planner.json} names; a later one writes
     * {@code planner.attempt-N} so a retry cannot overwrite the discarded attempt's prompt,
     * raw stdout or report while that attempt's ledger event still points at them.
     */
    public RoleRunner forDispatch(int dispatch) {
        this.evidenceDispatch = dispatch < 1 ? 1 : dispatch;
        return this;
    }

    /** Profiles that reported a spent subscription during the life of this runner. */
    private final Set<String> exhausted = new LinkedHashSet<>();

    /**
     * Profiles whose vendor call produced no artifact during the current {@link #run}.
     *
     * Cleared at the start of each dispatch. A network error is not a spent subscription,
     * so the next stage may try the same profile again; within this dispatch it must not
     * be selected twice, or a strategy of {@code first} would loop on it.
     * A location or eligibility refusal is the exception and lives in {@link #unavailable}.
     */
    private final Set<String> vendorFailedThisDispatch = new LinkedHashSet<>();

    /**
     * Profiles that returned {@code role_vendor_unavailable} during the life of this runner.
     *
     * A 403, {@code PERMISSION_DENIED}, or a "not eligible" / "not available in your
     * location" refusal fails the same way on every later call. The profile is skipped
     * up front for the rest of this process, including later stages. It is not added to
     * {@link #exhausted}: that set means a spent subscription, and a different profile of
     * the same vendor is still eligible. A new process starts with this set empty. A
     * network error is not recorded here.
     */
    private final Set<String> unavailable = new LinkedHashSet<>();

    /**
     * A vendor-failure confirmation carried by {@code --continue}, keyed by stage.
     *
     * The pending question already named the successor. Rotation is not asked again:
     * with {@code rotate} and three profiles it would dispatch someone the person
     * never approved, and the failed call would not be there to emit {@code role_failover}.
     */
    private final Map<String, Map<String, Object>> confirmedVendorSwitches = new LinkedHashMap<>();

    /**
     * Profiles a continued stage must not pay again, until the first {@link #run} of that
     * stage. Scoped to that stage, not to the process-lifetime {@link #exhausted} set: a
     * network error is not a spent subscription, and a later stage may still select the
     * profile. The set is every profile that already failed the stage, not only the last
     * one. That first dispatch removes the entry, so a fix round that re-runs the stage
     * does not keep excluding them.
     */
    private final Map<String, Set<String>> vendorExcludedForStage = new LinkedHashMap<>();

    /**
     * Vendor-failure substitutions a continued stage has already used, keyed by stage,
     * until the first {@link #run} of that stage.
     *
     * A confirmed switch counts when that profile is dispatched, on top of this number.
     * That first dispatch removes the entry. A fix round that re-runs the stage starts
     * the count over; leaving it in place with {@code max_per_stage} at one makes the
     * re-run unable to switch. A later stage does not inherit the count, and neither
     * fact is a spent subscription.
     */
    private final Map<String, Integer> vendorFailoversCarried = new LinkedHashMap<>();

    /** Stages whose confirmed switch has already been written, so a recheck does not write it twice. */
    private final Set<String> confirmedSwitchRecorded = new LinkedHashSet<>();

    /**
     * Assignments a run pinned for the rest of its chain, by role and by stage name.
     *
     * The escalation ladder names them: after the declared number of blocking readings the
     * loop stops asking the roster and hands the repair to the next rung's writer, and the
     * objecting stage to that rung's reader. A pin is resolved without the roster's rotation
     * or independence filter, because the pin is the operator's explicit instruction to read
     * as a co-author; what it keeps is every other check — the profile must exist, fill the
     * role, be verified, be present and not be spent — and it is labelled `peer_review`
     * rather than independent whenever the reader's vendor wrote into the candidate.
     */
    private final Map<String, String> roleAssignments = new LinkedHashMap<>();
    private final Map<String, String> stageAssignments = new LinkedHashMap<>();
    /**
     * Stages whose pin came from the operator rather than from the escalation ladder.
     *
     * The two are not the same instruction and must not share a resolution path. The ladder
     * climbs to a named pair *because* a reading objected, and the pair is declared in the
     * policy the independence rule itself comes from: that is a documented exception. A
     * person typing {@code --use review=claude-review} is choosing a reader, and choosing a
     * reader has never been permission to waive the contract's
     * {@code require_independent_vendor}. Without this set, it silently was.
     */
    private final Set<String> operatorPinnedStages = new LinkedHashSet<>();
    private RunOverride overlay = RunOverride.NONE;
    private final Map<String, String> replacements = new LinkedHashMap<>();

    public RoleRunner excluding(Set<String> profiles) {
        exhausted.addAll(profiles);
        return this;
    }

    /**
     * Honour a vendor-failure switch a person already approved.
     *
     * Pins {@code to_profile} for that stage, and remembers every profile that already
     * failed it, plus how many substitutions the stage has already used. Neither fact is
     * a spent subscription. The first {@link #run} of the stage spends both memories; a
     * later dispatch in this process, including a fix round of the same stage, starts
     * without them. A spent subscription is not stored here.
     */
    public RoleRunner honourConfirmedVendorFailure(Map<String, Object> pending) {
        if (pending == null || pending.isEmpty()) return this;
        String stage = pending.get("stage") == null
                ? String.valueOf(pending.get("role"))
                : String.valueOf(pending.get("stage"));
        confirmedVendorSwitches.put(stage, new LinkedHashMap<>(pending));
        Object successor = pending.get("to_profile");
        if (successor != null && !String.valueOf(successor).isBlank()) {
            replacements.put(stage, String.valueOf(successor));
        }
        if (pending.get("exhausted_profiles") instanceof List<?> names) {
            Set<String> excluded = vendorExcludedForStage.computeIfAbsent(
                    stage, key -> new LinkedHashSet<>());
            for (Object name : names) {
                if (name != null) excluded.add(String.valueOf(name));
            }
        }
        if (pending.get("vendor_failovers") instanceof Number number) {
            vendorFailoversCarried.put(stage, Math.max(0, number.intValue()));
        }
        return this;
    }

    /** Pin {@code profile} for every later dispatch of {@code role}. */
    public RoleRunner assignRole(String role, String profile) {
        roleAssignments.put(role, profile);
        return this;
    }

    /**
     * Pin {@code profile} for every later dispatch of the stage called {@code stage}.
     *
     * The escalation ladder's pin, and it takes the stage over: a rung reached after a
     * reading objected replaces whatever the operator chose for that stage, so the choice
     * is no longer theirs and must not keep being judged as theirs. Leaving the flag set
     * would apply the independence filter to a pair the policy declared for exactly the
     * case where the ordinary rotation has run out.
     */
    public RoleRunner assignStage(String stage, String profile) {
        stageAssignments.put(stage, profile);
        operatorPinnedStages.remove(stage);
        return this;
    }

    /**
     * The same pin, made by a person for one run, and judged by the roster's own filters.
     *
     * Everything {@link #assignStage} checks is checked here too; what is added back is the
     * independence question the ladder is allowed to skip. A refusal names the resolver's own
     * reason, so the operator reads `same_vendor_as_writer` and not `pinned profile cannot
     * fill role`.
     */
    public RoleRunner pinForRun(String stage, String profile) {
        stageAssignments.put(stage, profile);
        operatorPinnedStages.add(stage);
        return this;
    }

    /**
     * Per-run effort and host overlays. Pins are applied separately through
     * {@link #pinForRun}; this only rewrites the profile after it is chosen.
     */
    public RoleRunner overlay(RunOverride next) {
        this.overlay = next == null ? RunOverride.NONE : next;
        return this;
    }

    /** Who is installed, when the caller already asked; null probes the machine. */
    private RoleResolver.Availability availability;

    /**
     * The same runner, taking {@code known} as the answer to "is this profile's program here".
     * The settings panel previews every task on each refresh and probes each program once.
     */
    public RoleRunner availability(RoleResolver.Availability known) {
        this.availability = known;
        return this;
    }

    /** The pin that applies to a dispatch of {@code role} at {@code stage}, or null. */
    private String pinFor(String stage, String role) {
        String key = stage == null ? role : stage;
        String named = usable(replacements.get(key));
        if (named != null) return named;
        // A quota authorisation is not a pin. Rotation still names the profile, and the
        // spent one stays in the exhausted set so the switch is a role_failover when that
        // call fails again. A vendor-failure confirmation is a pin, applied by
        // honourConfirmedVendorFailure: the approved profile, not whoever rotation
        // would pick with the failed name removed.
        if (stage != null && stageAssignments.containsKey(stage)) {
            named = usable(stageAssignments.get(stage));
            if (named != null) return named;
            // The operator pinned a profile that has already reported a spent subscription
            // this run. Keeping the pin would re-dispatch it and never reach the backup the
            // roster still has; dropping it lets rotation (and failover) pick the next one.
            return null;
        }
        return usable(roleAssignments.get(role));
    }

    private String usable(String profile) {
        if (profile == null || exhausted.contains(profile)
                || unavailable.contains(profile)
                || vendorFailedThisDispatch.contains(profile)) return null;
        return profile;
    }

    /**
     * A pinned profile, resolved by the same checks the roster applies minus the rotation,
     * and labelled for what it is.
     *
     * @param operatorChoice whether a person named this profile for this run. When they did,
     *                       the independence filter applies exactly as it would to a rotated
     *                       choice: a run that would have refused a same-vendor reader still
     *                       refuses it, and the only thing the pin decided is which of the
     *                       admissible profiles runs. The escalation ladder passes false,
     *                       because a rung is a pair the policy declared for precisely the
     *                       case where the ordinary rotation has nothing left.
     */
    private RoleResolver.Resolution resolvePinned(String pinned, String role, UserConfig user,
                                                  RoleResolver.Writers writers,
                                                  boolean operatorChoice) {
        Profile profile = user.profiles().get(pinned);
        Map<String, String> rejected = new LinkedHashMap<>();
        if (profile == null) rejected.put(pinned, "profile_not_found");
        else if (!profile.role().equals(role)) rejected.put(pinned, "role_mismatch");
        else if (profile.connectionRef() != null && (profile.apiConnection() == null
                || !profile.apiConnection().enabled())) {
            rejected.put(pinned, "connection_unavailable");
        } else if (profile.harness() != null && !profile.verified()) {
            rejected.put(pinned, "harness_coding_unproven");
        } else if (dev.warden.execution.EffortCapabilities.problem(profile)!=null) {
            rejected.put(pinned,"role_effort_unsupported");
        } else if (RoleResolver.judges(role) && !profile.readOnly()) {
            rejected.put(pinned, RoleResolver.JUDGE_NOT_READ_ONLY);
        } else if ("visual_qa".equals(role) && !profile.hasVerifiedVision()) {
            rejected.put(pinned, "vision_capability_unverified");
        } else if (!profile.verified()) rejected.put(pinned, "profile_unverified");
        else if (exhausted.contains(pinned)) rejected.put(pinned, "quota_exhausted_this_run");
        else if (unavailable.contains(pinned)) rejected.put(pinned, "vendor_unavailable_this_run");
        else if (vendorFailedThisDispatch.contains(pinned)) {
            rejected.put(pinned, "vendor_failed_this_attempt");
        }
        else if (!available(profile)) rejected.put(pinned, "executable_not_found");
        if (!rejected.isEmpty()) {
            throw new RoleResolver.Unresolvable("pinned profile cannot fill role " + role + ": "
                    + rejected, rejected);
        }
        if (operatorChoice) {
            Policy.RoleSpec spec = user.policy() == null ? null : user.policy().roles().get(role);
            if (spec != null) {
                String assurance = RoleResolver.assuranceOf(spec, profile, user.profiles(), writers);
                if (assurance.startsWith("refused:")) {
                    rejected.put(pinned, assurance.substring("refused:".length()));
                    throw new RoleResolver.Unresolvable("pinned profile cannot fill role " + role
                            + ": " + rejected, rejected);
                }
                return new RoleResolver.Resolution(profile, Map.of(), assurance);
            }
        }
        String assurance;
        if (!profile.readOnly()) assurance = RoleResolver.NONE;
        else if (!writers.known()) assurance = RoleResolver.UNPROVEN;
        else if (!writers.contains(profile.vendor())) assurance = RoleResolver.INDEPENDENT;
        else assurance = "peer_review";
        return new RoleResolver.Resolution(profile, Map.of(), assurance);
    }

    /** Whether the pin in force at {@code stage} was typed by a person for this run. */
    private boolean pinnedByOperator(String stage, String role) {
        String key = stage == null ? role : stage;
        if (replacements.containsKey(key)) return true;
        if (stage != null && stageAssignments.containsKey(stage)) {
            return operatorPinnedStages.contains(stage);
        }
        return false;
    }

    public RoleRunner(ProcessRunner processes) { this(processes, ALWAYS, null, null); }

    public RoleRunner(ProcessRunner processes, DispatchGate gate) {
        this(processes, gate, null, null);
    }

    /**
     * @param pinnedDiffBase immutable commit selected by the outer run controller before any
     *                       vendor is dispatched. Null is retained for the standalone
     *                       {@code warden role} command, which resolves its own one-shot base.
     */
    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase) {
        this(processes, gate, pinnedDiffBase, null);
    }

    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId) {
        this(processes, gate, pinnedDiffBase, workflowRunId, Map.of());
    }

    /**
     * @param authorizedFailover role to profile substitutions a human has already agreed to,
     *                           carried from a resolved `switch` decision on an earlier run.
     *                           Each entry authorises exactly that swap and nothing else.
     */
    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId, Map<String, String> authorizedFailover) {
        this(processes, gate, pinnedDiffBase, workflowRunId, authorizedFailover,
                dev.warden.run.Progress.SILENT);
    }

    public RoleRunner(ProcessRunner processes, DispatchGate gate, String pinnedDiffBase,
                      String workflowRunId, Map<String, String> authorizedFailover,
                      dev.warden.run.Progress progress) {
        this.processes = processes;
        this.gate = gate;
        this.pinnedDiffBase = pinnedDiffBase;
        this.workflowRunId = workflowRunId;
        this.authorizedFailover = Map.copyOf(authorizedFailover);
        this.progress = progress;
    }

    /**
     * Who should hear the profile the next resolution actually chooses.
     *
     * One runner is reused for every role in a loop. The loop points this at a callback that
     * reads the beat currently wrapping the dispatch, so a fix round that runs with no beat
     * is a no-op rather than a name invented for a stage that has already ended.
     */
    public RoleRunner occupying(Occupied occupied) {
        this.occupied = occupied == null ? NOBODY : occupied;
        return this;
    }

    /** Profiles this runner has seen run out, in the order they did. */
    public Set<String> exhaustedProfiles() { return Set.copyOf(exhausted); }

    /** Initial model selection uses the ordinary admission and local-availability rules. */
    public List<RoleResolver.Candidate> routingCandidates(UserConfig user,String role,RoleResolver.Writers writers) {
        return new RoleResolver().candidates(role,user.policy(),user.profiles(),writers,this::available,
                exhausted,Set.of(),unavailable);
    }
    public boolean pinnedForRouting(String stage,String role){return pinFor(stage,role)!=null;}
    public void excludeForRouting(String profile){unavailable.add(profile);}
    public boolean excludedForRouting(String profile){return unavailable.contains(profile);}

    /**
     * Whether anybody could still fill this role, asked without dispatching or rotating.
     *
     * A bounded loop decides to spend on a repair before it discovers whether the repaired
     * work can be judged, and those are the wrong way round when the roster has thinned. Two
     * vendors and one spent subscription leaves one vendor, and a reviewer required to differ
     * from the implementer then has nobody left — which used to be found at the review stage,
     * after the repair had been paid for and had already moved the tree.
     *
     * Resolution is deliberately not cached: a probe answers whether the executable is there,
     * the exhausted set is this runner's own, and both can be true now and false in a minute.
     *
     * @param avoidVendor the vendor a role must differ from, or null when it need not
     */
    public boolean canFill(UserConfig user, String role, String avoidVendor, long rotation) {
        return canFill(user, role, RoleResolver.Writers.of(avoidVendor), rotation);
    }

    /** @param writers who has written into the candidate; a reader must differ from all of them */
    public boolean canFill(UserConfig user, String role, RoleResolver.Writers writers, long rotation) {
        return canFill(user, null, role, writers, rotation);
    }

    /**
     * The profile a dispatch of {@code role} at {@code stage} would get, without dispatching
     * and without advancing anything, or null when nobody could fill it.
     *
     * A preflight asks this for the writer so it can ask {@link #canFill} about every reader
     * against that writer's vendor before the writer is paid. Rotation state is read, not
     * written: a single-stage role's counter is consulted the way a dry run consults it.
     */
    public Profile peek(Path root, UserConfig user, String stage, String role,
                        RoleResolver.Writers writers, int stagePosition) {
        if (user.policy() == null || !user.policy().roles().containsKey(role)) return null;
        try {
            RoleResolver.Writers known = writers == null ? RoleResolver.Writers.NONE : writers;
            String pinned = pinFor(stage, role);
            Profile selected = pinned != null
                    ? resolvePinned(pinned, role, user, known, pinnedByOperator(stage, role)).selected()
                    : new RoleResolver().resolve(role, user.policy(), user.profiles(), known,
                            stagePosition >= 0 ? stagePosition : nextRotation(root, role, true),
                            this::available, exhausted, Set.of(), unavailable).selected();
            return overlay.adapt(stage, selected, user.profiles());
        } catch (Exception nobody) {
            return null;
        }
    }

    /**
     * Why nobody can fill {@code role} at {@code stage}, profile by profile, or null when
     * somebody can. The preflight reports this so an operator reads the resolver's own reason
     * — a spent subscription, a shared vendor, an invalid peer pair — and not only the fact.
     */
    public Map<String, String> explainFill(UserConfig user, String stage, String role,
                                           RoleResolver.Writers writers, long rotation) {
        if (user.policy() == null || !user.policy().roles().containsKey(role)) {
            return Map.of(role, "role_not_configured");
        }
        try {
            String pinned = pinFor(stage, role);
            if (pinned != null) {
                resolvePinned(pinned, role, user, writers == null ? RoleResolver.Writers.NONE : writers,
                        pinnedByOperator(stage, role));
                return null;
            }
            new RoleResolver().resolve(role, user.policy(), user.profiles(), writers,
                    Math.max(0, rotation), this::available, exhausted, Set.of(), unavailable);
            return null;
        } catch (RoleResolver.Unresolvable nobody) {
            return nobody.rejected();
        } catch (RuntimeException nobody) {
            return Map.of(role, String.valueOf(nobody.getMessage()));
        }
    }

    /**
     * The same question for a named stage, so a stage the escalation ladder pinned is judged
     * by its pin and not by a roster it will not consult.
     */
    public boolean canFill(UserConfig user, String stage, String role, RoleResolver.Writers writers,
                           long rotation) {
        if (user.policy() == null || !user.policy().roles().containsKey(role)) return false;
        try {
            String pinned = pinFor(stage, role);
            if (pinned != null) {
                resolvePinned(pinned, role, user, writers == null ? RoleResolver.Writers.NONE : writers,
                        pinnedByOperator(stage, role));
                return true;
            }
            new RoleResolver().resolve(role, user.policy(), user.profiles(), writers,
                    Math.max(0, rotation), this::available, exhausted, Set.of(), unavailable);
            return true;
        } catch (RuntimeException nobody) {
            return false;
        }
    }

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, boolean dryRun) throws Exception {
        return run(loaded, user, role, runId, implementerVendor, contextFile, List.of(), dryRun);
    }

    /**
     * @param stagePosition where the dispatching stage sits among the stages sharing this
     *                      role. It, and not a running total of dispatches, is what decides
     *                      which profile a {@code rotate} role gets — see
     *                      {@link #rotationFor}.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, boolean dryRun,
                       int stagePosition) throws Exception {
        return run(loaded, user, role, runId, implementerVendor, contextFile, List.of(), dryRun,
                stagePosition);
    }

    /**
     * @param attachments files the role must look at rather than read about — screenshots,
     *                    today. Their paths always reach the prompt as {{screenshots}}; a
     *                    profile that declares {@code attachments.flag} also gets them on the
     *                    command line, which is the only way a model actually sees an image.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, List<Path> attachments,
                       boolean dryRun) throws Exception {
        return run(loaded, user, role, runId, implementerVendor, contextFile, attachments, dryRun,
                UNSTAGED);
    }

    /** A dispatch that came from no workflow stage: `warden role`, and the visual role. */
    public static final int UNSTAGED = -1;

    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       String implementerVendor, Path contextFile, List<Path> attachments,
                       boolean dryRun, int stagePosition) throws Exception {
        return run(loaded, user, role, runId, RoleResolver.Writers.of(implementerVendor),
                contextFile, attachments, dryRun, stagePosition);
    }

    /**
     * @param writers every vendor that may have left bytes in the candidate, with whether that
     *                set is known at all. A reader is resolved against all of them; a writer
     *                is not measured against itself. See {@link RoleResolver.Writers}.
     */
    public Outcome run(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                       RoleResolver.Writers writers, Path contextFile, List<Path> attachments,
                       boolean dryRun, int stagePosition) throws Exception {
        try (var execution = dryRun ? dev.warden.execution.WorktreeLease.preview()
                : dev.warden.execution.WorktreeLease.acquire(loaded.root(),
                        workflowRunId == null ? runId : workflowRunId)) {
            return runOwned(loaded, user, role, runId, writers, contextFile, attachments, dryRun, stagePosition);
        }
    }

    private Outcome runOwned(ConfigLoader.Loaded loaded, UserConfig user, String role, String runId,
                             RoleResolver.Writers writers, Path contextFile, List<Path> attachments,
                             boolean dryRun, int stagePosition) throws Exception {
        if (writers == null) writers = RoleResolver.Writers.NONE;
        if (user.policy() == null) {
            throw new IllegalStateException("no policy at " + user.home().resolve("policy.yaml")
                    + " — run `warden setup` to create a starter configuration");
        }
        Policy.RoleSpec spec = user.policy().roles().get(role);
        if (spec == null) {
            throw new IllegalStateException("role '" + role + "' is not configured in "
                    + user.home().resolve("policy.yaml") + "; configured roles: " + user.policy().roles().keySet());
        }

        Path root = loaded.root();
        GitRepository git = new GitRepository(root, processes);
        EvidenceLedger ledger = new EvidenceLedger(root, runId, user.home());
        TaskSpec.ResolvedTask task = loaded.resolved();
        String roleInvocationId = java.util.UUID.randomUUID().toString();
        String mergeBase = pinnedDiffBase != null ? pinnedDiffBase : git.mergeBase(task.baseRef());
        String serialKey = role + "\0" + runId;
        if (!serialKey.equals(evidenceSerialKey)) {
            evidenceSerialKey = serialKey;
            nextEvidenceSerial = 1;
        }

        long rotation = rotationFor(root, role, stagePosition, dryRun);

        // Taken before the first vendor runs so that, if a writing role is cut off mid-edit,
        // the successor can be told the tree is not the one the task described.
        String fingerprintAtStart = git.fingerprint(mergeBase);

        List<Map<String, Object>> attempts = new ArrayList<>();
        double spent = 0;
        int rateLimitRetries = 0;
        List<Map<String, Object>> rateLimitPauses = new ArrayList<>();
        // This attempt cannot switch more than the policy allows, and cannot select a
        // profile that already failed it. A continuation of the same stage brings both
        // facts back for this dispatch only: the first run() removes them. A fix round
        // re-runs the stage in this same process and must start clean, or a carried
        // count of one with max_per_stage at one would make that re-run unable to
        // switch. A later stage never saw the entries. A spent subscription is not
        // among them. It stays in the process-lifetime exhausted set, which this
        // removal does not touch: the plan is still spent on the re-run.
        vendorFailedThisDispatch.clear();
        String stageKey = stageName == null ? role : stageName;
        Set<String> stagedExclusion = vendorExcludedForStage.remove(stageKey);
        if (stagedExclusion != null) vendorFailedThisDispatch.addAll(stagedExclusion);
        int vendorFailovers = vendorFailoversCarried.getOrDefault(stageKey, 0);
        vendorFailoversCarried.remove(stageKey);
        List<Map<String, Object>> roleFailovers = new ArrayList<>();
        // Once an API failure opens this list, later failures must not widen it to the
        // rotating roster (including when a named fallback is a CLI profile).
        Map<String, Object> confirmedSwitch = confirmedVendorSwitches.get(stageKey);
        boolean apiFallbackActive = confirmedSwitch != null
                && !confirmedSwitchRecorded.contains(stageKey)
                && confirmedSwitch.get("api_fallback_profiles") instanceof List<?> names
                && !names.isEmpty();

        for (int attempt = 1; ; attempt++) {
            RoleResolver.Resolution resolution;
            String pinned = pinFor(stageName, role);
            try {
                resolution = pinned != null
                        ? resolvePinned(pinned, role, user, writers, pinnedByOperator(stageName, role))
                        : new RoleResolver().resolve(role, user.policy(), user.profiles(),
                                writers, rotation, this::available, exhausted,
                                vendorFailedThisDispatch, unavailable);
            } catch (RoleResolver.Unresolvable failure) {
                return unresolved(ledger, role, user, attempts, failure, spent, roleInvocationId,
                        roleFailovers);
            } catch (RuntimeException failure) {
                return unresolved(ledger, role, user, attempts,
                        new RoleResolver.Unresolvable(String.valueOf(failure.getMessage()), Map.of()), spent,
                        roleInvocationId, roleFailovers);
            }

            // The servers a role may reach are the operator's file, resolved against the
            // config home. A missing file is a configuration fault found before anything is
            // spent, not a vendor that failed to answer, so no failover applies.
            Profile chosen = overlay.adapt(stageName, resolution.selected(), user.profiles());
            String overlayProblem = overlay.problem(stageName, resolution.selected(), user.profiles());
            if (overlayProblem != null || exhausted.contains(chosen.name())
                    || unavailable.contains(chosen.name())
                    || vendorFailedThisDispatch.contains(chosen.name())) {
                String why = overlayProblem != null ? "run_override_unavailable"
                        : exhausted.contains(chosen.name()) ? "quota_exhausted_this_run"
                        : unavailable.contains(chosen.name()) ? "vendor_unavailable_this_run"
                        : "vendor_failed_this_attempt";
                String message = overlayProblem != null ? overlayProblem
                        : exhausted.contains(chosen.name())
                        ? "profile exhausted: " + chosen.name()
                        : unavailable.contains(chosen.name())
                        ? "profile unavailable this run: " + chosen.name()
                        : "profile failed this attempt: " + chosen.name();
                return unresolved(ledger, role, user, attempts,
                        new RoleResolver.Unresolvable(message, Map.of(chosen.name(), why)),
                        spent, roleInvocationId, roleFailovers);
            }
            if (!available(chosen)) {
                Map<String, String> rejected = new LinkedHashMap<>(resolution.rejected());
                rejected.put(chosen.name(), "executable_not_found");
                return unresolved(ledger, role, user, attempts,
                        new RoleResolver.Unresolvable("no eligible profile for role " + role
                                + ": " + rejected, rejected), spent, roleInvocationId, roleFailovers);
            }
            Path mcpFile = chosen.mcpConfig() == null ? null
                    : user.resolve(chosen.mcpConfig());
            if (mcpFile != null && !Files.isRegularFile(mcpFile)) {
                Profile named = chosen;
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("message", "profile '" + named.name() + "' names mcp.config "
                        + named.mcpConfig() + ", which does not exist under the config home");
                details.put("resolution", "write the vendor's MCP configuration there, or remove "
                        + "mcp.config and the {{mcp_config}} argument from the profile");
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("role", role);
                missing.put("profile", named.name());
                missing.put("vendor", named.vendor());
                missing.put("ok", false);
                missing.put("code", "role_mcp_config_missing");
                missing.put("role_invocation_id", roleInvocationId);
                ledger.append("role_mcp_config_missing", missing);
                return new Outcome(false, "role_mcp_config_missing", role, named.name(), named.vendor(),
                        resolution.rejected(), null, details);
            }
            Profile profile = mcpFile == null ? chosen
                    : chosen.withMcpConfig(mcpFile.toAbsolutePath().normalize().toString());
            // Published from this resolution, not looked up again: a second resolve would
            // advance rotation and could name a profile that was not dispatched.
            publish(profile);

            // An implementer needs write authority from the task itself, not only from its
            // profile. This is a contract error, not a vendor failure: no failover applies.
            if (!profile.readOnly() && !task.authority().workspaceWrite()) {
                Map<String, Object> details = Map.of(
                        "message", "profile '" + profile.name() + "' writes to the workspace, but the task "
                                + "grants authority.workspace_write: false",
                        "resolution", "either raise the task's authority deliberately, or use a read-only role");
                Map<String, Object> denied = new LinkedHashMap<>();
                denied.put("role", role);
                denied.put("profile", profile.name());
                denied.put("vendor", profile.vendor());
                denied.put("ok", false);
                denied.put("code", "authority_denied");
                denied.put("role_invocation_id", roleInvocationId);
                ledger.append("authority_denied", denied);
                return new Outcome(false, "authority_denied", role, profile.name(), profile.vendor(),
                        resolution.rejected(), null, details);
            }

            boolean inheritsUnfinishedWork = attempt > 1
                    && !git.fingerprint(mergeBase).equals(fingerprintAtStart);
            int evidenceSerial = nextEvidenceSerial++;
            String evidenceName = evidenceSerial <= 1 ? role : role + ".attempt-" + evidenceSerial;
            String reportStem = evidenceDispatch <= 1 ? role : role + ".attempt-" + evidenceDispatch;

            // Where a role that takes its own screenshots puts them. Created only for such a
            // role: the loop believes a picture only if it finds it here afterwards.
            Path evidenceDirectory = ledger.runDirectory().resolve("screenshots");
            if (profile.vision() != null && profile.vision().acquires()) {
                Files.createDirectories(evidenceDirectory);
            }
            Map<String, String> values = promptValues(loaded, task, runId, mergeBase, user, profile,
                    contextFile, inheritsUnfinishedWork, attempts, attachments, evidenceDirectory);

            if (profile.promptTemplate() == null) {
                throw new IllegalStateException("profile '" + profile.name()
                        + "' has no prompt_template; a role with no prompt cannot be run");
            }
            Path templateFile = user.resolve(profile.promptTemplate());
            if (!Files.isRegularFile(templateFile)) {
                throw new IllegalStateException("prompt template not found: " + templateFile);
            }
            String template = Files.readString(templateFile);
            String prompt = PromptRenderer.render(template, values, templateFile.toString());
            boolean backfilled = backfillNeeded(role, task, template);
            if (backfilled) prompt = prompt + browserScenarioSection(values.get("visual_scenarios"));
            if ("api".equals(profile.runner())) {
                try {
                    prompt += "\n\n" + dev.warden.execution.ApiContextBuilder.build(root, mergeBase, processes);
                } catch (java.io.IOException unavailableContext) {
                    Map<String, Object> refusal = new LinkedHashMap<>();
                    refusal.put("ok", false);
                    refusal.put("code", "role_api_context_unavailable");
                    refusal.put("role", role);
                    refusal.put("profile", profile.name());
                    refusal.put("vendor", profile.vendor());
                    refusal.put("vendor_turn_started", false);
                    refusal.put("message", unavailableContext.getMessage());
                    Path path = ledger.writeReport("role-" + role + "-context-refused", refusal);
                    ledger.append("role_api_context_unavailable", refusal);
                    return new Outcome(false, "role_api_context_unavailable", role, profile.name(),
                            profile.vendor(), resolution.rejected(), path, refusal);
                }
            }

            Path promptDirectory = ledger.runDirectory().resolve("prompts");
            Files.createDirectories(promptDirectory);
            Path promptFile = promptDirectory.resolve(evidenceName + ".md");
            Files.writeString(promptFile, prompt, StandardCharsets.UTF_8);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("run_id", runId);
            report.put("role", role);
            report.put("task_id", task.id());
            report.put("profile", profile.name());
            report.put("vendor", profile.vendor());
            report.put("model", profile.model());
            report.put("model_requested", profile.model());
            report.put("effort_requested", profile.effort());
            report.put("workflow_run_id", workflowRunId == null ? runId : workflowRunId);
            report.put("stage", stageName == null ? role : stageName);
            report.put("read_only", profile.readOnly());
            report.put("runner", profile.runner());
            if (profile.harness() != null) {
                report.put("harness", profile.harness());
                report.put("coding_tools_verified", profile.verified());
            }
            // What the roster chose, beside what the overlay made of it. Recorded because a
            // reading is a reading by a named profile on a named channel, and "the operator
            // asked for this stage on Orca" is not visible from the dispatched profile alone.
            Profile requested = resolution.selected();
            if (!requested.name().equals(profile.name())) {
                report.put("profile_overlay", Map.of(
                        "from", requested.name(), "to", profile.name(), "reason", "host"));
            } else if (!requested.runner().equals(profile.runner())) {
                report.put("runner_overlay", profile.runner());
            }
            if (profile.effort() != null && !profile.effort().equals(requested.effort())) {
                report.put("effort_overlay", profile.effort());
            }
            report.put("rotation_counter", rotation);
            report.put("strategy", spec.strategy());
            report.put("independence_required", spec.requireIndependentVendor());
            // The terms this dispatch ran under, so a later run can show they still hold
            // before believing its verdict. See RoleContract.
            report.put("role_contract", RoleContract.of(stageName == null ? role : stageName,
                    role, spec, profile, user));
            report.put("measurement_context", MeasurementContext.snapshot(profile, task, user));
            report.put("role_invocation_id", roleInvocationId);
            // Every vendor that wrote into the candidate, not only the last implementer. The
            // single-valued key is kept for readers of older reports and carries the first.
            report.put("avoided_vendors", List.copyOf(new java.util.TreeSet<>(writers.vendors())));
            report.put("avoided_vendor", writers.vendors().isEmpty() ? null
                    : new java.util.TreeSet<>(writers.vendors()).first());
            report.put("writer_set_known", writers.known());
            // What this resolution may claim about the reader: independent, a declared
            // same-vendor peer, unproven when the writers are unknown, or none for a writer.
            report.put("independence", resolution.assurance());
            if (pinned != null) report.put("assignment", "pinned:" + pinned);
            if (backfilled) report.put("prompt_backfilled", List.of("visual_scenarios"));
            report.put("rejected_profiles", resolution.rejected());
            report.put("prompt_path", root.relativize(promptFile).toString().replace('\\', '/'));
            report.put("prompt_sha256", GitRepository.sha256(promptFile));
            report.put("diff_base_commit", mergeBase);
            report.put("wall_clock_minutes", profile.wallClockMinutes());
            if ("local".equals(profile.runner()) || "api".equals(profile.runner())) {
                report.put("dispatch_preview", dispatchPreview(profile));
            } else if (profile.harness() != null) {
                report.put("connection", profile.connectionRef());
                report.put("command_preview", profile.harnessExecutable() == null
                        ? List.of("opencode", "run", "--format", "json")
                        : List.of(profile.harnessExecutable().toString(), "run", "--pure", "--format", "json"));
            } else if ("orca".equals(profile.runner())) {
                List<String> preview = new ArrayList<>(List.of("orca", "orchestration", "worker-start",
                        "--task", "<orca-task-id>", "--worktree", "<current-worktree>"));
                preview.addAll(dev.warden.execution.orca.OrcaLaunch.arguments(profile));
                report.put("command_preview", preview);
                report.put("launch_contract", dev.warden.execution.orca.OrcaLaunch.contract(profile));
            } else {
                report.put("command_preview", commandPreview(profile));
            }
            report.put("attachment_count", (long) (attachments == null ? 0 : attachments.size()));
            if (profile.vision() != null) {
                report.put("vision_capability", Map.of(
                        "verified", profile.hasVerifiedVision(),
                        "delivery", profile.vision().delivery()));
            }
            boolean opensFilesItself = profile.vision() != null
                    && "workspace_file".equals(profile.vision().delivery());
            if (attachments != null && !attachments.isEmpty()
                    && profile.attachmentFlag() == null && !opensFilesItself) {
                // Not fatal: the paths are in the prompt and an agentic vendor can open them.
                // Recorded because "the reviewer looked at the screenshots" and "the reviewer
                // was told where the screenshots are" are different claims. A profile that
                // declares `vision.delivery: workspace_file` has made the second claim on
                // purpose, so warning about it would be noise, not a finding.
                report.put("attachments_not_passed_to_vendor",
                        "profile '" + profile.name() + "' declares no attachments.flag and no "
                                + "workspace_file vision, so the images reach it only as paths "
                                + "inside the prompt");
            }
            report.put("vendor_attempt", (long) attempt);
            if (!rateLimitPauses.isEmpty()) report.put("rate_limit_retry", List.copyOf(rateLimitPauses));

            if (dryRun) {
                report.put("dry_run", true);
                report.put("ok", true);
                report.put("view_state", "dry_run");
                dev.warden.dashboard.RoleView.update(ledger.runDirectory(), evidenceName, report);
                Path path = ledger.writeReport("role-" + role + "-dryrun", report);
                ledger.append("role_dry_run", Map.of("role", role, "profile", profile.name(),
                        "vendor", profile.vendor()));
                return new Outcome(true, "dry_run", role, profile.name(), profile.vendor(),
                        resolution.rejected(), path, report);
            }

            // Budget is checked here — after the routing decision, before anything is spent.
            // The home corpus is the other choke point: a measurement that cannot be saved
            // refuses a new paid dispatch. Settlement of a worker already running is past
            // this line and is never blocked for analytics.
            if (!"orca".equals(profile.runner())) {
                dev.warden.execution.WorktreeLease.requireLocalDispatch(root);
            }
            if (user.home() != null) HomeCorpus.requireDispatch(user.home(), root);
            gate.reserve(profile.maxCostUsd());
            gate.requireDispatch();
            java.time.Duration cap = gate.wallClockCap();
            if (cap != null && cap.toMinutes() < profile.wallClockMinutes()) {
                // Whole minutes, because that is the unit a profile declares; a cap under one
                // minute is refused by the gate before it gets here. The call may therefore
                // outlast the deadline by less than a minute, and the report says so.
                report.put("profile_wall_clock_minutes", profile.wallClockMinutes());
                profile = profile.withWallClockMinutes(Math.max(1, cap.toMinutes()));
                report.put("wall_clock_minutes", profile.wallClockMinutes());
                report.put("wall_clock_capped_by", "max_elapsed_minutes");
            }
            report.put("view_state", "running");
            report.put("controller_pid", ProcessHandle.current().pid());
            report.put("controller_started_at", ProcessHandle.current().info().startInstant()
                    .map(java.time.Instant::toString).orElse(null));
            dev.warden.dashboard.RoleView.update(ledger.runDirectory(), evidenceName, report);

            // Said before the call, not after it: this is the line an operator reads while a
            // vendor is busy for ten minutes, and "which model is working right now" is the
            // question the silence was hiding.
            progress.line("      " + profile.name() + "  " + profile.vendor()
                    + (profile.model() == null ? "" : "/" + profile.model())
                    + (profile.effort() == null ? "" : " effort=" + profile.effort())
                    + (attempt > 1 ? "  (vendor attempt " + attempt + ")" : "")
                    + "  dispatching, up to " + profile.wallClockMinutes() + " min");

            if (recordConfirmedSwitch(ledger, role, roleInvocationId, stageKey, profile, roleFailovers)
                    && countsAsVendorFailover(confirmedVendorSwitches.get(stageKey).get("cause"))) {
                // The substitution a person approved is one use of the stage's bound.
                // It is counted when that profile is dispatched, before its result, so a
                // failure of the successor cannot spend another switch the policy did not allow.
                vendorFailovers++;
            }

            Path schemaFile = profile.jsonSchema() == null ? null : user.resolve(profile.jsonSchema());
            Set<String> connectionVariables = user.connections().values().stream()
                    .map(dev.warden.config.Connection::apiKeyEnv).filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet());
            // Registration may change in the panel while a long-running loop holds its
            // initial user snapshot. Remove both old and newly registered names.
            if (user.home() != null) {
                Path connectionDirectory = user.home().resolve("connections");
                if (!Files.exists(connectionDirectory) || Files.isDirectory(connectionDirectory)) {
                    new dev.warden.config.ConnectionStore(user.home()).load().connections().values().stream()
                            .map(dev.warden.config.Connection::apiKeyEnv).filter(java.util.Objects::nonNull)
                            .forEach(connectionVariables::add);
                }
            }
            RoleExecutor executor = executors.forProfile(profile, processes.withoutEnvironment(connectionVariables), git);
            RoleExecutor.Result result = executor.execute(new RoleExecutor.Request(
                    runId, workflowRunId != null ? workflowRunId : runId,
                    role, profile, task, mergeBase, root, ledger.runDirectory(), promptFile, schemaFile,
                    values.get("context"), evidenceName, attachments == null ? List.of() : attachments));

            if (Boolean.FALSE.equals(result.evidence().get("process_tree_settled"))
                    && !"role_process_unsettled".equals(result.code())) {
                Map<String, Object> unsettled = new LinkedHashMap<>(result.evidence());
                unsettled.put("adapter_code", result.code());
                unsettled.put("failure", "role_process_unsettled");
                result = new RoleExecutor.Result(false, "role_process_unsettled", result.duration(),
                        result.rawOutput(), null, unsettled);
            }
            report.put("dry_run", false);
            report.put("ok", result.ok());
            report.put("code", result.code());
            report.put("duration_millis", result.duration().toMillis());
            report.putAll(result.evidence());
            report.put("measurement_context", MeasurementContext.withReported(
                    asMap(report.get("measurement_context")), result));
            try {
                report.put("candidate_fingerprint", git.sourceFingerprint(mergeBase));
            } catch (Exception ignored) {
                // A fingerprint that cannot be taken stays unknown rather than becoming a guess.
            }
            report.put("view_state", result.ok() ? "completed"
                    : "role_human_input_required".equals(result.code()) ? "needs_you" : "failed");
            dev.warden.dashboard.RoleView.update(ledger.runDirectory(), evidenceName, report);
            if (inheritsUnfinishedWork) {
                report.put("inherited_unfinished_work", true);
            }

            if (result.artifact() != null) {
                List<Findings.Finding> found = Findings.acknowledging(
                        Findings.of(result.artifact()), task.manualVerification());
                if (!found.isEmpty()) {
                    report.put("findings", found.stream().map(Findings.Finding::toMap).toList());
                }
            }

            Map<String, Object> attemptRow = attemptRecord(attempt, profile, result, inheritsUnfinishedWork);
            // Still an attempt in every record; only the budget gives the call back.
            boolean charged = !Boolean.FALSE.equals(result.evidence().get("vendor_turn_started"));
            if (!charged) {
                attemptRow.put("budget_charged", false);
                report.put("budget_charged", false);
            }
            attempts.add(attemptRow);
            if (report.get("cost_usd") instanceof Number number) spent += number.doubleValue();
            journalAttempt(ledger, role, roleInvocationId, report, attemptRow);
            if (charged) gate.settleAttempt(report.get("cost_usd"), profile.maxCostUsd());
            else gate.release();

            // A transient rate limit is the one failure worth asking the same vendor again
            // for, and only when the policy said how often. The wait honours the seconds the
            // vendor named when it named any, never a clock time it printed without a date.
            // A second paid writer is forbidden while the previous adapter cannot prove
            // its process/capture settlement, regardless of quota or failover policy.
            if (Boolean.FALSE.equals(result.evidence().get("process_tree_settled"))) {
                dev.warden.execution.WorktreeLease.requireRecovery("previous vendor process did not settle");
                report.put("failure", "role_process_unsettled");
                report.put("vendor_attempts", attempts);
                report.put("attempts_cost_usd", spent);
                rememberFailovers(report, roleFailovers);
                Path path = ledger.writeReport("role-" + reportStem, report);
                ledger.append("role_run", report);
                return new Outcome(false, "role_process_unsettled", role, profile.name(),
                        profile.vendor(), resolution.rejected(), path, report);
            }
            Policy.RateLimitRetry retry = user.policy().rateLimitRetry();
            if ("role_rate_limited".equals(result.code()) && rateLimitRetries < retry.maxAttempts()
                    && gate.hasRoom()) {
                long pause = retryPauseMillis(result.evidence(), retry, rateLimitRetries + 1);
                try {
                    gate.requireRetryWait(pause);
                } catch (RuntimeException declined) {
                    // The completed attempt remains a complete role record even though a
                    // deadline refusal must reach the loop with its original classification.
                    report.put("rate_limit_retry_declined", Map.of(
                            "attempt", (long) rateLimitRetries + 1,
                            "requested_pause_millis", pause,
                            "reason", String.valueOf(declined.getMessage())));
                    report.put("vendor_attempts", attempts);
                    report.put("attempts_cost_usd", spent);
                    ledger.writeReport("role-" + reportStem, report);
                    ledger.append("role_run", report);
                    throw declined;
                }
                rateLimitRetries++;
                rateLimitPauses.add(Map.of("attempt", (long) rateLimitRetries,
                        "of", retry.maxAttempts(), "paused_millis", pause));
                report.put("rate_limit_retry", List.copyOf(rateLimitPauses));
                progress.line("      " + profile.name() + " was rate limited; waiting "
                        + (pause / 1000) + " s, then retrying (" + rateLimitRetries + " of "
                        + retry.maxAttempts() + ")");
                sleeper.accept(pause);
                continue;
            }
            boolean quota = "role_quota_exhausted".equals(result.code());
            if (quota) {
                exhausted.add(profile.name());
                Map<String, Object> quotaEvent = new LinkedHashMap<>();
                quotaEvent.put("role", role);
                quotaEvent.put("profile", profile.name());
                quotaEvent.put("vendor", profile.vendor());
                quotaEvent.put("role_invocation_id", roleInvocationId);
                quotaEvent.put("quota", result.evidence().getOrDefault("quota", Map.of()));
                ledger.append("role_quota_exhausted", quotaEvent);
                Profile candidate = apiFallbackActive
                        ? seekApiSuccessor(role, user, writers, vendorFailedThisDispatch).profile()
                        : failoverCandidate(role, user, writers, rotation);
                if (candidate != null) {
                    String mode = user.policy().failoverMode();
                    boolean preAuthorized = candidate.name().equals(authorizedFailover.get(
                            stageName == null ? role : stageName))
                            || candidate.name().equals(authorizedFailover.get(role));
                    if ("auto".equals(mode) || preAuthorized) {
                        if (!gate.hasRoom()) {
                            // A one-call bound (the planner bootstrap) cannot spend a second
                            // vendor. Return the spent-subscription outcome rather than
                            // throwing from the next iteration, so the first call is still
                            // a role_run with its cost attached.
                            report.put("failover_declined_by_budget", candidate.name());
                            report.put("exhausted_profiles", List.copyOf(exhausted));
                            report.put("resolution", "another profile could fill this role, but "
                                    + "no further vendor dispatch is allowed under the budget "
                                    + "in force");
                            report.put("vendor_attempts", attempts);
                            report.put("attempts_cost_usd", spent);
                            rememberFailovers(report, roleFailovers);
                            Path path = ledger.writeReport("role-" + reportStem, report);
                            ledger.append("role_run", report);
                            return new Outcome(false, result.code(), role, profile.name(),
                                    profile.vendor(), resolution.rejected(), path, report);
                        }
                        // Switching vendors mid-role changes who wrote the work, so it is an
                        // event in its own right rather than a line in a report nobody reads.
                        // It joins the same list a vendor-failure switch and a confirmed
                        // switch join, so a continuation that already recorded A→B and then
                        // switches B→C on quota counts both.
                        Map<String, Object> failover = failoverEvent(role, profile, candidate,
                                result, preAuthorized ? "human_switch_decision" : "policy_auto");
                        if (apiFallbackActive) failover.put("api_fallback_profiles", spec.apiFallbackProfiles());
                        failover.put("role_invocation_id", roleInvocationId);
                        ledger.append("role_failover", failover);
                        roleFailovers.add(failover);
                        replacements.put(stageName == null ? role : stageName, candidate.name());
                        continue;
                    }
                    if ("confirm".equals(mode)) {
                        Map<String, Object> pending = failoverEvent(role, profile, candidate,
                                result, "pending_human_confirmation");
                        if (apiFallbackActive) pending.put("api_fallback_profiles", spec.apiFallbackProfiles());
                        pending.put("independence_after_switch",
                                independenceNote(candidate, writers));
                        pending.put("stage", stageName == null ? role : stageName);
                        pending.put("exhausted_profiles", List.copyOf(exhausted));
                        // An automatic vendor-failure switch earlier in this dispatch is not a
                        // spent subscription, so it is not in exhausted_profiles. The
                        // continuation still has to keep that profile out of this stage:
                        // strategy `first` would pay it again, ahead of the profile a person
                        // is about to approve.
                        if (!vendorFailedThisDispatch.isEmpty()) {
                            pending.put("vendor_failed_profiles", List.copyOf(vendorFailedThisDispatch));
                        }
                        // Substitutions already used on this stage. The continuation starts
                        // from this number; the quota switch itself is not one of them.
                        pending.put("vendor_failovers", (long) vendorFailovers);
                        pending.put("to_model", candidate.model());
                        pending.put("to_runner", candidate.runner());
                        pending.put("to_effort", candidate.effort());
                        report.put("failover_pending", pending);
                        report.put("vendor_attempts", attempts);
                        report.put("attempts_cost_usd", spent);
                        rememberFailovers(report, roleFailovers);
                        report.put("resolution", "policy failover.on_quota_exhausted is 'confirm'. "
                                + "Record the choice with `warden approve <run-id> --decision switch`, "
                                + "then re-run with `--continue <run-id>`; or set "
                                + "failover.on_quota_exhausted: auto to let Warden switch unattended.");
                        Path pendingPath = ledger.writeReport("role-" + reportStem, report);
                        ledger.append("role_run", report);
                        return new Outcome(false, "role_failover_requires_confirmation", role,
                                profile.name(), profile.vendor(), resolution.rejected(),
                                pendingPath, report);
                    }
                    // mode 'stop': a candidate exists and is deliberately not used.
                    report.put("failover_declined_by_policy", candidate.name());
                }
                // Last vendor standing, or a policy that forbids the switch. The report has to
                // carry the operator's next move: this is where the run ends and nothing
                // downstream will add it.
                report.put("exhausted_profiles", List.copyOf(exhausted));
                report.put("resolution", candidate != null
                        ? "another profile could fill this role, but failover.on_quota_exhausted is 'stop'"
                        : "every profile able to fill this role reported a spent subscription; "
                          + "add a profile from another vendor, or wait for the quota window "
                          + "named in the vendor message and re-run");
            } else if (!result.ok() && ((transientApiFailure(result.code())
                        && !Thread.currentThread().isInterrupted()
                        && !"cancelled".equals(result.evidence().get("api_retry_stopped"))
                        && !"cancel".equals(result.evidence().get("failure_phase")))
                    || (vendorFailure(result.code()) && !"api".equals(profile.runner())
                        && profile.harness() == null))) {
                // A finding is a successful call. A gate failure and a human rejection never
                // enter this method. This branch is only a call that left nothing a later
                // stage can believe: the vendor did not answer, or the answer was not an artifact.
                vendorFailedThisDispatch.add(profile.name());
                if ("role_vendor_unavailable".equals(result.code())) {
                    // Stable for this process, unlike a dropped connection. Not quota: a
                    // different profile of the same vendor may still run. Cleared with the
                    // process, not with this dispatch.
                    unavailable.add(profile.name());
                }
                int maxSwitches = user.policy().maxVendorFailovers();
                String mode = user.policy().vendorFailureModeFor(role);
                boolean bounded = vendorFailovers >= maxSwitches;
                apiFallbackActive |= transientApiFailure(result.code());
                Successor next = apiFallbackActive
                        ? seekApiSuccessor(role, user, writers, vendorFailedThisDispatch)
                        : seekSuccessor(role, user, writers, rotation, vendorFailedThisDispatch);
                Profile candidate = next.profile();
                boolean preAuthorized = candidate != null && (candidate.name().equals(
                        authorizedFailover.get(stageKey))
                        || candidate.name().equals(authorizedFailover.get(role)));
                String cause = vendorFailureCause(result.code());
                String setting = spec.vendorFailureMode() != null
                        ? "roles." + role + ".on_vendor_failure" : "failover.on_vendor_failure";
                if (apiFallbackActive) {
                    report.put("api_fallback_profiles", spec.apiFallbackProfiles());
                }
                if (candidate != null && !bounded && ("auto".equals(mode) || preAuthorized)) {
                    if (!gate.hasRoom()) {
                        report.put("failover_declined_by_budget", candidate.name());
                        report.put("resolution", "another profile could fill this role, but "
                                + "no further vendor dispatch is allowed under the budget "
                                + "in force");
                        report.put("vendor_attempts", attempts);
                        report.put("attempts_cost_usd", spent);
                        rememberFailovers(report, roleFailovers);
                        Path path = ledger.writeReport("role-" + reportStem, report);
                        ledger.append("role_run", report);
                        return new Outcome(false, result.code(), role, profile.name(),
                                profile.vendor(), resolution.rejected(), path, report);
                    }
                    Map<String, Object> failover = failoverEvent(role, profile, candidate, result,
                            preAuthorized ? "human_switch_decision" : "policy_auto", cause, setting);
                    if (apiFallbackActive) failover.put("api_fallback_profiles", spec.apiFallbackProfiles());
                    failover.put("role_invocation_id", roleInvocationId);
                    ledger.append("role_failover", failover);
                    roleFailovers.add(failover);
                    replacements.put(stageKey, candidate.name());
                    vendorFailovers++;
                    progress.line("      " + profile.name() + " produced no artifact ("
                            + cause + "); switching to " + candidate.name());
                    continue;
                }
                if (candidate != null && !bounded && "confirm".equals(mode)) {
                    Map<String, Object> pending = failoverEvent(role, profile, candidate, result,
                            "pending_human_confirmation", cause, setting);
                    if (apiFallbackActive) pending.put("api_fallback_profiles", spec.apiFallbackProfiles());
                    pending.put("independence_after_switch", independenceNote(candidate, writers));
                    pending.put("stage", stageKey);
                    // Every profile that already failed this stage, not only the last one.
                    // Live --continue excludes the whole set, so the roster cannot cycle
                    // back to an earlier failure. They are not added to the process-lifetime
                    // exhausted set: a later stage in this process may still try the profile.
                    // vendor_failovers is how many substitutions are already spent, not
                    // counting the one this question is asking about.
                    pending.put("exhausted_profiles", List.copyOf(vendorFailedThisDispatch));
                    pending.put("vendor_failovers", (long) vendorFailovers);
                    // A spent subscription is not one of those failures. It lives only in
                    // the process-lifetime exhausted set, which dies with this process, so
                    // a continuation would otherwise offer it again (A spends its quota,
                    // B returns nothing, a person confirms C, C fails, A is proposed).
                    // The continuation restores the list with excluding(). A record written
                    // before this field existed has no list, and that means none.
                    if (!exhausted.isEmpty()) {
                        pending.put("quota_exhausted_profiles", List.copyOf(exhausted));
                    }
                    pending.put("to_model", candidate.model());
                    pending.put("to_runner", candidate.runner());
                    pending.put("to_effort", candidate.effort());
                    report.put("failover_pending", pending);
                    report.put("vendor_attempts", attempts);
                    report.put("attempts_cost_usd", spent);
                    rememberFailovers(report, roleFailovers);
                    report.put("resolution", "policy failover.on_vendor_failure is 'confirm'. "
                            + "Record the choice with `warden approve <run-id> --decision switch`, "
                            + "then re-run with `--continue <run-id>`; or set "
                            + "failover.on_vendor_failure: auto to let Warden switch unattended.");
                    Path pendingPath = ledger.writeReport("role-" + reportStem, report);
                    ledger.append("role_run", report);
                    return new Outcome(false, "role_failover_requires_confirmation", role,
                            profile.name(), profile.vendor(), resolution.rejected(),
                            pendingPath, report);
                }
                if (candidate != null && bounded && !"stop".equals(mode)) {
                    report.put("failover_bound", candidate.name());
                    report.put("resolution", "another profile could fill this role, but "
                            + "failover.max_per_stage (" + maxSwitches + ") already allowed "
                            + vendorFailovers + " substitution(s) on this attempt");
                } else if (candidate != null) {
                    report.put("failover_declined_by_policy", candidate.name());
                    report.put("resolution", "another profile could fill this role, but "
                            + setting + " is 'stop'");
                } else if (!next.rejected().isEmpty()) {
                    report.put("failover_rejected", next.rejected());
                    report.put("resolution", "no other profile can fill this role: "
                            + next.rejected());
                } else {
                    report.put("resolution", apiFallbackActive
                            ? "no eligible profile remains in roles." + role + ".api_fallback_profiles"
                            : "no other profile can fill this role");
                }
            }

            if (result.ok() && result.artifact() != null) {
                Path artifactDirectory = ledger.runDirectory().resolve("artifacts");
                Files.createDirectories(artifactDirectory);
                Path artifactFile = artifactDirectory.resolve(role + ".json");
                Files.writeString(artifactFile, Json.writePretty(result.artifact()) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
                report.put("artifact_path", root.relativize(artifactFile).toString().replace('\\', '/'));
                report.put("artifact_sha256", GitRepository.sha256(artifactFile));
            }

            report.put("vendor_attempts", attempts);
            report.put("attempts_cost_usd", spent);
            rememberFailovers(report, roleFailovers);
            if (attempts.size() > 1) {
                report.put("failed_over_from", attempts.stream()
                        .limit(attempts.size() - 1L)
                        .map(entry -> String.valueOf(entry.get("profile"))).toList());
            }

            Path path = ledger.writeReport("role-" + reportStem, report);
            ledger.append("role_run", report);
            return new Outcome(result.ok(), result.code(), role, profile.name(), profile.vendor(),
                    resolution.rejected(), path, report);
        }
    }

    /**
     * How long to pause before a rate-limit retry: the seconds the vendor asked for when its
     * message carried a plain `retry after N seconds` or `Retry-After: N`, else the policy's
     * backoff doubled per attempt. Bounded to fifteen minutes either way, and a time of day the
     * vendor printed without a date is never parsed into an instant.
     */
    public static long retryPauseMillis(Map<String, Object> evidence, Policy.RateLimitRetry retry, int attempt) {
        long seconds = -1;
        if (evidence != null && evidence.get("quota") instanceof Map<?, ?> quota
                && quota.get("vendor_message") instanceof String message) {
            java.util.regex.Matcher named = java.util.regex.Pattern
                    .compile("(?i)retry[- ]after:?\\s*(\\d{1,4})\\s*(s|sec|seconds?)?\\b")
                    .matcher(message);
            if (named.find()) seconds = Long.parseLong(named.group(1));
        }
        if (seconds < 0) seconds = retry.backoffSeconds() * (1L << Math.max(0, attempt - 1));
        return Math.min(seconds, 900L) * 1000L;
    }

    /**
     * Which profile could fill this role once the exhausted ones are removed, or null when
     * none can. Asked before continuing so that the last vendor's failure is reported as its
     * own outcome rather than as an unresolvable role, and so that an operator being asked to
     * confirm a switch is told who would take over.
     *
     * Profiles that already returned no artifact in this dispatch are excluded. Naming one
     * of them would record a switch to a profile the next iteration cannot run.
     */
    private Profile failoverCandidate(String role, UserConfig user, RoleResolver.Writers writers,
                                      long rotation) {
        return seekSuccessor(role, user, writers, rotation, vendorFailedThisDispatch).profile();
    }

    /**
     * The next profile that could take this role, and why the others cannot.
     *
     * {@code vendorFailed} names profiles that already returned no artifact in this
     * dispatch. They are skipped with their own reason, not labelled as a spent plan.
     */
    private Successor seekSuccessor(String role, UserConfig user, RoleResolver.Writers writers,
                                    long rotation, Set<String> vendorFailed) {
        try {
            Profile candidate = new RoleResolver().resolve(role, user.policy(), user.profiles(),
                    writers, rotation, profile -> {
                        if (overlay.problem(stageName, profile, user.profiles()) != null) return false;
                        Profile effective = overlay.adapt(stageName, profile, user.profiles());
                        return !exhausted.contains(effective.name())
                                && !unavailable.contains(effective.name())
                                && !vendorFailed.contains(effective.name())
                                && available(effective);
                    }, exhausted, vendorFailed, unavailable).selected();
            return new Successor(overlay.adapt(stageName, candidate, user.profiles()), Map.of());
        } catch (RoleResolver.Unresolvable none) {
            return new Successor(null, none.rejected());
        } catch (RuntimeException none) {
            return new Successor(null, Map.of());
        }
    }

    private record Successor(Profile profile, Map<String, String> rejected) {}

    /** Explicit API fallback order, filtered through the ordinary admission rules. */
    private Successor seekApiSuccessor(String role, UserConfig user, RoleResolver.Writers writers,
                                      Set<String> vendorFailed) {
        List<RoleResolver.Candidate> candidates = new RoleResolver().candidates(role,
                user.policy(), user.profiles(), writers, profile -> {
                    if (overlay.problem(stageName, profile, user.profiles()) != null) return false;
                    return available(overlay.adapt(stageName, profile, user.profiles()));
                }, exhausted, vendorFailed, unavailable);
        Map<String, RoleResolver.Candidate> byName = new LinkedHashMap<>();
        candidates.forEach(candidate -> byName.put(candidate.profile(), candidate));
        Map<String, String> rejected = new LinkedHashMap<>();
        for (String name : user.policy().roles().get(role).apiFallbackProfiles()) {
            RoleResolver.Candidate candidate = byName.get(name);
            if (candidate == null) rejected.put(name, "profile_not_in_roster");
            else if (!candidate.eligible()) rejected.put(name, candidate.rejected());
            else return new Successor(overlay.adapt(stageName, user.profiles().get(name),
                    user.profiles()), rejected);
        }
        return new Successor(null, rejected);
    }

    // Auth, identity, truncation, parse, cancellation and admission/configuration errors
    // are deliberately absent. A different provider cannot repair those safely.
    private static boolean transientApiFailure(String code) {
        return code != null && Set.of("role_api_network_error", "role_api_timeout",
                "role_api_rate_limited", "role_api_provider_error").contains(code);
    }

    /**
     * Codes that mean the vendor produced nothing a later stage can believe.
     *
     * A timeout that already wrote a valid artifact does not arrive here: the executor
     * returns ok and leaves {@code timed_out} on the attempt. {@code role_timeout} stays
     * in this set for the call that left no complete artifact.
     */
    private static final Set<String> VENDOR_FAILURE_CODES = Set.of(
            "role_command_failed",
            "role_timeout",
            "role_artifact_unparseable",
            "role_artifact_incomplete",
            "role_artifact_schema_violation",
            "role_local_endpoint_unreachable",
            "role_local_http_error",
            "role_vendor_unavailable");

    private static boolean vendorFailure(String code) {
        // Harness admission/configuration/identity failures describe the execution boundary.
        // Paying another writer cannot repair it and may obscure edits from earlier turns.
        if (code == null || code.startsWith("role_harness_")
                || "harness_coding_unproven".equals(code)) return false;
        return VENDOR_FAILURE_CODES.contains(code);
    }

    /**
     * The ledger {@code cause} for a missing artifact. The role's own code stays on
     * {@code role_code}, so a network failure and a schema failure are not collapsed
     * into one token where the evidence is read.
     */
    private static String vendorFailureCause(String code) {
        return switch (code) {
            case "role_command_failed", "role_local_endpoint_unreachable",
                    "role_local_http_error", "role_api_network_error",
                    "role_api_provider_error", "role_api_rate_limited" -> "vendor_call_failed";
            case "role_timeout", "role_api_timeout" -> "role_timeout";
            case "role_artifact_unparseable", "role_artifact_incomplete",
                    "role_artifact_schema_violation" -> "artifact_invalid";
            case "role_vendor_unavailable" -> "vendor_unavailable";
            default -> code;
        };
    }

    /**
     * The switch a person approved, written when that profile is the one about to run.
     *
     * {@code authorized_by} is {@code human} and {@code policy_setting} is {@code confirm}:
     * the policy asked, and the continuation is the answer. Rotation is not consulted.
     *
     * @return whether this call wrote the switch. A repeat dispatch of the stage does not
     *         write it again. The caller decides whether the cause counts against
     *         {@code max_per_stage}; a spent subscription does not.
     */
    private boolean recordConfirmedSwitch(EvidenceLedger ledger, String role, String roleInvocationId,
                                       String stageKey, Profile chosen,
                                       List<Map<String, Object>> roleFailovers) throws Exception {
        Map<String, Object> pending = confirmedVendorSwitches.get(stageKey);
        if (pending == null || confirmedSwitchRecorded.contains(stageKey)) return false;
        if (!chosen.name().equals(String.valueOf(pending.get("to_profile")))) return false;
        confirmedSwitchRecorded.add(stageKey);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("role", role);
        event.put("from_profile", pending.get("from_profile"));
        event.put("from_vendor", pending.get("from_vendor"));
        event.put("to_profile", pending.get("to_profile"));
        event.put("to_vendor", pending.get("to_vendor"));
        event.put("from", pending.get("from_profile"));
        event.put("to", pending.get("to_profile"));
        event.put("cause", pending.get("cause"));
        event.put("authorized_by", "human");
        event.put("policy_setting", "confirm");
        if (pending.get("role_code") != null) event.put("role_code", pending.get("role_code"));
        if (pending.get("api_fallback_profiles") != null) {
            event.put("api_fallback_profiles", pending.get("api_fallback_profiles"));
        }
        event.put("role_invocation_id", roleInvocationId);
        ledger.append("role_failover", event);
        roleFailovers.add(event);
        return true;
    }

    /** A missing artifact, as opposed to a spent subscription. The bound counts only these. */
    private static boolean countsAsVendorFailover(Object cause) {
        String value = cause == null ? "" : String.valueOf(cause);
        return "vendor_call_failed".equals(value)
                || "role_timeout".equals(value)
                || "artifact_invalid".equals(value)
                || "vendor_unavailable".equals(value);
    }

    private static void rememberFailovers(Map<String, Object> report,
                                          List<Map<String, Object>> roleFailovers) {
        if (!roleFailovers.isEmpty()) report.put("role_failovers", List.copyOf(roleFailovers));
    }

    private static Map<String, Object> failoverEvent(String role, Profile from, Profile to,
                                                     RoleExecutor.Result result, String authorizedBy) {
        return failoverEvent(role, from, to, result, authorizedBy, "role_quota_exhausted", null);
    }

    private static Map<String, Object> failoverEvent(String role, Profile from, Profile to,
                                                     RoleExecutor.Result result, String authorizedBy,
                                                     String cause, String policySetting) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("role", role);
        event.put("from_profile", from.name());
        event.put("from_vendor", from.vendor());
        event.put("to_profile", to.name());
        event.put("to_vendor", to.vendor());
        event.put("from", from.name());
        event.put("to", to.name());
        event.put("cause", cause);
        event.put("authorized_by", authorizedBy);
        if ("role_quota_exhausted".equals(cause)) {
            event.put("quota", result.evidence().getOrDefault("quota", Map.of()));
        } else {
            event.put("role_code", result.code());
            if (policySetting != null) event.put("policy_setting", policySetting);
        }
        return event;
    }

    /**
     * Two vendors and one spent subscription leaves one vendor. The resolver already refuses
     * a reviewer sharing the implementer's vendor, so a switch cannot silently produce
     * self-review — but the operator confirming it should be told the roster is about to get
     * thin, before the next role fails to resolve.
     */
    private static String independenceNote(Profile candidate, RoleResolver.Writers writers) {
        if (writers.vendors().isEmpty()) {
            return "no writer has run yet in this workflow, so independence is unaffected";
        }
        String wrote = String.join(", ", new java.util.TreeSet<>(writers.vendors()));
        if (writers.contains(candidate.vendor())) {
            return "the candidate shares a vendor with a writer of this candidate (" + wrote
                    + "); a role that requires an independent vendor will refuse it";
        }
        return "the candidate differs from every vendor that wrote this candidate (" + wrote
                + "), so independence is preserved";
    }

    /**
     * No profile could fill the role. When the reason is that every candidate ran out, that is
     * reported as its own code: "nothing is configured" and "everything is spent" call for
     * opposite responses from an operator, and one of them resolves itself with time.
     */
    private Outcome unresolved(EvidenceLedger ledger, String role, UserConfig user,
                               List<Map<String, Object>> attempts, RoleResolver.Unresolvable failure,
                               double spent, String roleInvocationId,
                               List<Map<String, Object>> roleFailovers) throws Exception {
        // Attempts from a missing artifact must not be retold as a spent subscription.
        // Every other non-empty attempt list keeps the old reading: the role was already
        // failing over from quota when resolution gave out.
        boolean sawVendorFailure = attempts.stream()
                .anyMatch(row -> vendorFailure(String.valueOf(row.get("code")))
                        || transientApiFailure(String.valueOf(row.get("code"))));
        boolean quota = !sawVendorFailure && (!attempts.isEmpty()
                || failure.rejected().containsValue("quota_exhausted_this_run"));
        String code = quota ? "role_quota_exhausted"
                : sawVendorFailure ? String.valueOf(attempts.get(attempts.size() - 1).get("code"))
                : "role_unresolved";

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("message", String.valueOf(failure.getMessage()));
        details.put("known_profiles", List.copyOf(user.profiles().keySet()));
        details.put("profile_problems", user.problems());
        details.put("rejected_profiles", failure.rejected());
        if (!attempts.isEmpty()) {
            details.put("vendor_attempts", attempts);
            details.put("attempts_cost_usd", spent);
            rememberFailovers(details, roleFailovers);
            if (quota) {
                details.put("exhausted_profiles", List.copyOf(exhausted));
                details.put("resolution", "every profile able to fill this role reported a spent "
                        + "subscription; add a profile from another vendor, or wait for the quota "
                        + "window named in the vendor message and re-run");
            } else {
                details.put("resolution", String.valueOf(failure.getMessage()));
            }
        }
        Map<String, Object> unresolved = new LinkedHashMap<>();
        unresolved.put("role", role);
        unresolved.put("role_invocation_id", roleInvocationId);
        unresolved.put("message", String.valueOf(failure.getMessage()));
        ledger.append(code, unresolved);
        return new Outcome(false, code, role, null, null, failure.rejected(), null, details);
    }

    private void journalAttempt(EvidenceLedger ledger, String role, String roleInvocationId,
                                Map<String, Object> report, Map<String, Object> attempt)
            throws Exception {
        Map<String, Object> event = new LinkedHashMap<>(attempt);
        event.put("role", role);
        event.put("stage", report.get("stage"));
        event.put("task_id", report.get("task_id"));
        event.put("workflow_run_id", report.get("workflow_run_id"));
        event.put("role_invocation_id", roleInvocationId);
        event.put("vendor_attempt", attempt.get("attempt"));
        event.put("measurement_context", report.get("measurement_context"));
        event.put("role_contract", report.get("role_contract"));
        if (report.get("findings") != null) event.put("findings", report.get("findings"));
        ledger.append("vendor_attempt", event);
    }

    private Map<String, Object> attemptRecord(int attempt, Profile profile, RoleExecutor.Result result,
                                              boolean inheritsUnfinishedWork) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("attempt", (long) attempt);
        entry.put("vendor_attempt_id", java.util.UUID.randomUUID().toString());
        entry.put("profile", profile.name());
        entry.put("vendor", profile.vendor());
        entry.put("runner", profile.runner());
        entry.put("effort_requested", profile.effort());
        entry.put("model_requested", profile.model());
        for (String key : List.of("effort_transmitted", "effort_reported", "effort_delivery",
                "effort_transmission", "effort_verification")) {
            if (result.evidence().containsKey(key)) entry.put(key, result.evidence().get(key));
        }
        if (result.evidence().containsKey("launch")) entry.put("launch", result.evidence().get("launch"));
        Object reportedModel = result.evidence().get("model_reported");
        if (reportedModel instanceof String value && !value.isBlank()) {
            entry.put("model_reported", value);
            entry.put("model", value);
        }
        entry.put("ok", result.ok());
        entry.put("code", result.code());
        entry.put("duration_millis", result.duration().toMillis());
        Object cost = result.evidence().get("cost_usd");
        if (cost != null) entry.put("cost_usd", cost);
        Object tokens = result.evidence().get("tokens");
        if (tokens != null) entry.put("tokens", tokens);
        // A later success replaces the top-level API evidence. Keep each failed endpoint's
        // structured receipt with its own attempt, including unknown cost and delivery.
        for (String key : List.of("connection", "connection_fingerprint", "provider",
                "model_requested", "model_reported", "model_served", "correlation_id",
                "api_attempts", "api_attempt_count", "api_retries", "api_retry_policy",
                "api_retry_stopped", "failure_phase", "delivery", "http_status",
                "request_id", "cost_status", "unpriced_attempts", "reported_cost_usd_subtotal", "relay")) {
            if (result.evidence().containsKey(key)) entry.put(key, result.evidence().get(key));
        }
        Object raw = result.evidence().get("raw_stdout");
        if (raw != null) entry.put("raw_stdout", raw);
        Object quota = result.evidence().get("quota");
        if (quota != null) entry.put("quota", quota);
        Object unavailableEvidence = result.evidence().get("vendor_unavailable");
        if (unavailableEvidence != null) entry.put("vendor_unavailable", unavailableEvidence);
        // The failed call stays readable after a switch. The home corpus allowlist drops
        // these tails; the local attempt does not.
        for (String tail : List.of("stderr_tail", "stdout_tail", "exit_code", "timed_out", "start_error")) {
            if (result.evidence().containsKey(tail)) entry.put(tail, result.evidence().get(tail));
        }
        if (inheritsUnfinishedWork) entry.put("inherited_unfinished_work", true);
        return entry;
    }

    /**
     * Whether this prompt has to be told about the browser scenarios after the fact.
     *
     * The scenarios are half the definition of done — for a project with no check command
     * they are all of it — and both the reviewer and the visual_qa prompts have always been
     * given them. The implementer, the one role that can actually satisfy them, was not:
     * measured on a live run, where the contract asked for a `data-testid` the harness needs
     * and the implementer's prompt never mentioned it. It would have been found by the
     * browser stage and sent back as a fix round, paying a vendor to learn something the
     * contract already said.
     *
     * The shipped template now names {{visual_scenarios}}. This covers the templates already
     * sitting in an operator's ~/.warden — `warden setup` never overwrites them, and no
     * operator ever meant to hide the definition of done from the role expected to meet it.
     */
    private static boolean backfillNeeded(String role, TaskSpec.ResolvedTask task, String template) {
        return "implementer".equals(role)
                && task.visualQa().required()
                && !task.visualQa().scenarios().isEmpty()
                && !PromptRenderer.uses(template, "visual_scenarios");
    }

    /** Items the task already accepts as checked by hand, or an explicit empty list. */
    private static String manualLines(List<String> items) {
        if (items == null || items.isEmpty()) return "(none declared)";
        return bullets(items);
    }

    private static String browserScenarioSection(String scenarios) {
        return """

                ## Browser scenarios that must also pass

                A headless browser runs these against the app after the acceptance commands.
                They are part of the definition of done, not a suggestion: a matcher that finds
                nothing fails the run and comes back to you as a fix round. The grammar is
                `testid=` / `css=` / `role=` / `text=` with `visible`, `hidden`, `click`,
                `click@x,y` or `focused`, plus `press=KEY` (Escape, Enter, Tab, Space, an arrow,
                or one character; a matcher before it is focused first) and `wait` / `wait-for`.

                """ + scenarios + "\n";
    }

    private Map<String, String> promptValues(ConfigLoader.Loaded loaded, TaskSpec.ResolvedTask task,
                                             String runId, String mergeBase, UserConfig user,
                                             Profile profile, Path contextFile,
                                             boolean inheritsUnfinishedWork,
                                             List<Map<String, Object>> attempts,
                                             List<Path> attachments, Path evidenceDirectory)
            throws Exception {
        String schemaPretty = "";
        String schemaJson = "";
        if (profile.jsonSchema() != null) {
            Path schemaFile = user.resolve(profile.jsonSchema());
            if (Files.isRegularFile(schemaFile)) {
                Object schema = Json.parse(Files.readString(schemaFile));
                schemaPretty = Json.writePretty(schema);
                schemaJson = Json.write(schema);
            }
        }
        String context = contextFile != null && Files.isRegularFile(contextFile)
                ? Files.readString(contextFile) : "";
        if (inheritsUnfinishedWork) context = handover(attempts) + context;

        Map<String, String> values = new LinkedHashMap<>();
        values.put("task_id", task.id());
        values.put("run_id", runId);
        values.put("goal", task.goal());
        values.put("non_goals", bullets(task.nonGoals()));
        values.put("risk", task.risk());
        values.put("project", loaded.project().project());
        values.put("base_ref", task.baseRef());
        values.put("diff_base_commit", mergeBase);
        values.put("scope_paths", String.join(", ", task.scopePaths()));
        values.put("changed_files", changedFileList(loaded.root(), mergeBase));
        values.put("acceptance_commands", bullets(task.acceptanceCommands()));
        values.put("visual_scenarios", bullets(task.visualQa().scenarios()));
        values.put("manual_verification", manualLines(task.manualVerification()));
        values.put("authority", "workspace_write=" + task.authority().workspaceWrite()
                + ", network=" + task.authority().network() + ", land=" + task.authority().land());
        values.put("operator_goal", task.goal());
        values.put("named_checks", namedEntries(loaded.project().checks()));
        values.put("named_scopes", namedEntries(loaded.project().scopes()));
        values.put("max_fix_attempts", String.valueOf(task.maxFixAttempts()));
        values.put("timeout_minutes", String.valueOf(task.timeoutMinutes()));
        values.put("budget_max_role_runs", String.valueOf(task.budget().maxRoleRuns()));
        values.put("budget_max_cost_usd", String.valueOf(task.budget().maxCostUsd()));
        values.put("contract_path", loaded.root().relativize(loaded.taskFile()).toString().replace('\\', '/'));
        values.put("schema_json", schemaJson);
        values.put("schema_pretty", schemaPretty);
        values.put("context", context);
        values.put("context_path", contextFile == null ? "" : contextFile.toString());
        values.put("screenshots", attachmentList(attachments));
        values.put("evidence_dir", evidenceDirectory == null ? ""
                : evidenceDirectory.toAbsolutePath().normalize().toString());
        values.put("vision_note", visionNote(profile, attachments, evidenceDirectory));
        return values;
    }

    /**
     * The paths a reviewer has to look at, listed rather than described.
     *
     * The prompt used to name `git diff` and `git ls-files` as the way to find them, and for
     * a profile whose tool grant is Read/Grep/Glob that instruction is an invitation to spend
     * turns on refusals. Measured: an Opus review spent 16 of its 74 tool calls on shell
     * calls it was never allowed to make — every one of them doing exactly what this prompt
     * told it to — and then died at its turn ceiling. A list of paths costs nothing to render
     * and is usable by any profile, with or without a shell.
     */
    private String changedFileList(Path root, String mergeBase) {
        try {
            List<String> paths = new ArrayList<>(
                    new GitRepository(root, processes).changedPaths(mergeBase));
            paths.removeIf(path -> path.equals(dev.warden.config.WardenTree.DIRECTORY)
                    || path.startsWith(dev.warden.config.WardenTree.DIRECTORY + "/"));
            if (paths.isEmpty()) return "- (nothing changed since the diff base)";
            return bullets(paths.stream().map(path -> "`" + path + "`").toList());
        } catch (Exception unreadable) {
            // A prompt is not the place to fail a run. The reviewer still has the diff base
            // commit and its own tools; it simply does not get the shortcut.
            return "- (Warden could not list them: " + unreadable.getMessage() + ")";
        }
    }

    /**
     * What a vendor is told when it picks up a role another vendor was cut off from. It is
     * given the fact and no reassurance: the tree it is looking at is partly someone else's
     * work, and pretending otherwise produces a second implementation layered on a first.
     */
    private static String handover(List<Map<String, Object>> attempts) {
        StringBuilder builder = new StringBuilder("# You are continuing an interrupted run\n\n");
        for (Map<String, Object> attempt : attempts) {
            builder.append("- `").append(attempt.get("profile")).append("` (vendor `")
                    .append(attempt.get("vendor")).append("`) stopped with `")
                    .append(attempt.get("code")).append("`.\n");
        }
        builder.append("""

                The previous attempt stopped before producing a completed result. The worktree
                already contains edits it made, and those edits are unreviewed and may be half
                of a larger change.

                Read the current state of the files in scope before writing anything. Finish the
                task as specified — do not restart it from scratch, and do not assume the
                existing edits are correct.

                """);
        return builder.toString();
    }

    /**
     * How the pixels actually reach this vendor, in the words the prompt needs.
     *
     * The two deliveries ask for different behaviour from the model, and getting it wrong is
     * silent: a `workspace_file` profile told the images are "attached" will answer about
     * filenames, and sound just as confident doing it.
     */
    private static String visionNote(Profile profile, List<Path> attachments, Path evidenceDirectory) {
        boolean any = attachments != null && !attachments.isEmpty();
        if (!any && profile.vision() != null && profile.vision().acquires()) {
            return "No screenshot was handed to you: this role takes its own. Drive the "
                    + "application with your own tools (the browser, engine or desktop MCP "
                    + "server this profile is configured with), exercise the scenarios above, "
                    + "and save every screenshot you judge as a PNG file under `"
                    + (evidenceDirectory == null ? "the run's screenshots directory"
                            : evidenceDirectory.toAbsolutePath().normalize())
                    + "`. List those files, as absolute paths, in `screenshots_taken`. Warden "
                    + "verifies that each listed file exists there and hashes it; a verdict that "
                    + "lists none is refused as having no evidence. Do not edit the source tree.";
        }
        if (!any) return "No screenshot reached this run. Say so and return status: \"aborted\".";
        String delivery = profile.vision() == null ? null : profile.vision().delivery();
        if ("cli_attachment".equals(delivery)) {
            return "The images below are attached to this message as image data. Look at them.";
        }
        if ("workspace_file".equals(delivery)) {
            return "The images below are absolute paths in this workspace, not attachments. "
                    + "**Open every one with your own image-reading tool before you answer.** "
                    + "If your tools cannot open an image, say so in `summary` and return "
                    + "status: \"aborted\" — a verdict on files you did not open is worth "
                    + "less than an honest refusal, and Warden checks for exactly that.";
        }
        return "The images below are paths. This profile declares no vision capability, so "
                + "whether you can see them at all is unproven; if you cannot, say so and "
                + "return status: \"aborted\".";
    }

    private static String attachmentList(List<Path> attachments) {
        if (attachments == null || attachments.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Path attachment : attachments) {
            builder.append("- ").append(attachment.toAbsolutePath()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private static String bullets(List<String> items) {
        if (items == null || items.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (String item : items) builder.append("- ").append(item).append('\n');
        return builder.toString().stripTrailing();
    }

    /**
     * Named project entries as the planner (and any later reader) must see them: the name
     * Warden will accept, and the value that name already resolves to. A model that invents
     * a fourth name is refused at compile time; this list is what "already exists" means.
     */
    private static String namedEntries(Map<String, ? extends List<String>> named) {
        if (named == null || named.isEmpty()) return "(none)";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, ? extends List<String>> entry : named.entrySet()) {
            builder.append("- `").append(entry.getKey()).append("`: ")
                    .append(entry.getValue()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
    }

    private static List<String> commandPreview(Profile profile) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(profile.command()),
                profile.args().stream().map(arg -> arg.replace("{{effort}}", profile.effort() == null ? "" : profile.effort())
                        .replace("{{model}}", profile.model() == null ? "" : profile.model()))).toList();
    }

    /**
     * What actually leaves this process for a runner that starts none.
     *
     * `command_preview` names an argv, and a local profile has no argv to name. Publishing
     * `["ollama"]` as the dispatch for an HTTP POST would put a process that was never
     * started into the report of every local role, dry runs included — the same class of
     * claim the adapter itself was added to stop making.
     */
    private static Map<String, Object> dispatchPreview(Profile profile) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("method", "POST");
        preview.put("endpoint", profile.endpoint());
        preview.put("model", profile.model());
        if ("api".equals(profile.runner())) {
            preview.put("connection", profile.connectionRef());
            preview.put("model_vendor", profile.modelVendor());
            preview.put("max_output_tokens", (long) profile.maxOutputTokens());
            preview.put("auth", new dev.warden.execution.CredentialResolver().status(profile.apiConnection()));
            preview.put("execution", "text_completion_only");
        }
        return preview;
    }

    /**
     * The beat and the card learn the name here, from the resolution that is about to
     * dispatch. A failure to tell anyone is dropped: this is a retelling, not evidence.
     */
    private void publish(Profile profile) {
        if (profile == null) return;
        try {
            occupied.by(profile.name(), profile.vendor());
        } catch (RuntimeException | Error notOurProblem) {
            // Naming the vendor is not a reason to stop the run.
        }
    }

    /** A vendor whose executable is absent is skipped, never attempted mid-loop. */
    private boolean available(Profile profile) {
        return availability != null ? availability.available(profile) : executableFound(processes, profile);
    }

    /**
     * Whether the program a profile needs is on this machine — the resolver's availability
     * question, public so the panel asks it the same way.
     */
    public static boolean executableFound(ProcessRunner processes, Profile profile) {
        // A local profile has no process to find: the model is already listening on HTTP.
        // Probing `command` would skip every local profile whose command is a label.
        if ("api".equals(profile.runner())) {
            if (profile.apiConnection() == null || !profile.apiConnection().enabled()) return false;
            String state = String.valueOf(new dev.warden.execution.CredentialResolver()
                    .status(profile.apiConnection()).get("state"));
            return "ready".equals(state) || "not_required".equals(state);
        }
        if ("local".equals(profile.runner())) return true;
        if (profile.harness() != null) {
            if (profile.harnessExecutable() == null || !Files.isExecutable(profile.harnessExecutable())
                    || profile.apiConnection() == null || !profile.apiConnection().enabled()) return false;
            String state = String.valueOf(new dev.warden.execution.CredentialResolver()
                    .status(profile.apiConnection()).get("state"));
            return "ready".equals(state) || "not_required".equals(state);
        }
        if (profile.command() == null) return false;
        String command = "orca".equals(profile.runner()) ? "orca" : profile.command();
        if (command.contains("/") || command.contains("\\")) return Files.isExecutable(Path.of(command));
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> probe = windows ? List.of("where.exe", command) : List.of("sh", "-c", "command -v " + command);
        try {
            ProcessRunner.Result result = processes.run(probe, Path.of("."), Duration.ofSeconds(10));
            return result.ok() && !result.stdout().isBlank();
        } catch (Exception failure) {
            return false;
        }
    }

    /**
     * Which position in the role's profile list this dispatch takes.
     *
     * A role dispatched by several workflow stages takes its position from the stage, and
     * nothing is persisted. This is what makes "Opus reads first, GPT reads last" a property
     * of `policy.yaml` that holds on every run. The counter it replaces advanced once per
     * dispatch and was shared by every run in the project, so a run that died before its
     * reviewer, a fix round, or a `warden role` invocation all shifted the pairing: measured
     * live, two failed runs left the third run's first review with the profile the operator
     * had put second, and the order only righted itself by accident after a fix round.
     *
     * A role with a single stage keeps the persisted counter, because for that shape rotation
     * across runs is the whole feature: two implementers alternating run to run.
     */
    private long rotationFor(Path root, String role, int stagePosition, boolean dryRun)
            throws Exception {
        if (stagePosition >= 0) return stagePosition;
        return nextRotation(root, role, dryRun);
    }

    /** Rotation state lives with the project's run evidence, so it is local and disposable. */
    private long nextRotation(Path root, String role, boolean dryRun) throws Exception {
        Path file = root.resolve(".warden/runs/rotation.json");
        Map<String, Object> state = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            Map<String, Object> parsed = Json.findLastObject(Files.readString(file));
            if (parsed != null) state.putAll(parsed);
        }
        long current = state.get(role) instanceof Number number ? number.longValue() : 0L;
        if (dryRun) return current;
        state.put(role, current + 1);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.writePretty(state) + System.lineSeparator(), StandardCharsets.UTF_8);
        return current;
    }
}
