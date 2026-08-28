package dev.warden;

import dev.warden.config.Policy;
import dev.warden.config.Workflow;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;

/**
 * The declared chain of stages.
 *
 * The value of moving the chain into configuration is entirely in what the parser refuses.
 * A workflow file decides which vendor is dispatched with write access and in what order, so
 * every one of these checks is a way that file could otherwise mean something its author did
 * not write.
 */
public final class WorkflowTest implements Suite {

    @Override public String name() { return "workflow"; }

    private static final String ROLES = """
            version: 1
            roles:
              implementer: { profiles: [a], strategy: first }
              reviewer: { profiles: [b], strategy: first }
              visual_qa: { profiles: [c], strategy: first }
            """;

    @Override public void run(Check check) {
        Policy silent = Policy.parse(ROLES, "policy.yaml");
        check.that("a policy that declares no workflow is not treated as declaring one",
                !silent.workflowDeclared());
        check.eq("and gets the documented chain", Workflow.builtIn().toList(),
                silent.workflow().toList());

        List<Workflow.Stage> builtIn = Workflow.builtIn().stages();
        check.eq("the built-in chain runs five stages", 5, builtIn.size());
        check.eq("gates recheck after any later fix round", true,
                builtIn.stream().filter(stage -> stage.name().equals("gates")).findFirst()
                        .orElseThrow().recheckAfterFix());
        // The reviewer is in this list on purpose. A fix round for the browser or the
        // visual role edits code after the review passed, and without re-running it the
        // candidate a human accepts holds a diff no independent vendor ever read.
        check.eq("every judging stage before the last one is re-run after a fix",
                List.of("gates", "review", "browser"),
                Workflow.builtIn().recheckBefore(4).stream().map(Workflow.Stage::name).toList());

        // Failure names are what an operator greps for. They follow the stage's job, not the
        // name an operator happened to give it, so renaming a stage cannot rename its reason.
        Policy renamed = Policy.parse(ROLES + """
                workflow:
                  stages:
                    - { stage: build-it, run: role, role: implementer, on_fail: stop }
                    - { stage: prove-it, run: machine_gates, on_fail: fix }
                """, "policy.yaml");
        check.that("a declared workflow is reported as declared", renamed.workflowDeclared());
        check.eq("a renamed gate still stops for the same reason", "gates_not_satisfied",
                renamed.workflow().stages().get(1).failureReason());
        check.eq("and still writes the same fix-context file", "gates",
                renamed.workflow().stages().get(1).contextKind());
        check.eq("a renamed implementer stage keeps its reason", "implementer_failed",
                renamed.workflow().stages().get(0).failureReason());

        // Order is the whole point of declaring the chain, so it is preserved verbatim.
        Policy reordered = Policy.parse(ROLES + """
                workflow:
                  stages:
                    - { stage: review, run: role, role: reviewer, on_fail: stop, on_findings: fix }
                    - { stage: implement, run: role, role: implementer, on_fail: stop }
                    - { stage: gates, run: machine_gates, on_fail: fix }
                """, "policy.yaml");
        check.eq("stages keep the order they were written in",
                List.of("review", "implement", "gates"),
                reordered.workflow().stages().stream().map(Workflow.Stage::name).toList());

        // Conditions are a closed set. An unknown one must not silently evaluate to false,
        // because false is the permissive answer here: it skips a check nobody asked to skip.
        check.rejects("an unknown condition is refused, not ignored", "unknown condition",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: gates, run: machine_gates, on_fail: fix, when: [on_tuesdays] }
                        """, "policy.yaml"));
        check.rejects("an unknown stage kind is refused", "must declare run",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: guess, run: vibes, on_fail: stop }
                        """, "policy.yaml"));
        check.rejects("an unknown response is refused", "on_fail must be one of",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: gates, run: machine_gates, on_fail: land }
                        """, "policy.yaml"));
        check.rejects("a role stage must name its role", "must name it",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: someone, run: role, on_fail: stop }
                        """, "policy.yaml"));
        check.rejects("an unknown role is refused", "unknown role",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: someone, run: role, role: janitor, on_fail: stop }
                        """, "policy.yaml"));
        check.rejects("two stages cannot share a name", "declared twice",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: gates, run: machine_gates, on_fail: fix }
                            - { stage: gates, run: machine_gates, on_fail: fix }
                        """, "policy.yaml"));
        check.rejects("an empty workflow is refused rather than silently doing nothing",
                "at least one stage",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages: []
                        """, "policy.yaml"));
        check.rejects("a machine stage cannot claim to produce findings", "remove on_findings",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: gates, run: machine_gates, on_fail: fix, on_findings: fix }
                        """, "policy.yaml"));
        check.rejects("an unsupported key in a stage is a typo, not a feature", "unsupported key",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: gates, run: machine_gates, on_fail: fix, retries: 9 }
                        """, "policy.yaml"));

        // The one role with eyes cannot be dispatched over pixels nobody took. Binding it to
        // a named harness stage is what stops "we ran visual QA" from meaning "we paid a
        // model to read a report about screenshots that were never captured".
        check.rejects("a visual_qa role with no harness before it is refused",
                "no visual_harness stage precedes it",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: look, run: role, role: visual_qa, on_fail: stop }
                        """, "policy.yaml"));
        check.rejects("and it cannot look at a harness that runs later", "must name an earlier",
                () -> Policy.parse(ROLES + """
                        workflow:
                          stages:
                            - { stage: look, run: role, role: visual_qa, sees: browser, on_fail: stop }
                            - { stage: browser, run: visual_harness, on_fail: fix }
                        """, "policy.yaml"));

        Policy bound = Policy.parse(ROLES + """
                workflow:
                  stages:
                    - { stage: shots, run: visual_harness, on_fail: fix }
                    - { stage: look, run: role, role: visual_qa, on_fail: stop, on_findings: fix }
                """, "policy.yaml");
        check.eq("a visual_qa role binds to the harness before it without being told",
                "shots", bound.workflow().stages().get(1).sees());
        check.eq("and keeps its own stop reason", "visual_qa_role_failed",
                bound.workflow().stages().get(1).failureReason());
        check.eq("and its own findings reason", "visual_findings_remain",
                bound.workflow().stages().get(1).findingsReason());

        // The shipped policy template must actually be one this parser accepts. A commented
        // example that does not parse is worse than none: it is discovered by an operator.
        Policy shipped = Policy.parse(dev.warden.config.UserSetup.policyTemplate(), "policy.yaml");
        check.that("the policy `warden setup` writes declares a workflow", shipped.workflowDeclared());
        check.eq("and it is exactly the built-in chain, written out",
                Workflow.builtIn().toList(), shipped.workflow().toList());
    }
}
