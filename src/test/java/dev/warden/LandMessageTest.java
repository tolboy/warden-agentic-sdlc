package dev.warden;

import dev.warden.run.LandCommand;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;
import java.util.Map;

/**
 * The one sentence in a commit message that makes a claim about the run behind it.
 *
 * It goes into the repository's history, where a reader has nothing to check it against, so
 * every clause has to be answerable from the report the run actually wrote. These checks are
 * about the shapes that sentence can be asked to describe, including the two it used to get
 * wrong by saying nothing: a stage that was skipped, and a stage that ran and failed.
 */
public final class LandMessageTest implements Suite {

    @Override public String name() { return "land-message"; }

    @Override public void run(Check check) {
        String green = LandCommand.checksFrom(report(
                List.of(stage("implementer", true), stage("gates", true), stage("reviewer", true)),
                List.of()));
        check.contains("a green run names every stage that passed", green,
                "implementer, gates and reviewer passed");
        check.that("and grows no empty clause about skipping", !green.contains("skipped"));
        check.that("nor about failing", !green.contains("failed"));

        String skipped = LandCommand.checksFrom(report(
                List.of(stage("implementer", true), stage("gates", true)),
                List.of(skip("browser", "condition_not_met:visual_qa_required"),
                        skip("look", "condition_not_met:visual_qa_required"))));
        check.contains("a skipped stage is named", skipped, "browser and look were skipped");
        check.contains("with the reason the run recorded", skipped,
                "(condition_not_met:visual_qa_required)");
        check.that("and is not counted among the stages that passed",
                skipped.contains("implementer and gates passed"));

        // The gap this suite was written for. A stage that ran and did not pass was in neither
        // list, so the sentence simply did not mention it.
        String broke = LandCommand.checksFrom(report(
                List.of(stage("implementer", true), stage("gates", false), stage("reviewer", true)),
                List.of()));
        check.contains("a stage that ran and failed is named", broke, "gates failed");
        check.that("first, because it is what a reader must not miss",
                broke.startsWith("gates failed"));
        check.that("and not among the ones that passed",
                broke.contains("implementer and reviewer passed"));

        // A fix round runs the same stage twice. The verdict is the last one, not the first.
        String recovered = LandCommand.checksFrom(report(
                List.of(stage("gates", false), stage("implementer", true), stage("gates", true)),
                List.of()));
        check.that("a stage that failed and then passed is not reported as failed",
                !recovered.contains("failed"));
        check.contains("it passed, and is named once", recovered, "gates and implementer passed");

        check.eq("a report with no stages at all makes no claim", "",
                LandCommand.checksFrom(report(List.of(), List.of())));
    }

    private static Map<String, Object> report(List<Object> stages, List<Object> skipped) {
        return Map.of("stages", stages, "skipped_stages", skipped);
    }

    private static Object stage(String step, boolean ok) {
        return Map.of("step", step, "ok", ok);
    }

    private static Object skip(String stage, String reason) {
        return Map.of("stage", stage, "reason", reason);
    }
}
