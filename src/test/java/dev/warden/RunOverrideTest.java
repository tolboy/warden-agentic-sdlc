package dev.warden;

import dev.warden.config.Profile;
import dev.warden.config.RunOverride;
import dev.warden.config.TaskSpec;
import dev.warden.config.Workflow;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;
import java.util.Map;

/**
 * Per-run overlays must not rewrite ~/.warden, and a Unity task must be able to require
 * visual QA without inventing a browser scenario.
 */
public final class RunOverrideTest implements Suite {

    @Override public String name() { return "run-override"; }

    @Override public void run(Check check) {
        fromArgs(check);
        taskYaml(check);
        cliWinsOverTask(check);
        hostOverlayFindsTheDeclaredTwin(check);
        effortMustReachTheVendor(check);
        survivesTheProcessHop(check);
        agentEvidenceNeedsNoBrowserScenario(check);
        agentEvidenceRewritesTheChain(check);
        bumpRunIds(check);
    }

    private void fromArgs(Check check) {
        RunOverride overlay = RunOverride.fromArgs(new String[] {
                "run", "level-bakery",
                "--use", "review-second=claude-review",
                "--effort", "review-second=xhigh",
                "--host", "review-second=orca"
        });
        check.eq("pin", "claude-review", overlay.toMap().get("profile") instanceof Map<?, ?> map
                ? map.get("review-second") : null);
        check.eq("effort", "xhigh", overlay.toMap().get("effort") instanceof Map<?, ?> map
                ? map.get("review-second") : null);
        check.eq("host", "orca", overlay.toMap().get("host") instanceof Map<?, ?> map
                ? map.get("review-second") : null);
        check.rejects("host is only orca", "only overlay runner is orca",
                () -> RunOverride.fromArgs(new String[] {"--host", "look=direct"}));
        check.rejects("malformed pair", "stage=value",
                () -> RunOverride.fromArgs(new String[] {"--use", "review-second"}));
        check.that("absent flags are empty", RunOverride.fromArgs(new String[] {"run", "t"}).isEmpty());
    }

    private void taskYaml(Check check) {
        TaskSpec spec = TaskSpec.parse("""
                version: 1
                id: bakery
                goal: add a level
                risk: medium
                scope: [Assets]
                checks: [compile]
                use:
                  review-second:
                    profile: claude-review
                    effort: xhigh
                    host: orca
                  look:
                    profile: claude-visual-qa-mcp
                """, "task.yaml");
        Map<String, Object> use = spec.use().toMap();
        check.eq("task pin", "claude-review", nested(use, "profile", "review-second"));
        check.eq("task host", "orca", nested(use, "host", "review-second"));
        check.eq("look pin", "claude-visual-qa-mcp", nested(use, "profile", "look"));
        check.rejects("unknown use key", "unsupported key",
                () -> TaskSpec.parse("""
                        version: 1
                        id: bakery
                        goal: add a level
                        risk: medium
                        scope: [Assets]
                        checks: [compile]
                        use:
                          review-second:
                            model: opus
                        """, "task.yaml"));
    }

    private void cliWinsOverTask(Check check) {
        RunOverride task = RunOverride.fromArgs(new String[] {
                "--effort", "review-second=max", "--host", "review-second=orca"});
        RunOverride cli = RunOverride.fromArgs(new String[] {
                "--effort", "review-second=xhigh"});
        RunOverride merged = task.merged(cli);
        check.eq("later overlay wins", "xhigh", nested(merged.toMap(), "effort", "review-second"));
        check.eq("untouched keys remain", "orca", nested(merged.toMap(), "host", "review-second"));
    }

    /**
     * The host overlay moves a stage to a profile the operator declared for Orca. It does
     * not turn a grant-carrying direct profile into one, because worker-start forwards
     * agent, model and effort and nothing else.
     */
    private void hostOverlayFindsTheDeclaredTwin(Check check) {
        Profile direct = Profile.parse("""
                version: 1
                profile: claude-review
                role: reviewer
                vendor: claude
                model: opus
                effort: max
                command: claude
                runner: direct
                read_only: true
                args: ["-p", "--effort", "{{effort}}", "--allowedTools", "Read,Grep"]
                verification:
                  verified_on: "2026-09-01"
                """, "claude-review.yaml");
        Profile twin = Profile.parse("""
                version: 1
                profile: orca-claude-review
                role: reviewer
                vendor: claude
                model: opus
                effort: max
                command: claude
                runner: orca
                prompt_delivery: workspace_file
                read_only: true
                verification:
                  verified_on: "2026-09-01"
                """, "orca-claude-review.yaml");
        RunOverride host = RunOverride.fromArgs(new String[] {"--host", "review-second=orca"});
        Map<String, Profile> roster = Map.of(direct.name(), direct, twin.name(), twin);

        check.eq("the declared twin runs the stage", "orca-claude-review",
                host.adapt("review-second", direct, roster).name());
        check.eq("nothing to refuse", null, host.problem("review-second", direct, roster));
        check.eq("the home profile is untouched", "direct", direct.runner());

        Map<String, Profile> alone = Map.of(direct.name(), direct);
        check.eq("without a twin the grants are not silently dropped", "claude-review",
                host.adapt("review-second", direct, alone).name());
        String refusal = host.problem("review-second", direct, alone);
        check.that("and the refusal names what would be lost",
                refusal != null && refusal.contains("tool grants") && refusal.contains("runner: orca"));

        Profile unstamped = Profile.parse("""
                version: 1
                profile: orca-claude-review
                role: reviewer
                vendor: claude
                model: opus
                command: claude
                runner: orca
                prompt_delivery: workspace_file
                read_only: true
                """, "orca-claude-review.yaml");
        String unverified = host.problem("review-second", direct,
                Map.of(direct.name(), direct, unstamped.name(), unstamped));
        check.that("a prepared but unstamped twin is told to be stamped, not written again",
                unverified != null && unverified.contains("--verify orca-claude-review"));

        Profile bare = Profile.parse("""
                version: 1
                profile: bare-review
                role: reviewer
                vendor: claude
                model: opus
                command: claude
                runner: direct
                read_only: true
                verification:
                  verified_on: "2026-09-01"
                """, "bare-review.yaml");
        Profile hosted = host.adapt("review-second", bare, Map.of(bare.name(), bare));
        check.eq("a direct stamp cannot verify another transport", "direct", hosted.runner());
        check.that("bare profile needs an explicitly verified twin",
                host.problem("review-second", bare, Map.of()) != null);
        check.eq("a dispatch without a stage name is not an overlay miss",
                "direct", RunOverride.NONE.adapt(null, direct, roster).runner());
    }

    /**
     * An effort Warden cannot deliver is worse than none: the evidence would claim it. The
     * overlay is held to exactly the rules {@code Profile.parse} applies to a file.
     */
    private void effortMustReachTheVendor(Check check) {
        Profile carries = Profile.parse("""
                version: 1
                profile: claude-review
                role: reviewer
                vendor: claude
                model: opus
                effort: max
                command: claude
                runner: direct
                read_only: true
                args: ["-p", "--effort", "{{effort}}"]
                verification:
                  verified_on: "2026-09-01"
                """, "claude-review.yaml");
        Profile silent = Profile.parse("""
                version: 1
                profile: codex-review
                role: reviewer
                vendor: openai
                model: gpt-5
                command: codex
                runner: direct
                read_only: true
                args: ["exec", "--model", "{{model}}"]
                verification:
                  verified_on: "2026-09-01"
                """, "codex-review.yaml");
        RunOverride overlay = RunOverride.fromArgs(new String[] {"--effort", "review=xhigh"});

        check.eq("a profile that forwards it gets it", "xhigh",
                overlay.adapt("review", carries, Map.of()).effort());
        check.eq("and is not refused", null, overlay.problem("review", carries, Map.of()));

        check.eq("a profile that cannot forward it is left alone", null,
                overlay.adapt("review", silent, Map.of()).effort());
        String refusal = overlay.problem("review", silent, Map.of());
        check.that("and the run is stopped with the reason",
                refusal != null && refusal.contains("{{effort}}"));
    }

    /** The overlay a `--conductor` run leaves behind is the overlay its inner run reads. */
    private void survivesTheProcessHop(Check check) {
        RunOverride typed = RunOverride.fromArgs(new String[] {
                "--use", "review-second=claude-review",
                "--effort", "review-second=xhigh",
                "--host", "look=orca"});
        RunOverride read = RunOverride.fromMap(typed.toMap());
        check.eq("pin survives", "claude-review", nested(read.toMap(), "profile", "review-second"));
        check.eq("effort survives", "xhigh", nested(read.toMap(), "effort", "review-second"));
        check.eq("host survives", "orca", nested(read.toMap(), "host", "look"));
        check.that("an absent file is no overlay", RunOverride.fromMap(Map.of()).isEmpty());
        check.that("and the block round-trips into a contract",
                typed.toYaml().contains("use:") && typed.toYaml().contains("profile: claude-review"));
    }

    private void agentEvidenceNeedsNoBrowserScenario(Check check) {
        TaskSpec spec = TaskSpec.parse("""
                version: 1
                id: bakery
                goal: add a level
                risk: medium
                scope: [Assets]
                checks: [compile]
                visual_qa:
                  required: true
                  evidence: agent
                  scenarios:
                    - "play Bakery and photograph the hop"
                """, "task.yaml");
        check.that("agent evidence is required without a viewport grammar", spec.visualQa().required());
        check.that("and names the agent camera", spec.visualQa().agentEvidence());
        TaskSpec bare = TaskSpec.parse("""
                version: 1
                id: bakery
                goal: add a level
                risk: medium
                scope: [Assets]
                checks: [compile]
                visual_qa:
                  required: true
                  evidence: agent
                """, "task.yaml");
        check.that("prose scenarios are optional for an agent camera",
                bare.visualQa().required() && bare.visualQa().scenarios().isEmpty());
        check.rejects("harness still needs a scenario", "scenarios must not be empty",
                () -> TaskSpec.parse("""
                        version: 1
                        id: bakery
                        goal: add a level
                        risk: medium
                        scope: [Assets]
                        checks: [compile]
                        visual_qa:
                          required: true
                        """, "task.yaml"));
    }

    private void agentEvidenceRewritesTheChain(Check check) {
        Workflow policy = Workflow.builtIn();
        check.that("the documented chain still has a browser harness",
                policy.stages().stream().anyMatch(stage ->
                        stage.kind() == Workflow.Kind.VISUAL_HARNESS));
        Workflow agent = policy.forAgentEvidence();
        check.that("a Unity task does not pay for CDP",
                agent.stages().stream().noneMatch(stage ->
                        stage.kind() == Workflow.Kind.VISUAL_HARNESS));
        Workflow.Stage look = agent.stages().stream()
                .filter(stage -> "visual_qa".equals(stage.role()))
                .findFirst().orElseThrow();
        check.that("the visual role takes its own pictures", look.acquiresEvidence());
        check.eq("and does not point at a harness that is not there", null, look.sees());
    }

    private void bumpRunIds(Check check) {
        check.eq("trailing number", "bakery-4", Main.bumpRunId("bakery-3"));
        check.eq("no number", "run-abc-2", Main.bumpRunId("run-abc"));
    }

    private static Object nested(Map<String, Object> map, String a, String b) {
        Object inner = map.get(a);
        return inner instanceof Map<?, ?> nested ? nested.get(b) : null;
    }
}
