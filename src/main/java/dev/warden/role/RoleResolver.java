package dev.warden.role;

import dev.warden.config.Policy;
import dev.warden.config.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves capabilities in code; no model chooses which model should run. */
public final class RoleResolver {
    @FunctionalInterface
    public interface Availability { boolean available(Profile profile); }

    /**
     * Who has written into the candidate so far, as far as Warden can prove.
     *
     * Independence used to be checked against the last implementer's vendor. That is the
     * wrong set: a writer that was failed over mid-edit, a fix round by a different vendor,
     * and every writer of every run a continuation inherits all left bytes in the tree, and
     * a reviewer sharing any of those vendors is reading its own work. So the resolver is
     * handed the whole set, and it is a set of vendors rather than a lineage of lines.
     *
     * {@code known} is false when Warden cannot prove who wrote the candidate — a tree that
     * already differed from the diff base when the run started, or a resumed tree that no
     * recorded run left in that state. An unknown set refuses nobody: it cannot. What it does
     * is deny the label: no verdict reached over it is called independent.
     *
     * @param peerAllowed whether the task's contract admits a same-vendor peer reading in
     *                    place of an independent one. Defaults to false: the peer pair is a
     *                    weaker assurance and a contract has to ask for it.
     */
    public record Writers(Set<String> vendors, Set<String> profiles, boolean known,
                          boolean peerAllowed) {
        public static final Writers NONE = new Writers(Set.of(), Set.of(), true, false);

        public Writers {
            vendors = Set.copyOf(vendors);
            profiles = Set.copyOf(profiles);
        }

        /** The legacy single-vendor form: one implementer, provenance known. */
        public static Writers of(String implementerVendor) {
            if (implementerVendor == null || implementerVendor.isBlank()) return NONE;
            return new Writers(Set.of(implementerVendor), Set.of(), true, false);
        }

        public Writers allowingPeer(boolean allowed) {
            return new Writers(vendors, profiles, known, allowed);
        }

        public boolean contains(String vendor) {
            return vendor != null && vendors.contains(vendor);
        }
    }

    /**
     * What a resolution can claim about the reader it chose.
     *
     * `independent`: the profile's vendor wrote nothing in the candidate, and the writer set
     * is known. `same_vendor_peer`: the profile is the declared peer reviewer of the one
     * writer, admitted by policy and by the task. `unproven`: the writer set is not known, so
     * nothing can be said either way. `none`: the role is not a reader of the candidate (a
     * writer, a planner), so the question does not arise.
     */
    public static final String INDEPENDENT = "independent";
    public static final String SAME_VENDOR_PEER = "same_vendor_peer";
    public static final String UNPROVEN = "unproven";
    public static final String NONE = "none";

    /**
     * Roles whose verdict is the check on somebody else's work: the code, the screenshots,
     * the contract. A judge that may write can change what it is judging, so it is refused
     * before dispatch rather than admitted as a writer. The write flag used to decide
     * whether independence was asked at all: a reviewer copied from an implementer with
     * {@code read_only: false} and the writer's vendor was labelled {@code none} and passed a
     * strict {@code require_independent_vendor: true} policy, reading its own vendor's work.
     */
    public static final Set<String> JUDGING_ROLES = Set.of("reviewer", "visual_qa", "plan_reviewer");

    /** The rejection a writable profile gets for a judging role. */
    public static final String JUDGE_NOT_READ_ONLY = "judge_not_read_only";

    public static boolean judges(String role) { return JUDGING_ROLES.contains(role); }

    public record Resolution(Profile selected, Map<String, String> rejected, String assurance) {
        public Resolution(Profile selected, Map<String, String> rejected) {
            this(selected, rejected, NONE);
        }
    }

    /**
     * No profile can fill the role. Carries the per-profile reasons rather than only a
     * message, because the caller's next move depends on them: a role nothing is configured
     * for needs an operator, and a role whose every vendor ran out needs a different vendor or
     * the passage of time.
     */
    public static final class Unresolvable extends IllegalStateException {
        private final Map<String, String> rejected;

        public Unresolvable(String message, Map<String, String> rejected) {
            super(message);
            this.rejected = Map.copyOf(rejected);
        }

        public Map<String, String> rejected() { return rejected; }

        /**
         * Whether every refusal was about who wrote the candidate rather than about the
         * roster. The loop reports that as `independent_review_unavailable`, a fact about
         * the writers, instead of `reviewer_failed`, which sends an operator to read a
         * transcript with no defect in it.
         */
        public boolean onlyIndependence() {
            if (rejected.isEmpty()) return false;
            return rejected.values().stream().allMatch(RoleResolver::isIndependenceReason);
        }
    }

    /** Reasons that name the writer set, not the roster. */
    public static boolean isIndependenceReason(String reason) {
        return reason != null && (reason.equals("same_vendor_as_writer")
                || reason.equals("shares_vendor_with_writer")
                || reason.equals("peer_not_allowed_by_task")
                || reason.startsWith("peer_pair_invalid"));
    }

    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              String implementerVendor, long rotation, Availability availability) {
        return resolve(role, policy, profiles, Writers.of(implementerVendor), rotation, availability,
                Set.of());
    }

    /**
     * @param exhausted profiles that already reported an exhausted subscription during this
     *                  process. Their quota does not come back while a loop is running, so
     *                  re-dispatching one only converts budget into the same refusal.
     */
    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              String implementerVendor, long rotation, Availability availability,
                              Set<String> exhausted) {
        return resolve(role, policy, profiles, Writers.of(implementerVendor), rotation, availability,
                exhausted);
    }

    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              Writers writers, long rotation, Availability availability,
                              Set<String> exhausted) {
        Policy.RoleSpec roleSpec = policy.roles().get(role);
        Map<String, String> rejected = new LinkedHashMap<>();
        List<Profile> eligible = new ArrayList<>();
        Map<String, String> assurances = new LinkedHashMap<>();
        for (Candidate candidate : candidates(role, policy, profiles, writers, availability, exhausted)) {
            if (!candidate.eligible()) { rejected.put(candidate.profile(), candidate.rejected()); continue; }
            eligible.add(profiles.get(candidate.profile()));
            assurances.put(candidate.profile(), candidate.assurance());
        }
        if (eligible.isEmpty()) {
            throw new Unresolvable("no eligible profile for role " + role + ": " + rejected, rejected);
        }
        int index = roleSpec.strategy().equals("first") ? 0 : Math.floorMod(rotation, eligible.size());
        Profile chosen = eligible.get(index);
        return new Resolution(chosen, Map.copyOf(rejected), assurances.get(chosen.name()));
    }

    /**
     * One profile the policy names for a role, with the resolver's verdict on it: the reason
     * it is refused, or the assurance it would read with.
     */
    public record Candidate(String profile, String rejected, String assurance) {
        public boolean eligible() { return rejected == null; }
    }

    /**
     * Every profile the policy names for {@code role}, in policy order, each with the verdict
     * {@link #resolve} reaches on it. {@code resolve} is built on this, so a surface that shows
     * why a candidate is out — the Warden panel does — shows the resolver's own reason and
     * never one of its own.
     */
    public List<Candidate> candidates(String role, Policy policy, Map<String, Profile> profiles,
                                      Writers writers, Availability availability, Set<String> exhausted) {
        Policy.RoleSpec roleSpec = policy.roles().get(role);
        if (roleSpec == null) throw new IllegalArgumentException("role is not configured: " + role);
        Writers known = writers == null ? Writers.NONE : writers;
        List<Candidate> verdicts = new ArrayList<>();
        for (String name : roleSpec.profiles()) {
            Profile profile = profiles.get(name);
            if (profile == null) { verdicts.add(new Candidate(name, "profile_not_found", null)); continue; }
            if (!profile.role().equals(role)) { verdicts.add(new Candidate(name, "role_mismatch", null)); continue; }
            // A judge that may write can change what it is judging: refused before anything
            // else is asked of it, pinned or rotated. See JUDGING_ROLES.
            if (judges(role) && !profile.readOnly()) {
                verdicts.add(new Candidate(name, JUDGE_NOT_READ_ONLY, null));
                continue;
            }
            if ("visual_qa".equals(role) && !profile.hasVerifiedVision()) {
                verdicts.add(new Candidate(name, "vision_capability_unverified", null));
                continue;
            }
            if (!profile.verified()) { verdicts.add(new Candidate(name, "profile_unverified", null)); continue; }
            if (exhausted.contains(name)) {
                verdicts.add(new Candidate(name, "quota_exhausted_this_run", null));
                continue;
            }
            String assurance = assuranceOf(roleSpec, profile, profiles, known);
            if (assurance.startsWith("refused:")) {
                verdicts.add(new Candidate(name, assurance.substring("refused:".length()), null));
                continue;
            }
            // The probe answers one question — is the executable there. Calling that
            // "unavailable or quota exhausted" claimed knowledge Warden did not have; a spent
            // subscription is only ever learned by running, and is reported separately.
            if (!availability.available(profile)) {
                verdicts.add(new Candidate(name, "executable_not_found", null));
                continue;
            }
            verdicts.add(new Candidate(name, null, assurance));
        }
        return List.copyOf(verdicts);
    }

    /**
     * What this profile could claim about the candidate, or `refused:<reason>` when it may not
     * read it at all.
     *
     * A writer is never measured against the writer set: an implementer re-dispatched for a
     * fix round shares a vendor with itself, and that is the point of the round. A reader
     * whose vendor wrote nothing is independent when the writers are known. A reader whose
     * vendor did write is refused under a strict policy, and admitted under a permissive one
     * only as the declared peer of the single writer — a pair the operator named, with two
     * different models, which the resolution then labels as the weaker assurance it is.
     * `require_independent_vendor: false` alone used to admit any same-vendor reader, silently
     * and with no mark on the verdict; it no longer does. A judging role is measured whatever
     * its write flag says; see {@link #JUDGING_ROLES}.
     */
    static String assuranceOf(Policy.RoleSpec spec, Profile profile,
                              Map<String, Profile> profiles, Writers writers) {
        if (judges(spec.role()) && !profile.readOnly()) return "refused:" + JUDGE_NOT_READ_ONLY;
        if (!profile.readOnly()) return NONE;
        if (!writers.known()) return UNPROVEN;
        if (!writers.contains(profile.vendor())) return INDEPENDENT;
        if (spec.requireIndependentVendor()) return "refused:same_vendor_as_writer";
        Policy.PeerPair pair = spec.peer();
        if (pair == null) return "refused:shares_vendor_with_writer";
        String invalid = pair.problem(profiles, spec.role());
        if (invalid != null) return "refused:peer_pair_invalid:" + invalid;
        if (!pair.reviewer().equals(profile.name())) return "refused:shares_vendor_with_writer";
        if (!writers.vendors().equals(Set.of(profile.vendor()))) {
            return "refused:shares_vendor_with_writer";
        }
        // The pair names one writer. A tree that a second profile of the same vendor also
        // wrote into is not the pair the operator declared, whatever the vendor column says.
        if (!writers.profiles().isEmpty() && !writers.profiles().equals(Set.of(pair.implementer()))) {
            return "refused:shares_vendor_with_writer";
        }
        if (!writers.peerAllowed()) return "refused:peer_not_allowed_by_task";
        return SAME_VENDOR_PEER;
    }
}
