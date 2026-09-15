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

    /**
     * A Claude Code CLI call stopped by the operator's session limit, 2026-09-15. The whole of
     * stdout was this one envelope, with the timing and usage fields removed; stderr was empty.
     * Its type is {@code result} and its subtype is {@code success}, so the only structured
     * sign of failure is {@code is_error} and the HTTP status. Three runs recorded this as
     * {@code role_command_failed}, and a retry re-bought every reading it had already paid for.
     */
    private static final String CLAUDE_SESSION_LIMIT =
            "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":429,"
                    + "\"num_turns\":8,\"result\":\"You've hit your session limit \\u00b7 resets 10:50am "
                    + "(America/New_York)\"}";

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
        claudeEnvelopeChecks(check);
        turnCeilingChecks(check);
    }

    /**
     * Claude reports failure inside a result envelope rather than as an error event. The same
     * envelope carries the model's answer under {@code result} when the call succeeded, so the
     * key may only be read when the vendor itself flagged the envelope as an error.
     */
    private void claudeEnvelopeChecks(Check check) {
        QuotaSignal.Detection claude = QuotaSignal.detect(List.of(), CLAUDE_SESSION_LIMIT, "");
        check.that("a Claude session limit is recognised", claude.matched());
        check.eq("from the envelope the vendor flagged as an error",
                "structured_error_event", claude.detectedBy());
        check.eq("by its own wording", "session limit", claude.signature());
        check.contains("with the reset time kept verbatim", claude.evidence(), "resets 10:50am");

        String answerAboutLimits = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
                + "\"result\":\"{\\\"verdict\\\":\\\"fail\\\",\\\"summary\\\":\\\"The client ignores the "
                + "session limit and the usage limit replies\\\"}\"}";
        check.that("a successful answer that discusses limits is not a refusal",
                !QuotaSignal.detect(List.of(), answerAboutLimits, "").matched());

        String turnCeiling = "{\"type\":\"result\",\"subtype\":\"error_max_turns\",\"is_error\":true,"
                + "\"num_turns\":40}";
        check.that("an error envelope with no quota wording stays an ordinary failure",
                !QuotaSignal.detect(List.of(), turnCeiling, "").matched());
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
