package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.json.Schema;
import dev.warden.process.ProcessRunner;
import dev.warden.role.PromptRenderer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a vendor CLI directly. The simplest adapter, and the one that has to be right first:
 * every other execution surface is a variation on it.
 *
 * Four guarantees hold regardless of which vendor is behind the command, because none of
 * them can be delegated to a vendor flag:
 *
 *  1. The exact prompt and the raw output are written to the run directory before anything
 *     is judged. Without them a malformed answer is undiagnosable after the fact.
 *  2. A read-only role is verified by comparing a CONTENT fingerprint of the worktree before
 *     and after. Comparing path names fails open the moment the tree is already dirty, which
 *     is the normal state after an implementer has run.
 *  3. Required artifact fields are checked before the artifact is accepted; an artifact that
 *     fails is not written at all.
 *  4. Arguments are passed as an argv array, never through a shell, so nothing inside a
 *     prompt can be interpreted as a command.
 *  5. A failure is classified before it is reported. An exhausted subscription and a broken
 *     flag both exit 1, and only one of them is worth retrying elsewhere — see
 *     {@link QuotaSignal}.
 */
public final class DirectCliExecutor implements RoleExecutor {

    /** Envelope keys vendors use for the model's answer, most specific first. */
    private static final List<String> ENVELOPE_KEYS = List.of(
            "structuredOutput", "structured_output", "result", "output", "response", "content", "data", "text");

    private final ProcessRunner processes;
    private final GitRepository git;

    public DirectCliExecutor(ProcessRunner processes, GitRepository git) {
        this.processes = processes;
        this.git = git;
    }

    @Override
    public Result execute(Request request) throws Exception {
        Profile profile = request.profile();
        Path runDirectory = request.runDirectory();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("role", request.role());
        evidence.put("profile", profile.name());
        evidence.put("vendor", profile.vendor());
        evidence.put("model", profile.model());
        evidence.put("effort_requested", profile.effort());
        evidence.put("effort_verification", "not_reported_by_provider");
        evidence.put("read_only", profile.readOnly());

        // The run controller pins this before the first agent starts. Re-resolving HEAD here
        // would let an agent hide committed changes by moving HEAD during the run.
        String mergeBase = request.diffBaseCommit() != null
                ? request.diffBaseCommit() : git.mergeBase(request.task().baseRef());
        String fingerprintBefore = profile.readOnly() ? git.fingerprint(mergeBase) : null;

        String schemaJson = "";
        if (request.schemaFile() != null && Files.isRegularFile(request.schemaFile())) {
            schemaJson = Json.write(Json.parse(Files.readString(request.schemaFile())));
        }
        Map<String, String> argumentValues = new LinkedHashMap<>();
        argumentValues.put("prompt_file", request.promptFile().toAbsolutePath().toString());
        argumentValues.put("prompt", Files.readString(request.promptFile()));
        argumentValues.put("schema_json", schemaJson);
        argumentValues.put("repo_root", request.projectRoot().toAbsolutePath().toString());
        argumentValues.put("run_id", request.runId());
        argumentValues.put("task_id", request.task().id());
        // Without this, `model:` was a label the ledger reported and the vendor never saw:
        // the run used whatever the CLI defaults to while the evidence named something else.
        // Each vendor spells the flag differently, so the profile still writes the flag out
        // (`args: ["--model", "{{model}}"]`); what is provided here is the value.
        argumentValues.put("model", profile.model() == null ? "" : profile.model());
        argumentValues.put("effort", profile.effort() == null ? "" : profile.effort());

        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(profile.command(), request.projectRoot()));
        for (String argument : profile.args()) {
            command.add(PromptRenderer.render(argument, argumentValues, "profile " + profile.name() + " args"));
        }
        // Files the role has to look at rather than read about. The flag is repeated per
        // file because that is how the vendors that accept images spell it; a profile with
        // no flag still gets the paths in its prompt and opens them with its own tools.
        List<String> attached = new ArrayList<>();
        if (profile.attachmentFlag() != null && request.attachments() != null) {
            for (Path attachment : request.attachments()) {
                if (!Files.isRegularFile(attachment)) continue;
                command.add(profile.attachmentFlag());
                command.add(attachment.toAbsolutePath().toString());
                attached.add(attachment.toAbsolutePath().toString());
            }
        }
        evidence.put("attachments", attached);
        evidence.put("attachments_offered", request.attachments() == null ? List.of()
                : request.attachments().stream().map(path -> path.toAbsolutePath().toString()).toList());
        if (profile.vision() != null) {
            evidence.put("vision_delivery", profile.vision().delivery());
            evidence.put("vision_verified", profile.hasVerifiedVision());
        }
        // The prompt can be enormous; record a readable command without inlining it.
        evidence.put("command", command.stream()
                .map(part -> part.equals(argumentValues.get("prompt")) ? "<prompt>" : part).toList());

        String stdinText = profile.promptDelivery().equals("stdin") ? argumentValues.get("prompt") : null;
        evidence.put("prompt_delivery", profile.promptDelivery());

        Map<String, Object> deliverable = deliverabilityCheck(command);
        evidence.put("argument_delivery_check", deliverable);
        if (Boolean.FALSE.equals(deliverable.get("deliverable"))) {
            evidence.put("failure", "role_prompt_undeliverable");
            return new Result(false, "role_prompt_undeliverable", Duration.ZERO, "", null, evidence);
        }

        ProcessRunner.Result process;
        try {
            process = processes.run(command, request.projectRoot(),
                    Duration.ofMinutes(profile.wallClockMinutes()), 4 * 1024 * 1024, stdinText);
        } catch (IOException cannotStart) {
            // A vendor that will not start is a configuration fault, not a vendor failure, and
            // deliberately does not fail over: routing around a broken profile would hide it.
            // It is still returned as a role outcome rather than thrown, so the run leaves a
            // report behind instead of a stack trace with nothing recorded.
            evidence.put("failure", "role_command_failed");
            evidence.put("exit_code", -1L);
            evidence.put("start_error", String.valueOf(cannotStart.getMessage()));
            return new Result(false, "role_command_failed", Duration.ZERO, "", null, evidence);
        }

        Path rawDirectory = runDirectory.resolve("raw");
        Files.createDirectories(rawDirectory);
        Path stdoutFile = rawDirectory.resolve(request.evidenceName() + ".stdout.txt");
        Files.writeString(stdoutFile, process.stdout(), StandardCharsets.UTF_8);
        Files.writeString(rawDirectory.resolve(request.evidenceName() + ".stderr.txt"),
                process.stderr(), StandardCharsets.UTF_8);
        evidence.put("raw_stdout", runDirectory.relativize(stdoutFile).toString().replace('\\', '/'));
        evidence.put("exit_code", (long) process.exitCode());
        evidence.put("timed_out", process.timedOut());
        evidence.put("duration_millis", process.durationMillis());

        Duration duration = Duration.ofMillis(process.durationMillis());

        // Telemetry is read before the verdict, not after it, and this order is the whole
        // point. A call that failed still spent money, and it reports what it spent in the
        // same envelope key it uses when it succeeds: a grok run killed by its own turn
        // ceiling printed `"total_cost_usd": 1.33` and was recorded as costing nothing, and
        // an Opus review that did the same hid $4.88. The budget ceiling then measures only
        // the calls that worked, which is the opposite of what a ceiling is for — the run
        // most worth costing is the one that failed. A planner that mutated the worktree is
        // the same: the protocol failure is a verdict, and the call still cost what it cost.
        Map<String, Object> envelope = Json.findLastObject(process.stdout());
        if (envelope != null) collectTelemetry(envelope, evidence);
        noteModelMismatch(profile, evidence);

        if (profile.readOnly()) {
            String fingerprintAfter = git.fingerprint(mergeBase);
            boolean unchanged = fingerprintBefore.equals(fingerprintAfter);
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("method", "worktree_content_fingerprint");
            check.put("covers", "tracked edits, deletes, renames and untracked file contents");
            check.put("does_not_cover", "paths ignored by .gitignore");
            check.put("matched", unchanged);
            evidence.put("read_only_check", check);
            if (!unchanged) {
                evidence.put("failure", "role_violated_read_only");
                return new Result(false, "role_violated_read_only", duration, process.stdout(), null, evidence);
            }
        }

        if (process.timedOut()) {
            evidence.put("failure", "role_timeout");
            return new Result(false, "role_timeout", duration, process.stdout(), null, evidence);
        }

        if (process.exitCode() != 0) {
            Result quota = quotaFailure(request, process, duration, evidence);
            if (quota != null) return quota;
            // A non-zero exit is how both measured vendors report a spent turn ceiling:
            // grok writes `Error: max turns reached` to stderr and exits 1, and claude -p
            // exits 1 with subtype `error_max_turns`. Until this check moved here it was
            // only reachable on the exit-0 path, so the code that exists to name the one
            // knob worth turning was never the code a real ceiling reached.
            Result ceiling = turnCeilingFailure(profile, process, duration, evidence);
            if (ceiling != null) return ceiling;
            evidence.put("failure", "role_command_failed");
            // Both streams: the Codex usage-limit message arrives on stdout while stderr
            // carries unrelated transport noise, so quoting stderr alone hid the cause.
            evidence.put("stderr_tail", tail(process.stderr(), 2000));
            evidence.put("stdout_tail", tail(process.stdout(), 2000));
            return new Result(false, "role_command_failed", duration, process.stdout(), null, evidence);
        }

        // Codex --json is JSONL: the last object is usually turn.completed (usage), while
        // the model's answer is an earlier item.completed / agent_message. Prefer that
        // message when it is itself JSON; otherwise unwrap the last envelope as before.
        Map<String, Object> fromJsonl = extractJsonlAgentMessage(process.stdout());
        Map<String, Object> artifact = fromJsonl != null ? unwrap(fromJsonl) : unwrap(envelope);
        if (artifact == null || isEventEnvelope(artifact)) {
            artifact = unwrap(envelope);
        }
        if (artifact == null || isEventEnvelope(artifact)) {
            Result quota = quotaFailure(request, process, duration, evidence);
            if (quota != null) return quota;
            // A vendor that ran out of turns did not fail at the work; it was interrupted
            // mid-thought by a ceiling the operator set. `role_command_failed` sends someone
            // to read a transcript looking for a defect that is not in it — the same reason
            // a spent subscription is classified apart from an ordinary failure.
            Result ceiling = turnCeilingFailure(profile, process, duration, evidence);
            if (ceiling != null) return ceiling;
            evidence.put("failure", "role_artifact_unparseable");
            evidence.put("stdout_tail", tail(process.stdout(), 2000));
            return new Result(false, "role_artifact_unparseable", duration, process.stdout(), null, evidence);
        }

        artifact.putIfAbsent("role", request.role());
        artifact.putIfAbsent("task_id", request.task().id());
        artifact.putIfAbsent("run_id", request.runId());

        List<String> missing = new ArrayList<>();
        for (String field : profile.requiredArtifactFields()) {
            if (!artifact.containsKey(field)) missing.add(field);
        }
        if (!missing.isEmpty()) {
            evidence.put("failure", "role_artifact_incomplete");
            evidence.put("missing_fields", missing);
            evidence.put("received_keys", new ArrayList<>(artifact.keySet()));
            return new Result(false, "role_artifact_incomplete", duration, process.stdout(), null, evidence);
        }

        List<String> schemaErrors = schemaErrors(profile, request, artifact);
        if (!schemaErrors.isEmpty()) {
            evidence.put("failure", "role_artifact_schema_violation");
            evidence.put("schema_errors", schemaErrors.stream().limit(40).toList());
            return new Result(false, "role_artifact_schema_violation", duration, process.stdout(), null, evidence);
        }

        String semanticFailure = semanticFailure(request.role(), artifact);
        if (semanticFailure != null) {
            evidence.put("failure", semanticFailure);
            evidence.put("artifact_status", artifact.get("status"));
            return new Result(false, semanticFailure, duration, process.stdout(), artifact, evidence);
        }

        evidence.put("verdict", artifact.get("verdict"));
        return new Result(true, "ok", duration, process.stdout(), artifact, evidence);
    }

    /**
     * Whether the vendor stopped because it hit its own turn ceiling.
     *
     * Matched on the vendor's words, like the quota signatures, and for the same reason: no
     * vendor exposes this as a distinct exit code, and inferring it from "exit 1 with no
     * artifact" would swallow real crashes too. Kept narrow on purpose — a phrase this
     * specific is not going to appear in an ordinary failure.
     */
    /**
     * The one failure whose fix is a number in a profile, reported as such.
     *
     * Called from both failure paths, because a turn ceiling is not one shape of transcript.
     * Grok exits 1 with `Error: max turns reached` on stderr; `claude -p` exits 1 with
     * `"subtype":"error_max_turns"` in its JSON; a vendor that exits 0 and simply stops
     * short leaves no artifact to parse. All three mean the same thing and take the same
     * one-line remedy, so all three must reach the same code.
     */
    private static Result turnCeilingFailure(Profile profile, ProcessRunner.Result process,
                                             Duration duration, Map<String, Object> evidence) {
        if (!turnsExhausted(process.stdout(), process.stderr())) return null;
        evidence.put("failure", "role_turns_exhausted");
        evidence.put("stderr_tail", tail(process.stderr(), 500));
        evidence.put("resolution", "the vendor stopped at its turn ceiling before "
                + "emitting an artifact. Raise --max-turns (or the vendor's equivalent) "
                + "on profile '" + profile.name() + "', or narrow the task: this diff "
                + "needed more steps to read than the profile allows.");
        return new Result(false, "role_turns_exhausted", duration, process.stdout(), null, evidence);
    }

    public static boolean turnsExhausted(String stdout, String stderr) {
        String haystack = ((stderr == null ? "" : stderr) + "\n"
                + (stdout == null ? "" : stdout.length() > 4000
                        ? stdout.substring(stdout.length() - 4000) : stdout))
                .toLowerCase();
        return haystack.contains("max turns reached")
                || haystack.contains("maximum number of turns")
                || haystack.contains("max_turns exceeded")
                // claude -p names it in the envelope rather than in prose.
                || haystack.contains("error_max_turns");
    }

    /** A syntactically valid artifact may still be an honest refusal, not a completed role. */
    public static String semanticFailure(String role, Map<String, Object> artifact) {
        Object status = artifact == null ? null : artifact.get("status");
        if (!(status instanceof String value)) return null;
        String normalized = value.toLowerCase();
        if (normalized.equals("blocked")) return "role_reported_blocked";
        if (normalized.equals("aborted") || normalized.equals("failed")
                || normalized.equals("cancelled") || normalized.equals("canceled")) {
            return "role_reported_" + normalized;
        }
        return null;
    }

    /** Conformance is ours, even when the vendor claimed to enforce the schema. */
    static List<String> schemaErrors(Profile profile, Request request, Map<String, Object> artifact)
            throws Exception {
        // The planner's draft is compiled into a contract. A profile may skip schema
        // enforcement for other roles; it may not skip it for the planner. Warden does not
        // trust a draft that fails the schema the prompt advertised.
        boolean mustCheck = profile.enforceSchema() || "planner".equals(request.role());
        if (!mustCheck || request.schemaFile() == null || !Files.isRegularFile(request.schemaFile())) {
            return List.of();
        }
        Object schema = Json.parse(Files.readString(request.schemaFile()));
        return Schema.validate(artifact, schema);
    }

    /**
     * Classifies a failed run as an exhausted subscription, or returns null to let the caller
     * report the generic failure. Only ever consulted for a run that already failed: a vendor
     * that produced a usable artifact is never reclassified on the strength of its prose.
     */
    private static Result quotaFailure(Request request, ProcessRunner.Result process,
                                       Duration duration, Map<String, Object> evidence) {
        QuotaSignal.Detection detection = QuotaSignal.detect(
                request.profile().quotaSignatures(), process.stdout(), process.stderr());
        if (!detection.matched()) return null;
        evidence.put("failure", "role_quota_exhausted");
        evidence.put("quota", detection.report());
        evidence.put("stderr_tail", tail(process.stderr(), 2000));
        evidence.put("stdout_tail", tail(process.stdout(), 2000));
        return new Result(false, "role_quota_exhausted", duration, process.stdout(), null, evidence);
    }

    /**
     * Refuses a command whose arguments cannot survive the channel they are about to go
     * through, rather than letting the vendor answer a corrupted question.
     *
     * Windows cannot start a `.cmd` or `.bat` directly; it runs them through cmd.exe, whose
     * command line is line-oriented. A multi-line argument is cut at its first newline and
     * every argument after it is dropped, with no error anywhere. Measured on this host:
     *
     * <pre>
     * argv given:     [exec, "# Reviewer line one\nsecond line\nthird line", --json]
     * argv received:  ARG1[exec]  ARG2[# Reviewer line one]  COUNT=2
     * </pre>
     *
     * The same argv reaches an `.exe` intact, which is why a vendor shipping a single binary
     * never showed the problem, and an npm-installed one silently reviewed a one-line prompt
     * with its output flags stripped off. Paying for that answer and then judging it is worse
     * than not running: use `prompt_delivery: stdin`, or a vendor flag that takes a file path.
     *
     * The second rule is not about shims at all. On Windows the JVM builds one command-line
     * string and wraps an argument in quotes when it contains a space — but it does not escape
     * a double quote already inside the value. The receiving process re-splits on whitespace
     * from there, so an argument carrying JSON arrives as a dozen arguments. Measured: a
     * visual-QA prompt with the harness report inlined reached `claude.exe` as
     * `error: unknown option '->'`, having been torn apart at the first quote in the report.
     * That is not a shim problem and no shim check would ever have seen it. The whitespace
     * half of the rule matters as much as the quote half: without it this check refuses
     * `-c model_reasoning_effort="high"`, an argument that has always worked.
     *
     * What this does not cover: cmd.exe also expands `%NAME%` inside an argument when that
     * variable exists in the environment. Warden does not scan for that, and the check says so
     * in every report rather than leaving the gap to be discovered.
     */
    public static Map<String, Object> deliverabilityCheck(List<String> command) {
        String executable = command.get(0).toLowerCase();
        boolean shim = executable.endsWith(".cmd") || executable.endsWith(".bat");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> undeliverable = new ArrayList<>();
        for (int index = 1; index < command.size(); index++) {
            String argument = command.get(index);
            if (shim && (argument.contains("\n") || argument.contains("\r"))) {
                undeliverable.add("argv[" + index + "] is multi-line ("
                        + argument.length() + " chars) and goes through cmd.exe");
            } else if (windows && argument.indexOf('"') >= 0 && hasWhitespace(argument)) {
                undeliverable.add("argv[" + index + "] contains both whitespace and a double "
                        + "quote (" + argument.length() + " chars)");
            }
        }
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("executable_is_batch_shim", shim);
        check.put("covers", windows
                ? "multi-line arguments through cmd.exe, and Windows arguments carrying both "
                  + "whitespace and a double quote, which the JVM quotes but does not escape"
                : "multi-line arguments passed through cmd.exe, which truncates them at the "
                  + "first newline and drops every argument after it");
        check.put("does_not_cover", "cmd.exe expansion of %NAME% inside an argument");
        check.put("deliverable", undeliverable.isEmpty());
        if (!undeliverable.isEmpty()) {
            check.put("undeliverable_arguments", undeliverable);
            check.put("resolution", "set prompt_delivery: stdin on this profile, or pass "
                    + "{{prompt_file}} to a vendor flag that reads the prompt from a path");
        }
        return check;
    }

    /**
     * Both conditions are needed, and the second one is why. The JVM wraps an argument in
     * quotes only when it contains whitespace; a quote inside a value it did not wrap is
     * passed through untouched. `-c model_reasoning_effort="high"` has been reaching Codex
     * intact for months for exactly that reason, and refusing it would be a false alarm on a
     * flag that works.
     */
    private static boolean hasWhitespace(String argument) {
        for (int index = 0; index < argument.length(); index++) {
            if (Character.isWhitespace(argument.charAt(index))) return true;
        }
        return false;
    }

    /** Extensions Windows will actually start as a process, most specific first. */
    private static final List<String> WINDOWS_EXECUTABLE_SUFFIXES =
            List.of(".exe", ".cmd", ".bat", ".com");

    /**
     * Java resolves a bare command against PATH but does not apply PATHEXT for `.cmd` on
     * Windows, so a working `grok` on the command line can still fail here. Falling back to
     * a shell would reintroduce quoting of prompts, so resolve explicitly instead.
     *
     * Taking the first line `where.exe` prints is not enough. An npm-installed CLI puts two
     * entries on PATH — an extensionless shell shim and the `.cmd` beside it — and the shim
     * comes first:
     *
     * <pre>
     * $ where.exe codex
     * C:\Users\...\npm\codex        &lt;- a POSIX shell script: CreateProcess error=193
     * C:\Users\...\npm\codex.cmd
     * </pre>
     *
     * Warden picked the shim and the role died before the vendor was ever reached, on the
     * first live run that used an npm-installed vendor. Grok had hidden the bug by shipping a
     * single `grok.exe`. So the choice is made by extension, not by the order `where.exe`
     * happens to print.
     */
    public static String resolveExecutable(String command, Path workingDirectory) {
        if (command.contains("/") || command.contains("\\")) return command;
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        try {
            ProcessRunner.Result lookup = new ProcessRunner()
                    .run(List.of("where.exe", command), workingDirectory, Duration.ofSeconds(10));
            if (lookup.ok()) {
                List<String> candidates = new ArrayList<>();
                for (String line : lookup.stdout().split("\\R")) {
                    if (!line.isBlank()) candidates.add(line.strip());
                }
                for (String suffix : WINDOWS_EXECUTABLE_SUFFIXES) {
                    for (String candidate : candidates) {
                        if (candidate.toLowerCase().endsWith(suffix)) return candidate;
                    }
                }
                if (!candidates.isEmpty()) return candidates.get(0);
            }
        } catch (IOException | InterruptedException ignored) {
            if (Thread.interrupted()) Thread.currentThread().interrupt();
        }
        return command;
    }

    /**
     * Codex {@code exec --json} writes one event per line. The last line is typically
     * {@code turn.completed} with usage; the model's answer, if it printed JSON, is in an
     * {@code item.completed} event of type {@code agent_message}. Recovered from a live
     * gpt-5.4-mini probe, not from documentation.
     */
    public static Map<String, Object> extractJsonlAgentMessage(String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        Map<String, Object> last = null;
        for (String line : stdout.split("\\R")) {
            String trimmed = line.strip();
            if (!trimmed.startsWith("{")) continue;
            Map<String, Object> event;
            try {
                event = Json.parseObject(trimmed);
            } catch (Json.JsonException ignored) {
                continue;
            }
            if (!"item.completed".equals(event.get("type"))) continue;
            Object item = event.get("item");
            if (!(item instanceof Map<?, ?> map)) continue;
            if (!"agent_message".equals(map.get("type"))) continue;
            Object text = map.get("text");
            if (!(text instanceof String body) || body.isBlank()) continue;
            Map<String, Object> parsed = Json.findLastObject(body);
            if (parsed != null) last = parsed;
        }
        return last;
    }

    /** JSONL lifecycle events must not be accepted as the role artifact. */
    static boolean isEventEnvelope(Map<String, Object> candidate) {
        if (candidate == null) return true;
        Object type = candidate.get("type");
        if (!(type instanceof String name)) return false;
        return name.startsWith("turn.") || name.startsWith("thread.") || name.startsWith("item.")
                || "error".equals(name);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> unwrap(Map<String, Object> envelope) {
        if (envelope == null) return null;
        for (String key : ENVELOPE_KEYS) {
            Object inner = envelope.get(key);
            if (inner instanceof Map<?, ?> map) return unwrap(new LinkedHashMap<>((Map<String, Object>) map));
            if (inner instanceof String text) {
                Map<String, Object> reparsed = Json.findLastObject(text);
                if (reparsed != null) return unwrap(reparsed);
            }
        }
        return envelope;
    }

    /**
     * Records the case where the profile names one model and the vendor reports another.
     *
     * Not a failure: a vendor may report an internal build name for the model it was asked
     * for. It is evidence, because an experiment that compares two models is worthless if the
     * ledger's `model` column is a wish rather than an observation.
     */
    public static void noteModelMismatch(Profile profile, Map<String, Object> evidence) {
        Object reported = evidence.get("model_reported");
        String declared = profile.model();
        if (declared == null || declared.isBlank() || !(reported instanceof String actual)) return;
        if (actual.equals(declared)) return;
        Map<String, Object> mismatch = new LinkedHashMap<>();
        mismatch.put("declared", declared);
        mismatch.put("reported", actual);
        mismatch.put("note", "the profile declares one model and the vendor reported another. If the "
                + "profile's args do not pass {{model}} to the vendor's own flag, the declared name "
                + "is a label and the run used the CLI's default.");
        evidence.put("model_mismatch", mismatch);
    }

    /** Cost and usage the vendor volunteered. No experiment is needed to learn what a run costs. */
    public static void collectTelemetry(Map<String, Object> envelope, Map<String, Object> evidence) {
        Object cost = firstPresent(envelope, "total_cost_usd", "totalCostUsd", "cost_usd");
        if (cost instanceof Number number) evidence.put("cost_usd", number.doubleValue());
        Object turns = firstPresent(envelope, "num_turns", "numTurns");
        if (turns instanceof Number number) evidence.put("num_turns", number.longValue());
        Object stop = firstPresent(envelope, "stopReason", "stop_reason");
        if (stop != null) evidence.put("stop_reason", stop);
        Object session = firstPresent(envelope, "sessionId", "session_id");
        if (session != null) evidence.put("session_id", session);
        if (envelope.get("usage") instanceof Map<?, ?> usage) {
            Map<String, Object> tokens = new LinkedHashMap<>();
            tokens.put("input", usage.get("input_tokens") != null ? usage.get("input_tokens") : usage.get("inputTokens"));
            tokens.put("output", usage.get("output_tokens") != null ? usage.get("output_tokens") : usage.get("outputTokens"));
            tokens.put("total", usage.get("total_tokens") != null ? usage.get("total_tokens") : usage.get("totalTokens"));
            evidence.put("tokens", tokens);
        }
        Object modelUsage = envelope.get("modelUsage");
        if (modelUsage instanceof Map<?, ?> map && !map.isEmpty()) {
            evidence.put("model_reported", map.keySet().iterator().next());
        }
    }

    private static Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) return value;
        }
        return null;
    }

    private static String tail(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(text.length() - limit);
    }
}
