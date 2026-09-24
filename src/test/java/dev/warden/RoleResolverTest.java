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
        check.eq("same vendor is rejected", "same_vendor_as_writer", first.rejected().get("codex"));
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
        writerSetChecks(check, resolver, policy, profiles);
        writableJudgeChecks(check, resolver);
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

    /**
     * Independence is measured against everyone who wrote the candidate, and a same-vendor
     * reading is admitted only as the pair the operator declared, labelled for what it is.
     */
    private void writerSetChecks(Check check, RoleResolver resolver, Policy policy,
                                 Map<String, Profile> profiles) {
        RoleResolver.Writers two = new RoleResolver.Writers(
                java.util.Set.of("openai", "grok"), java.util.Set.of(), true, false);
        RoleResolver.Resolution left = resolver.resolve("reviewer", policy, profiles, two, 0,
                profile -> true, java.util.Set.of());
        check.eq("a reader must differ from every writer, not only the last one", "claude",
                left.selected().name());
        check.eq("both writers' vendors are refused", "same_vendor_as_writer",
                left.rejected().get("grok"));
        check.eq("and the reading is labelled independent", RoleResolver.INDEPENDENT,
                left.assurance());

        RoleResolver.Writers unknown = new RoleResolver.Writers(
                java.util.Set.of(), java.util.Set.of(), false, false);
        RoleResolver.Resolution unproven = resolver.resolve("reviewer", policy, profiles, unknown,
                0, profile -> true, java.util.Set.of());
        check.eq("an unknown writer set refuses nobody", "grok", unproven.selected().name());
        check.eq("and denies the label instead", RoleResolver.UNPROVEN, unproven.assurance());

        Policy permissive = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [codex]
                    strategy: first
                    require_independent_vendor: false
                """, "policy.yaml");
        RoleResolver.Writers openai = new RoleResolver.Writers(
                java.util.Set.of("openai"), java.util.Set.of("codex-writer"), true, true);
        check.rejects("a permissive policy no longer admits any same-vendor reader",
                "shares_vendor_with_writer", () ->
                resolver.resolve("reviewer", permissive, profiles, openai, 0, profile -> true,
                        java.util.Set.of()));

        check.rejects("a strict policy cannot also declare a peer pair", "conflict", () ->
                Policy.parse("""
                        version: 1
                        roles:
                          reviewer:
                            profiles: [codex]
                            strategy: first
                            require_independent_vendor: true
                            same_vendor_peer: { implementer: codex-writer, reviewer: codex }
                        """, "policy.yaml"));

        Policy paired = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [codex]
                    strategy: first
                    require_independent_vendor: false
                    same_vendor_peer: { implementer: codex-writer, reviewer: codex }
                """, "policy.yaml");
        Map<String, Profile> withWriter = new LinkedHashMap<>(profiles);
        withWriter.put("codex", modelled("codex", "openai", "reviewer", "gpt-6-astra", true));
        withWriter.put("codex-writer", modelled("codex-writer", "openai", "implementer", "gpt-6-sol", false));
        RoleResolver.Resolution peer = resolver.resolve("reviewer", paired, withWriter, openai, 0,
                profile -> true, java.util.Set.of());
        check.eq("the declared pair is admitted", "codex", peer.selected().name());
        check.eq("as a same-vendor peer, never as independent", RoleResolver.SAME_VENDOR_PEER,
                peer.assurance());
        check.rejects("but only when the task admits that weaker reading",
                "peer_not_allowed_by_task", () ->
                resolver.resolve("reviewer", paired, withWriter, openai.allowingPeer(false), 0,
                        profile -> true, java.util.Set.of()));
        RoleResolver.Writers twoWriters = new RoleResolver.Writers(
                java.util.Set.of("openai"), java.util.Set.of("codex-writer", "codex-other"), true, true);
        check.rejects("a second profile of the same vendor in the writer set is not the pair",
                "shares_vendor_with_writer", () ->
                resolver.resolve("reviewer", paired, withWriter, twoWriters, 0, profile -> true,
                        java.util.Set.of()));
        withWriter.put("codex-writer", modelled("codex-writer", "openai", "implementer", "gpt-6-astra", false));
        check.rejects("the same model at another effort is not a peer", "peer_pair_invalid", () ->
                resolver.resolve("reviewer", paired, withWriter, openai, 0, profile -> true,
                        java.util.Set.of()));
    }

    /**
     * A reviewer copied from the implementer, {@code read_only: false} left in, the writer's
     * vendor, and a strict policy. The write flag used to skip the independence question
     * altogether: the resolver labelled it {@code none} and it read its own vendor's work.
     */
    private void writableJudgeChecks(Check check, RoleResolver resolver) {
        Policy strict = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [copied-review]
                    strategy: first
                    require_independent_vendor: true
                  visual_qa:
                    profiles: [copied-eyes]
                    strategy: first
                  plan_reviewer:
                    profiles: [copied-plan]
                    strategy: first
                  implementer:
                    profiles: [codex-writer]
                    strategy: first
                """, "policy.yaml");
        Map<String, Profile> roster = new LinkedHashMap<>();
        roster.put("copied-review", modelled("copied-review", "openai", "reviewer", "gpt-6-sol", false));
        roster.put("copied-plan", modelled("copied-plan", "openai", "plan_reviewer", "gpt-6-sol", false));
        roster.put("copied-eyes", Profile.parse("""
                version: 1
                profile: copied-eyes
                role: visual_qa
                vendor: openai
                command: codex
                runner: orca
                read_only: false
                capabilities:
                  vision:
                    delivery: workspace_file
                    verification: required
                verification:
                  verified_on: "2026-08-27"
                """, "copied-eyes.yaml"));
        roster.put("codex-writer", modelled("codex-writer", "openai", "implementer", "gpt-6-sol", false));
        RoleResolver.Writers openai = new RoleResolver.Writers(
                java.util.Set.of("openai"), java.util.Set.of("codex-writer"), true, false);

        for (String role : java.util.List.of("reviewer", "visual_qa", "plan_reviewer")) {
            String name = strict.roles().get(role).profiles().get(0);
            try {
                resolver.resolve(role, strict, roster, openai, 0, profile -> true, java.util.Set.of());
                check.that("a writable " + role + " is refused", false);
            } catch (RoleResolver.Unresolvable refused) {
                check.eq("a writable " + role + " is refused, named, for being writable",
                        RoleResolver.JUDGE_NOT_READ_ONLY, refused.rejected().get(name));
                check.that("which is not an independence reason, so it is not reported as a missing vendor",
                        !refused.onlyIndependence());
            }
            try {
                resolver.resolve(role, strict, roster, RoleResolver.Writers.NONE, 0, profile -> true,
                        java.util.Set.of());
                check.that("with no writer yet a writable " + role + " is still refused", false);
            } catch (RoleResolver.Unresolvable refused) {
                check.eq("with no writer yet a writable " + role + " is still refused",
                        RoleResolver.JUDGE_NOT_READ_ONLY, refused.rejected().get(name));
            }
        }

        roster.put("fixed-review", modelled("fixed-review", "anthropic", "reviewer", "opus", true));
        Policy mixed = Policy.parse("""
                version: 1
                roles:
                  reviewer:
                    profiles: [copied-review, fixed-review]
                    strategy: first
                    require_independent_vendor: true
                """, "policy.yaml");
        RoleResolver.Resolution skipped = resolver.resolve("reviewer", mixed, roster, openai, 0,
                profile -> true, java.util.Set.of());
        check.eq("a read-only reader behind it is chosen instead", "fixed-review", skipped.selected().name());
        check.eq("and the writable one is listed as refused", RoleResolver.JUDGE_NOT_READ_ONLY,
                skipped.rejected().get("copied-review"));
        check.eq("the reading that remains is independent", RoleResolver.INDEPENDENT, skipped.assurance());

        RoleResolver.Resolution writer = resolver.resolve("implementer", strict, roster, openai, 0,
                profile -> true, java.util.Set.of());
        check.eq("a writer is still a writer: no independence question", RoleResolver.NONE, writer.assurance());
    }

    private static Profile modelled(String name, String vendor, String role, String model,
                                    boolean readOnly) {
        return Profile.parse("""
                version: 1
                profile: %s
                role: %s
                vendor: %s
                model: %s
                command: %s
                read_only: %s
                verification:
                  verified_on: "2026-08-26"
                """.formatted(name, role, vendor, model, name, readOnly), name + ".yaml");
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
