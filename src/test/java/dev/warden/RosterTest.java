package dev.warden;

import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.RosterCommand;
import dev.warden.config.UserSetup;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * {@code warden roster} must change the one line the operator named and leave every other
 * byte — comments, CRLF, the rest of the role — alone. A YAML round-trip would drop the
 * comments that explain why a flag is there.
 */
public final class RosterTest implements Suite {

    @Override public String name() { return "roster"; }

    @Override public void run(Check check) throws Exception {
        Path sandbox = Files.createTempDirectory("warden-roster-");
        try {
            listShowsRolesAndWorkflow(check, home(sandbox, "list"));
            setChangesOnlyTheProfilesLine(check, home(sandbox, "set-one-line"));
            unknownProfileLeavesTheFileUntouched(check, home(sandbox, "unknown"));
            blockListIsReplacedWithFlowForm(check, home(sandbox, "block-list"));
            missingRoleIsAppendedAtEndOfRoles(check, home(sandbox, "append"));
            modelRemovesVerifiedOnUnlessKept(check, sandbox);
            modelReachesTheVendorOrIsRefused(check, sandbox);
            effortWithoutPlaceholderIsRefused(check, home(sandbox, "effort"));
            failedReparseRestoresOriginalBytes(check, home(sandbox, "reparse"));
            allowMissingAndMismatch(check, home(sandbox, "allow"));
        } finally {
            deleteTree(sandbox);
        }
    }

    private static Path home(Path sandbox, String name) throws Exception {
        Path home = sandbox.resolve(name);
        new UserSetup().run(home);
        Path policy = home.resolve("policy.yaml");
        String lf = Files.readString(policy, StandardCharsets.UTF_8);
        Files.writeString(policy, lf.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        return home;
    }

    private static RosterCommand.Outcome roster(Path home, String... args) throws Exception {
        return new RosterCommand().run(home, args);
    }

    private void listShowsRolesAndWorkflow(Check check, Path home) throws Exception {
        RosterCommand.Outcome json = roster(home, "roster");
        check.that("roster lists an ok policy", json.ok());
        check.eq("list code", "roster", json.report().get("code"));
        check.eq("list backup is null", null, json.report().get("backup"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roles = (List<Map<String, Object>>) json.report().get("roles");
        check.eq("shipped policy has two roles", 2, roles.size());
        Map<String, Object> reviewer = roles.get(0);
        check.eq("first role is reviewer", "reviewer", reviewer.get("role"));
        check.eq("reviewer strategy", "rotate", reviewer.get("strategy"));
        check.eq("reviewer requires an independent vendor", true,
                reviewer.get("require_independent_vendor"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) reviewer.get("profiles");
        check.eq("reviewer profiles stay in policy order", "grok-review", profiles.get(0).get("profile"));
        check.eq("grok vendor", "grok", profiles.get(0).get("vendor"));
        check.eq("grok model", "grok-4.6-build", profiles.get(0).get("model"));
        check.eq("grok effort", "high", profiles.get(0).get("effort"));
        check.eq("grok runner", "direct", profiles.get(0).get("runner"));
        check.eq("grok read_only", true, profiles.get(0).get("read_only"));
        check.eq("verified is the date, not a boolean", "2026-08-25", profiles.get(0).get("verified"));
        check.eq("unverified profile carries null, not false", null, profiles.get(1).get("verified"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> workflow = (List<Map<String, Object>>) json.report().get("workflow");
        check.that("workflow stages are listed", workflow.size() >= 3);
        check.eq("first stage name", "implement", workflow.get(0).get("stage"));
        check.eq("first stage kind", "role", workflow.get(0).get("run"));
        check.eq("first stage role", "implementer", workflow.get(0).get("role"));

        check.eq("problems from UserConfig", Map.of(), json.report().get("problems"));
        check.eq("dangling from UserConfig", List.of(), json.report().get("dangling_policy_references"));

        RosterCommand.Outcome text = roster(home, "roster", "--text");
        check.that("--text prints a table", text.text() != null && text.text().contains("reviewer"));
        check.contains("table names the workflow", text.text(), "WORKFLOW");
        check.contains("table names dangling refs", text.text(), "DANGLING_POLICY_REFERENCES");
    }

    private void setChangesOnlyTheProfilesLine(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        String original = Files.readString(policy, StandardCharsets.UTF_8);
        check.that("fixture is CRLF", original.contains("\r\n"));
        check.that("fixture still has the comment that must survive",
                original.contains("Hard constraint"));

        RosterCommand.Outcome outcome = roster(home, "roster", "set", "reviewer",
                "--profiles", "grok-review");
        check.that("set succeeds", outcome.ok());
        check.eq("set code", "roster_set", outcome.report().get("code"));

        String after = Files.readString(policy, StandardCharsets.UTF_8);
        String expected = original.replace(
                "    profiles: [grok-review, claude-review]",
                "    profiles: [grok-review]");
        check.eq("set changes only the profiles line", expected, after);
        check.that("CRLF survived the rewrite", after.contains("\r\n"));
        check.that("the independence comment survived", after.contains("Hard constraint"));

        Path backup = Path.of(String.valueOf(outcome.report().get("backup")));
        check.that("backup exists", Files.isRegularFile(backup));
        check.eq("backup equals the original bytes", original,
                Files.readString(backup, StandardCharsets.UTF_8));
        check.eq("file path is policy.yaml", policy.toString(), outcome.report().get("file"));
    }

    private void unknownProfileLeavesTheFileUntouched(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        byte[] before = Files.readAllBytes(policy);
        RosterCommand.Outcome outcome = roster(home, "roster", "set", "reviewer",
                "--profiles", "no-such-profile");
        check.that("unknown profile is refused", !outcome.ok());
        check.eq("code", "profile_not_found", outcome.report().get("code"));
        check.that("policy.yaml is byte-identical", Arrays.equals(before, Files.readAllBytes(policy)));
        check.eq("no backup of a file we did not write", null, outcome.report().get("backup"));

        RosterCommand.Outcome unknownRole = roster(home, "roster", "set", "janitor",
                "--profiles", "grok-review");
        check.eq("unknown role is refused", "role_unknown", unknownRole.report().get("code"));
        check.that("unknown role also leaves the file", Arrays.equals(before, Files.readAllBytes(policy)));
    }

    private void blockListIsReplacedWithFlowForm(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        String block = """
                version: 1

                # keep this comment
                roles:
                  reviewer:
                    profiles:
                      - grok-review
                      - claude-review
                    strategy: rotate
                    require_independent_vendor: true
                  implementer:
                    profiles: [codex-implement]
                    strategy: first
                    require_independent_vendor: false
                review:
                  required_for_risk: [medium, high]
                """.replace("\n", "\r\n");
        Files.writeString(policy, block, StandardCharsets.UTF_8);
        String original = Files.readString(policy, StandardCharsets.UTF_8);

        RosterCommand.Outcome outcome = roster(home, "roster", "set", "reviewer",
                "--profiles", "grok-review,claude-review");
        check.that("block-list set succeeds", outcome.ok());
        String after = Files.readString(policy, StandardCharsets.UTF_8);
        String expected = original.replace(
                "    profiles:\r\n      - grok-review\r\n      - claude-review",
                "    profiles: [grok-review, claude-review]");
        check.eq("the block becomes one flow line and nothing else moves", expected, after);
        check.that("the comment survived", after.contains("# keep this comment"));
        Policy.parse(after, policy.toString());
    }

    private void missingRoleIsAppendedAtEndOfRoles(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        String original = Files.readString(policy, StandardCharsets.UTF_8);
        RosterCommand.Outcome outcome = roster(home, "roster", "set", "visual_qa",
                "--profiles", "codex-visual-qa");
        check.that("appending a missing role succeeds", outcome.ok());
        String after = Files.readString(policy, StandardCharsets.UTF_8);
        check.that("the commented visual_qa block is still a comment",
                original.contains("# visual_qa:") && after.contains("# visual_qa:"));
        check.contains("a real visual_qa key was appended", after, "\r\n  visual_qa:\r\n");
        check.contains("appended profiles", after, "    profiles: [codex-visual-qa]");
        check.contains("appended strategy defaults to first", after, "    strategy: first");
        check.contains("visual_qa requires an independent vendor", after,
                "    require_independent_vendor: true");
        check.that("review: still follows the roles block",
                after.indexOf("  visual_qa:") < after.indexOf("\nreview:"));
        check.that("failover comments survived", after.contains("on_quota_exhausted"));
        Policy parsed = Policy.parse(after, policy.toString());
        check.that("parser sees the new role", parsed.roles().containsKey("visual_qa"));
        check.eq("appended strategy", "first", parsed.roles().get("visual_qa").strategy());
        check.that("visual_qa independence", parsed.roles().get("visual_qa").requireIndependentVendor());
    }

    /** The shipped grok-review keeps its model as a label; give it the vendor's flag. */
    private static void forwardsModel(Path profile) throws Exception {
        String text = Files.readString(profile, StandardCharsets.UTF_8);
        Files.writeString(profile, text.replace("args:\n", "args:\n  - \"--model\"\n  - \"{{model}}\"\n"),
                StandardCharsets.UTF_8);
    }

    private void modelRemovesVerifiedOnUnlessKept(Check check, Path sandbox) throws Exception {
        Path drop = home(sandbox, "model-drop");
        Path grok = drop.resolve("profiles").resolve("grok-review.yaml");
        forwardsModel(grok);
        String original = Files.readString(grok, StandardCharsets.UTF_8);
        check.that("shipped grok profile is stamped", original.contains("verified_on"));

        RosterCommand.Outcome dropped = roster(drop, "roster", "model", "grok-review",
                "--model", "grok-4.20");
        check.that("model succeeds", dropped.ok());
        check.eq("model code", "roster_model", dropped.report().get("code"));
        check.eq("verify_with names the probe", "warden profiles --verify grok-review",
                dropped.report().get("verify_with"));
        String after = Files.readString(grok, StandardCharsets.UTF_8);
        check.contains("model line rewritten", after, "model: grok-4.20");
        check.that("verified_on is gone so the old probe cannot stamp a new model",
                !after.contains("verified_on"));
        check.contains("profile comments survived", after, "A label unless the args");
        Path backup = Path.of(String.valueOf(dropped.report().get("backup")));
        check.eq("profile backup equals original", original,
                Files.readString(backup, StandardCharsets.UTF_8));
        Profile.parse(after, grok.toString());

        Path keep = home(sandbox, "model-keep");
        Path keptFile = keep.resolve("profiles").resolve("grok-review.yaml");
        forwardsModel(keptFile);
        RosterCommand.Outcome kept = roster(keep, "roster", "model", "grok-review",
                "--model", "grok-4.20", "--keep-verified");
        check.that("keep-verified succeeds", kept.ok());
        String keptText = Files.readString(keptFile, StandardCharsets.UTF_8);
        check.contains("model still changes", keptText, "model: grok-4.20");
        check.contains("verified_on stays when asked", keptText, "verified_on");

        Path addEffort = home(sandbox, "model-effort");
        Path extra = addEffort.resolve("profiles").resolve("effort-review.yaml");
        // runner: orca allows effort without {{effort}} in args, so a parseable profile can
        // still be missing the effort key — the case "add the line after model" exists for.
        Files.writeString(extra, """
                version: 1
                profile: effort-review
                role: reviewer
                vendor: grok
                model: old-model
                command: grok
                runner: orca
                verification:
                  verified_on: "2026-01-01"
                """, StandardCharsets.UTF_8);
        RosterCommand.Outcome efforted = roster(addEffort, "roster", "model", "effort-review",
                "--model", "new-model", "--effort", "high");
        check.that("adding effort after model succeeds", efforted.ok());
        String effortText = Files.readString(extra, StandardCharsets.UTF_8);
        check.contains("effort inserted after model", effortText, "model: new-model\neffort: high\n");
        check.that("adding a model still drops the old stamp", !effortText.contains("verified_on"));
        Profile.parse(effortText, extra.toString());
    }

    /**
     * `roster model` used to rewrite the `model:` line and nothing else. Every hand-written
     * profile on the maintainer's machine passed `--model opus` literally in args and in its
     * probe, so a switch to a newer Opus renamed the profile, the vendor kept the old model,
     * and `profiles --verify` stamped the new name on a call to the old one.
     */
    private void modelReachesTheVendorOrIsRefused(Check check, Path sandbox) throws Exception {
        Path labelled = home(sandbox, "model-label");
        Path grok = labelled.resolve("profiles").resolve("grok-review.yaml");
        String untouched = Files.readString(grok, StandardCharsets.UTF_8);
        RosterCommand.Outcome refused = roster(labelled, "roster", "model", "grok-review",
                "--model", "grok-4.20");
        check.eq("a profile that passes no model is refused", "model_not_forwarded",
                refused.report().get("code"));
        check.contains("and told which line to add",
                String.valueOf(refused.report().get("message")), "\"{{model}}\"");
        check.eq("the file is untouched", untouched, Files.readString(grok, StandardCharsets.UTF_8));
        check.eq("and nothing was backed up", null, refused.report().get("backup"));

        Path literal = home(sandbox, "model-literal");
        Path opus = literal.resolve("profiles").resolve("literal-review.yaml");
        Files.writeString(opus, """
                version: 1
                profile: literal-review
                role: reviewer
                vendor: claude
                model: opus
                effort: xhigh
                command: claude
                runner: direct
                args:
                  - "-p"
                  # the model flag, pinned by hand
                  - "--model"
                  - "opus"
                  - "--effort"
                  - "{{effort}}"
                verification:
                  probe: 'claude -p "say ok" --model opus --output-format json'
                  verified_on: "2026-08-27"
                """, StandardCharsets.UTF_8);
        RosterCommand.Outcome switched = roster(literal, "roster", "model", "literal-review",
                "--model", "claude-opus-5-5");
        check.that("a literal model is switched", switched.ok());
        String text = Files.readString(opus, StandardCharsets.UTF_8);
        Profile parsed = Profile.parse(text, opus.toString());
        check.eq("the model line names the new model", "claude-opus-5-5", parsed.model());
        check.that("the args pass the placeholder the executor fills",
                parsed.args().contains("{{model}}"));
        check.that("and no longer ask for the old model", !parsed.args().contains("opus"));
        check.contains("the probe asks for the declared model too",
                parsed.verificationProbe(), "--model {{model}} --output-format");
        check.contains("the comment above the flag survived", text, "# the model flag, pinned by hand");
        check.that("the old stamp is dropped", !parsed.verified());
        check.eq("a probe that names the model draws no warning", null,
                switched.report().get("probe_warning"));

        Path flow = home(sandbox, "model-flow");
        Path flowFile = flow.resolve("profiles").resolve("flow-review.yaml");
        Files.writeString(flowFile, """
                version: 1
                profile: flow-review
                role: reviewer
                vendor: codex
                model: gpt-6-astra
                command: codex
                runner: direct
                args: ["exec", "-m", "gpt-6-astra", "--json"]
                """, StandardCharsets.UTF_8);
        RosterCommand.Outcome flowed = roster(flow, "roster", "model", "flow-review",
                "--model", "gpt-6-luna");
        check.that("a flow-style args list is switched too", flowed.ok());
        Profile flowParsed = Profile.parse(Files.readString(flowFile, StandardCharsets.UTF_8),
                flowFile.toString());
        check.eq("with the placeholder in place of the old model",
                List.of("exec", "-m", "{{model}}", "--json"), flowParsed.args());
        check.contains("a profile with no probe is told it has nothing to verify with",
                String.valueOf(flowed.report().get("probe_warning")), "no verification.probe");
    }

    private void effortWithoutPlaceholderIsRefused(Check check, Path home) throws Exception {
        Path claude = home.resolve("profiles").resolve("claude-review.yaml");
        String original = Files.readString(claude, StandardCharsets.UTF_8);
        RosterCommand.Outcome outcome = roster(home, "roster", "model", "claude-review",
                "--model", "opus", "--effort", "high");
        check.that("effort without {{effort}} is refused before a write", !outcome.ok());
        check.contains("same wording Profile.parse uses",
                String.valueOf(outcome.report().get("message")),
                "runner: direct effort requires {{effort}} in args; replace the literal effort value");
        check.eq("claude-review is untouched", original,
                Files.readString(claude, StandardCharsets.UTF_8));
        check.eq("nothing was backed up", null, outcome.report().get("backup"));
    }

    private void failedReparseRestoresOriginalBytes(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        byte[] original = Files.readAllBytes(policy);
        String broken = Files.readString(policy, StandardCharsets.UTF_8)
                .replace("version: 1", "version: 9");
        RosterCommand.Outcome outcome = new RosterCommand().rewriteAndReparse(
                policy, broken, "policy", "roster_set",
                List.of("version: 1"), List.of("version: 9"), Map.of());
        check.that("a parser refusal is not ok", !outcome.ok());
        check.eq("code", "reparse_failed", outcome.report().get("code"));
        check.contains("parser message is the failure",
                String.valueOf(outcome.report().get("message")), "version must be 1");
        check.that("the old file is byte-identical after restore",
                Arrays.equals(original, Files.readAllBytes(policy)));
        Path backup = Path.of(String.valueOf(outcome.report().get("backup")));
        check.that("the backup was not deleted", Files.isRegularFile(backup));
        check.that("the backup still equals the original",
                Arrays.equals(original, Files.readAllBytes(backup)));
    }

    private void allowMissingAndMismatch(Check check, Path home) throws Exception {
        Path policy = home.resolve("policy.yaml");
        byte[] before = Files.readAllBytes(policy);

        RosterCommand.Outcome mismatch = roster(home, "roster", "set", "reviewer",
                "--profiles", "codex-implement");
        check.eq("a profile for another role is refused", "profile_role_mismatch",
                mismatch.report().get("code"));
        check.that("mismatch leaves policy.yaml", Arrays.equals(before, Files.readAllBytes(policy)));

        RosterCommand.Outcome allowed = roster(home, "roster", "set", "reviewer",
                "--profiles", "ghost-review", "--allow-missing");
        check.that("--allow-missing writes a name with no file yet", allowed.ok());
        String after = Files.readString(policy, StandardCharsets.UTF_8);
        check.contains("ghost profile is now on the role", after,
                "    profiles: [ghost-review]");

        RosterCommand.Outcome listed = roster(home, "roster");
        check.contains("dangling_policy_references names the gap",
                String.valueOf(listed.report().get("dangling_policy_references")),
                "reviewer -> ghost-review");

        RosterCommand.Outcome planner = roster(home, "roster", "set", "planner",
                "--profiles", "draft-planner", "--allow-missing");
        check.that("planner can be appended", planner.ok());
        String withPlanner = Files.readString(policy, StandardCharsets.UTF_8);
        check.contains("planner does not require an independent vendor", withPlanner,
                "    require_independent_vendor: false");

        RosterCommand.Outcome strategy = roster(home, "roster", "set", "implementer",
                "--profiles", "codex-implement", "--strategy", "rotate");
        check.that("strategy rewrite succeeds", strategy.ok());
        String rotated = Files.readString(home.resolve("policy.yaml"), StandardCharsets.UTF_8);
        check.contains("strategy line rewritten", rotated, "    strategy: rotate");
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) {}
                }
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            }
        }
    }
}
