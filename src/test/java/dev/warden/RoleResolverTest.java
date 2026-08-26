package dev.warden;

import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.role.RoleResolver;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RoleResolverTest implements Suite {
    @Override public String name() { return "role-resolver"; }

    @Override public void run(Check check) {
        Policy policy = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [grok, claude, codex]
                    strategy: rotate
                    require_independent_vendor: true
                review:
                  required_for_risk: [medium, high]
                """, "policy.yaml");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        profiles.put("grok", profile("grok", "grok", "2026-08-26"));
        profiles.put("claude", profile("claude", "anthropic", "2026-08-26"));
        profiles.put("codex", profile("codex", "openai", "2026-08-26"));

        RoleResolver resolver = new RoleResolver();
        RoleResolver.Resolution first = resolver.resolve("reviewer", policy, profiles,
                "openai", 0, profile -> true);
        check.eq("independent vendor selected", "grok", first.selected().name());
        check.eq("same vendor is rejected", "same_vendor_as_implementer", first.rejected().get("codex"));
        RoleResolver.Resolution rotated = resolver.resolve("reviewer", policy, profiles,
                "openai", 1, profile -> true);
        check.eq("rotation is deterministic", "claude", rotated.selected().name());
        RoleResolver.Resolution fallback = resolver.resolve("reviewer", policy, profiles,
                "openai", 0, profile -> !profile.name().equals("grok"));
        check.eq("unavailable profile is skipped", "claude", fallback.selected().name());

        profiles.put("claude", profile("claude", "anthropic", null));
        check.rejects("independence and verification fail closed", "no eligible profile", () ->
                resolver.resolve("reviewer", policy, profiles, "openai", 0,
                        profile -> !profile.name().equals("grok")));
    }

    private static Profile profile(String name, String vendor, String verifiedOn) {
        String verification = verifiedOn == null ? "" : "  verified_on: \"" + verifiedOn + "\"\n";
        return Profile.parse("""
                version: 1
                profile: %s
                role: reviewer
                vendor: %s
                command: %s
                read_only: true
                verification:
                %s
                """.formatted(name, vendor, name, verification), name + ".yaml");
    }
}
