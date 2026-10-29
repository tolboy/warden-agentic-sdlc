package dev.warden.execution;

import dev.warden.config.Connection;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One logical API call: a bounded, counted series of attempts sharing one deadline.
 *
 * Every attempt is recorded with the same correlation id, its number, profile and stage, how
 * far it got ({@code delivery}), how long it took, the provider's request id and whatever
 * usage and cost it reported. Only three outcomes may be sent again, and only when the policy
 * names them: a request that provably never left this machine ({@code not_sent}), a 429 whose
 * {@code Retry-After} names a whole number of seconds under the policy's cap, and a 502/503
 * from a gateway. An authentication refusal, a model mismatch, a truncated answer, a cancel
 * and every unknown outcome stop at once: resending those either cannot help or could pay
 * twice for one answer.
 */
public final class ApiRetry {
    /** Hard ceiling, whatever a policy says: three attempts per logical call. */
    public static final int MAX_RETRIES = 2;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public static final Sleeper REAL_SLEEP = Thread::sleep;

    /**
     * What may be resent.
     *
     * @param name          recorded with every logical call so a report says which applied
     * @param maxRetries    extra attempts after the first, clamped to {@link #MAX_RETRIES}
     * @param notSent       resend a request that never reached the provider
     * @param rateLimited   resend a 429 that named a short whole-second {@code Retry-After}
     * @param gateway       resend a 502 or 503
     * @param maxRetryAfter the longest {@code Retry-After} worth waiting for inside one call
     * @param backoff       pause before a resend that named no wait, multiplied by the attempt
     */
    public record Policy(String name, int maxRetries, boolean notSent, boolean rateLimited,
                         boolean gateway, Duration maxRetryAfter, Duration backoff) {
        public Policy {
            maxRetries = Math.max(0, Math.min(MAX_RETRIES, maxRetries));
        }

        /** No resend at all. */
        public static final Policy NONE = new Policy("none", 0, false, false, false,
                Duration.ZERO, Duration.ZERO);
        /** Roles, read-only and writing alike: only a request that never left. */
        public static final Policy NOT_SENT_ONLY = new Policy("not_sent_only", MAX_RETRIES, true,
                false, false, Duration.ZERO, Duration.ofMillis(500));
        /** Availability checks: a diagnostic, so transient gateway trouble may be asked again. */
        public static final Policy CHECK = new Policy("check", MAX_RETRIES, true, true, true,
                Duration.ofSeconds(10), Duration.ofSeconds(1));
    }

    /**
     * The result of the series. Exactly one of {@code reply} and {@code failure} is set: the
     * last attempt's complete reply (any HTTP status), or the last attempt's failure.
     */
    public record Outcome(ApiExecutor.HttpReply reply, ApiExecutor.CallFailure failure,
                          List<Map<String, Object>> attempts, Map<String, Object> correlation,
                          Policy policy, String stoppedBecause) {
        /** True when no attempt of the series could have reached the provider. */
        public boolean nothingSent() {
            return attempts.stream().allMatch(row -> "not_sent".equals(row.get("delivery")));
        }

        /** The attempt log and its summary, in the shape role and check receipts carry. */
        public void record(Map<String, Object> evidence) {
            evidence.put("correlation_id", correlation.get("correlation_id"));
            evidence.put("api_attempts", attempts);
            evidence.put("api_attempt_count", (long) attempts.size());
            evidence.put("api_retries", (long) Math.max(0, attempts.size() - 1));
            evidence.put("api_retry_policy", policy.name());
            if (stoppedBecause != null) evidence.put("api_retry_stopped", stoppedBecause);
            if (failure != null) failure.describe(evidence);
            else evidence.put("delivery", "answered");
            Object requestId = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1).get("request_id");
            if (requestId != null) evidence.putIfAbsent("request_id", requestId);
        }

        /**
         * Reconcile the cost the final reply reported with every earlier attempt that may also
         * have been billed. An attempt that never left is free. Any other attempt without a
         * reported cost makes the total unknown: it is never counted as zero.
         */
        public void settleCost(Map<String, Object> evidence) {
            if (attempts.size() <= 1) return; // one attempt: the receipt already says it exactly
            double known = 0;
            int unpriced = 0;
            for (Map<String, Object> row : attempts) {
                if ("not_sent".equals(row.get("delivery"))) continue;
                if (row.get("cost_usd") instanceof Number cost) known += cost.doubleValue();
                else unpriced++;
            }
            if (unpriced == 0) {
                evidence.put("cost_usd", known);
                evidence.put("cost_status", "reported");
                return;
            }
            evidence.remove("cost_usd");
            evidence.put("cost_status", "unknown");
            evidence.put("unpriced_attempts", (long) unpriced);
            if (known > 0) evidence.put("reported_cost_usd_subtotal", known);
        }
    }

    private ApiRetry() {}

    /** The fields that tie each attempt to the profile and stage that asked for it. */
    public static Map<String, Object> correlation(String runId, String profile, String stage,
                                                  String evidenceName) {
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("correlation_id", UUID.randomUUID().toString());
        if (runId != null) correlation.put("run_id", runId);
        if (profile != null) correlation.put("profile", profile);
        if (stage != null) correlation.put("stage", stage);
        if (evidenceName != null) correlation.put("call", evidenceName);
        return correlation;
    }

    public static Outcome send(Connection connection, String method, URI uri, String body, String token,
                               Duration limit, Policy policy, Sleeper sleeper,
                               Map<String, Object> correlation) {
        long deadline = System.nanoTime() + limit.toNanos();
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (int attempt = 1; ; attempt++) {
            long started = System.nanoTime();
            Duration remaining = Duration.ofNanos(Math.max(1, deadline - started));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attempt", (long) attempt);
            row.putAll(correlation);
            ApiExecutor.HttpReply reply = null;
            ApiExecutor.CallFailure failure = null;
            try {
                reply = ApiExecutor.call(connection, method, uri, body, token, remaining);
            } catch (ApiExecutor.CallFailure failed) {
                failure = failed;
            }
            row.put("duration_millis", Duration.ofNanos(System.nanoTime() - started).toMillis());
            long wait;
            if (failure != null) {
                row.put("code", failure.code);
                failure.describe(row);
                attempts.add(row);
                if ("cancel".equals(failure.phase)) {
                    return new Outcome(null, failure, attempts, correlation, policy, null);
                }
                if (!(policy.notSent() && failure.notSent() && !"request".equals(failure.phase))) {
                    return new Outcome(null, failure, attempts, correlation, policy, null);
                }
                wait = policy.backoff().toMillis() * attempt;
            } else {
                row.put("delivery", "answered");
                row.put("http_status", (long) reply.status());
                Map<String, Object> receipt = new LinkedHashMap<>();
                ApiExecutor.recordReceipt(connection, ApiExecutor.object(ApiExecutor.redact(reply.body(), token)),
                        reply.headers(), receipt, token);
                for (String key : List.of("request_id", "generation_id", "tokens", "cost_usd", "cost_status")) {
                    if (receipt.containsKey(key)) row.put(key, receipt.get(key));
                }
                attempts.add(row);
                Long pause = resendPause(policy, reply, attempt);
                if (pause == null) return new Outcome(reply, null, attempts, correlation, policy, null);
                wait = pause;
                row.put("code", reply.status() == 429 ? "role_api_rate_limited" : "role_api_provider_error");
            }
            if (attempt > policy.maxRetries()) {
                return last(reply, failure, attempts, correlation, policy, "retries_exhausted");
            }
            if (System.nanoTime() + wait * 1_000_000L >= deadline) {
                return last(reply, failure, attempts, correlation, policy, "deadline");
            }
            row.put("resent_after_millis", wait);
            try {
                if (wait > 0) sleeper.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return last(reply, failure, attempts, correlation, policy, "cancelled");
            }
            if (Thread.currentThread().isInterrupted()) {
                return last(reply, failure, attempts, correlation, policy, "cancelled");
            }
        }
    }

    private static Outcome last(ApiExecutor.HttpReply reply, ApiExecutor.CallFailure failure,
                                List<Map<String, Object>> attempts, Map<String, Object> correlation,
                                Policy policy, String reason) {
        attempts.get(attempts.size() - 1).remove("resent_after_millis");
        if ("cancelled".equals(reason)) {
            // The last transport failure explains why we waited, not why the logical
            // call stopped. Keep that attempt, but never expose cancellation as a
            // transient failure that a role fallback could route around.
            String delivery = String.valueOf(attempts.get(attempts.size() - 1)
                    .getOrDefault("delivery", "unknown"));
            return new Outcome(null, new ApiExecutor.CallFailure("role_api_interrupted",
                    "cancel", delivery, "cancelled"), attempts, correlation, policy, reason);
        }
        return new Outcome(reply, failure, attempts, correlation, policy, reason);
    }

    /** Milliseconds to wait before resending this reply, or null when it must not be resent. */
    static Long resendPause(Policy policy, ApiExecutor.HttpReply reply, int attempt) {
        int status = reply.status();
        if (status == 429 && policy.rateLimited()) {
            Long seconds = retryAfterSeconds(reply.headers().firstValue("retry-after").orElse(null));
            if (seconds == null || seconds * 1000 > policy.maxRetryAfter().toMillis()) return null;
            return seconds * 1000;
        }
        if ((status == 502 || status == 503) && policy.gateway()) {
            return policy.backoff().toMillis() * attempt;
        }
        return null;
    }

    /** Whole seconds only. An HTTP date needs a trusted clock and is treated as "no wait named". */
    static Long retryAfterSeconds(String value) {
        if (value == null || !value.strip().matches("\\d{1,6}")) return null;
        return Long.parseLong(value.strip());
    }
}
