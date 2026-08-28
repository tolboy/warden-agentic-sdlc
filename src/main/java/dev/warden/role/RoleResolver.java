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

    public record Resolution(Profile selected, Map<String, String> rejected) {}

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
    }

    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              String implementerVendor, long rotation, Availability availability) {
        return resolve(role, policy, profiles, implementerVendor, rotation, availability, Set.of());
    }

    /**
     * @param exhausted profiles that already reported an exhausted subscription during this
     *                  process. Their quota does not come back while a loop is running, so
     *                  re-dispatching one only converts budget into the same refusal.
     */
    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              String implementerVendor, long rotation, Availability availability,
                              Set<String> exhausted) {
        Policy.RoleSpec roleSpec = policy.roles().get(role);
        if (roleSpec == null) throw new IllegalArgumentException("role is not configured: " + role);

        Map<String, String> rejected = new LinkedHashMap<>();
        List<Profile> eligible = new ArrayList<>();
        for (String name : roleSpec.profiles()) {
            Profile profile = profiles.get(name);
            if (profile == null) { rejected.put(name, "profile_not_found"); continue; }
            if (!profile.role().equals(role)) { rejected.put(name, "role_mismatch"); continue; }
            if ("visual_qa".equals(role) && !profile.hasVerifiedVision()) {
                rejected.put(name, "vision_capability_unverified");
                continue;
            }
            if (!profile.verified()) { rejected.put(name, "profile_unverified"); continue; }
            if ("local".equals(profile.runner())) { rejected.put(name, "runner_unimplemented"); continue; }
            if (exhausted.contains(name)) { rejected.put(name, "quota_exhausted_this_run"); continue; }
            if (roleSpec.requireIndependentVendor() && implementerVendor != null
                    && profile.vendor().equals(implementerVendor)) {
                rejected.put(name, "same_vendor_as_implementer");
                continue;
            }
            // The probe answers one question — is the executable there. Calling that
            // "unavailable or quota exhausted" claimed knowledge Warden did not have; a spent
            // subscription is only ever learned by running, and is reported separately.
            if (!availability.available(profile)) { rejected.put(name, "executable_not_found"); continue; }
            eligible.add(profile);
        }
        if (eligible.isEmpty()) {
            throw new Unresolvable("no eligible profile for role " + role + ": " + rejected, rejected);
        }
        int index = roleSpec.strategy().equals("first") ? 0 : Math.floorMod(rotation, eligible.size());
        return new Resolution(eligible.get(index), Map.copyOf(rejected));
    }
}
