package dev.warden;

import dev.warden.execution.QuotaSignal;
import dev.warden.execution.DirectCliExecutor;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;

/**
 * Classifying a spent subscription.
 *
 * The transcript in {@link #CODEX_TRANSCRIPT} is copied from a real Codex run that had run out
 * of quota, with only the URLs replaced. It is the reason this class exists: nothing in it is
 * distinguishable from an ordinary failure except the prose, the prose is on stdout, and the
 * exit code is the same 1 a mistyped flag produces.
 *
 * The last two cases matter more than the first. Getting a quota diagnosis wrong in the
 * hopeful direction re-routes a run to a second vendor and pays it to fail the same way, so
 * a review that merely discusses rate limiting must not be mistaken for a review that hit one.
 */
public final class QuotaSignalTest implements Suite {

    @Override public String name() { return "quota signal"; }

    private static final String CODEX_TRANSCRIPT = """
            {"type":"thread.started","thread_id":"01a0404c-88f5-72d0-bbfe-5c822dafb513"}
            {"type":"turn.started"}
            {"type":"error","message":"You've hit your usage limit. Upgrade to Pro \
            (https://example.invalid/pro), visit https://example.invalid/usage to purchase more \
            credits or try again at 9:21 PM."}
            {"type":"turn.failed","error":{"message":"You've hit your usage limit. Upgrade to Pro \
            (https://example.invalid/pro), visit https://example.invalid/usage to purchase more \
            credits or try again at 9:21 PM."}}
            """;

    /** What Codex actually put on stderr during that run: nothing about the quota. */
    private static final String CODEX_STDERR = """
            Reading additional input from stdin...
            ERROR codex_models_manager::manager: failed to refresh available models: timeout
            ERROR rmcp::transport::worker: worker quit with fatal: Transport channel closed
            """;

    @Override public void run(Check check) {
        QuotaSignal.Detection codex = QuotaSignal.detect(List.of(), CODEX_TRANSCRIPT, CODEX_STDERR);
        check.that("a live Codex usage limit is recognised", codex.matched());
        check.eq("from the structured event, not from loose text",
                "structured_error_event", codex.detectedBy());
        check.eq("and the phrase that decided it is named", "usage limit", codex.signature());
        check.contains("the vendor's own words are kept, retry window included",
                codex.evidence(), "try again at 9:21 PM");
        check.that("no retry instant is invented from a wording that has no date",
                !codex.report().containsKey("retry_at"));

        // A vendor may put its refusal only on stderr; the weaker source is admitted as such.
        QuotaSignal.Detection stderrOnly = QuotaSignal.detect(List.of(), "",
                "Error: 429 Too Many Requests (rate limit exceeded)");
        check.that("a refusal that exists only as stderr text is still caught", stderrOnly.matched());
        check.eq("and is labelled by the weaker evidence it came from",
                "stderr_text", stderrOnly.detectedBy());

        // Profile-supplied wording extends the defaults rather than replacing them.
        String house = "{\"type\":\"error\",\"message\":\"plan allowance spent for this window\"}";
        check.that("an unknown wording is not guessed at",
                !QuotaSignal.detect(List.of(), house, "").matched());
        check.that("a profile can teach Warden its vendor's wording",
                QuotaSignal.detect(List.of("plan allowance spent"), house, "").matched());
        check.that("and teaching one wording does not un-teach the built-in ones",
                QuotaSignal.detect(List.of("plan allowance spent"), CODEX_TRANSCRIPT, "").matched());

        // The false positive that would cost money: a review that talks about rate limits.
        String reviewOfQuotaCode = """
                chatter the vendor prints first
                {"text":"{\\"verdict\\":\\"fail\\",\\"summary\\":\\"The retry path ignores the \
                usage limit response and the 429 Too Many Requests branch is untested\\"}"}
                """;
        check.that("an ordinary answer that discusses rate limiting is not a quota refusal",
                !QuotaSignal.detect(List.of(), reviewOfQuotaCode, "").matched());
        check.that("the model's own words on stdout are never the deciding evidence",
                !QuotaSignal.detect(List.of(), "I could not finish: the API returned a rate limit", "")
                        .matched());
        check.that("a bare mention of the word quota decides nothing, even on stderr",
                !QuotaSignal.detect(List.of(), "", "recomputing the disk quota table").matched());
        turnCeilingChecks(check);
    }

    /**
     * A vendor stopped by its own turn ceiling is not a vendor that failed at the work.
     * Measured: a review of a 126-line diff used all 16 turns reading it and exited 1 with
     * no artifact, and the run called that `role_command_failed` — sending an operator to
     * read a transcript in which nothing had gone wrong.
     */
    private void turnCeilingChecks(Check check) {
        check.that("the vendor's own words are what identify it",
                DirectCliExecutor.turnsExhausted("", "Error: max turns reached"));
        check.that("wherever they appear",
                DirectCliExecutor.turnsExhausted("... Error: max turns reached\n", ""));
        check.that("and in the other spellings vendors use",
                DirectCliExecutor.turnsExhausted("", "reached the maximum number of turns"));
        // Narrow on purpose. Inferring this from "exit 1 with no artifact" would swallow
        // every real crash into a code that tells the operator to raise a limit.
        check.that("an ordinary failure is not mistaken for one",
                !DirectCliExecutor.turnsExhausted("Traceback: connection reset", "exit status 1"));
        check.that("nor is silence", !DirectCliExecutor.turnsExhausted("", ""));
    }
}
