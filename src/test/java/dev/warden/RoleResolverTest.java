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

        profiles.put("claude", profile("claude", "anthropic", "2026-08-26"));
        profiles.put("local-only", Profile.parse("""
                version: 1
                profile: local-only
                role: reviewer
                vendor: local
                command: ollama
                runner: local
                endpoint: http://127.0.0.1:11434/v1/chat/completions
                verification:
                  verified_on: "2026-08-27"
                """, "local-only.yaml"));
        Policy localPolicy = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [local-only]
                    strategy: first
                """, "policy.yaml");
        RoleResolver.Resolution local = resolver.resolve("reviewer", localPolicy, profiles,
                null, 0, profile -> true);
        check.eq("a local runner is eligible, not skipped as unimplemented",
                "local-only", local.selected().name());
        check.eq("and is not rewritten as a direct CLI", "local", local.selected().runner());

        Policy visualPolicy = Policy.parse("""
                version: 1
                roles:
                  visual_qa:
                    profiles: [eyes]
                    strategy: first
                """, "policy.yaml");
        profiles.put("eyes", visualProfile(false));
        try {
            resolver.resolve("visual_qa", visualPolicy, profiles, null, 0, profile -> true);
            check.that("unverified vision is not eligible", false);
        } catch (RoleResolver.Unresolvable failure) {
            check.eq("unverified vision has a capability-specific reason",
                    "vision_capability_unverified", failure.rejected().get("eyes"));
        }
        profiles.put("eyes", visualProfile(true));
        check.eq("verified workspace-file vision is eligible", "eyes",
                resolver.resolve("visual_qa", visualPolicy, profiles, null, 0, profile -> true)
                        .selected().name());
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

    private static Profile visualProfile(boolean verified) {
        return Profile.parse("""
                version: 1
                profile: eyes
                role: visual_qa
                vendor: openai
                command: codex
                runner: orca
                capabilities:
                  vision:
                    delivery: workspace_file
                    verification: required
                verification:
                %s
                """.formatted(verified ? "  verified_on: \"2026-08-27\"" :
                "  probe: 'describe a known image'"), "eyes.yaml");
    }
}
