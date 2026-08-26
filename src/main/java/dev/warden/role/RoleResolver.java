package dev.warden.role;

import dev.warden.config.Policy;
import dev.warden.config.Profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves capabilities in code; no model chooses which model should run. */
public final class RoleResolver {
    @FunctionalInterface
    public interface Availability { boolean available(Profile profile); }

    public record Resolution(Profile selected, Map<String, String> rejected) {}

    public Resolution resolve(String role, Policy policy, Map<String, Profile> profiles,
                              String implementerVendor, long rotation, Availability availability) {
        Policy.RoleSpec roleSpec = policy.roles().get(role);
        if (roleSpec == null) throw new IllegalArgumentException("role is not configured: " + role);

        Map<String, String> rejected = new LinkedHashMap<>();
        List<Profile> eligible = new ArrayList<>();
        for (String name : roleSpec.profiles()) {
            Profile profile = profiles.get(name);
            if (profile == null) { rejected.put(name, "profile_not_found"); continue; }
            if (!profile.role().equals(role)) { rejected.put(name, "role_mismatch"); continue; }
            if (!profile.verified()) { rejected.put(name, "profile_unverified"); continue; }
            if (roleSpec.requireIndependentVendor() && implementerVendor != null
                    && profile.vendor().equals(implementerVendor)) {
                rejected.put(name, "same_vendor_as_implementer");
                continue;
            }
            if (!availability.available(profile)) { rejected.put(name, "unavailable_or_quota_exhausted"); continue; }
            eligible.add(profile);
        }
        if (eligible.isEmpty()) {
            throw new IllegalStateException("no eligible profile for role " + role + ": " + rejected);
        }
        int index = roleSpec.strategy().equals("first") ? 0 : Math.floorMod(rotation, eligible.size());
        return new Resolution(eligible.get(index), Map.copyOf(rejected));
    }
}
