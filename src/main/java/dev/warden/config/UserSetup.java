package dev.warden.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates a starter `~/.warden` so the first `warden role --dry-run` explains itself.
 *
 * Only the Grok profile is marked verified, because only its flags were established by
 * running them. The other two carry the exact probe command instead of a `verified_on` date,
 * and the resolver refuses them until that date is filled in. Guessing a vendor's flags cost
 * three failed runs once already; the tool now makes that state visible rather than letting
 * an unproven profile fail in the middle of a loop.
 *
 * Nothing here is overwritten. An existing file is left exactly as it is.
 */
public final class UserSetup {

    public record Result(Path home, List<String> created, List<String> skipped) {}

    public Result run(Path home) throws IOException {
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Files.createDirectories(home.resolve("profiles"));
        Files.createDirectories(home.resolve("prompts"));
        Files.createDirectories(home.resolve("schemas"));

        write(home.resolve("policy.yaml"), POLICY, created, skipped, home);
        write(home.resolve("profiles/grok-review.yaml"), GROK_REVIEW, created, skipped, home);
        write(home.resolve("profiles/claude-review.yaml"), CLAUDE_REVIEW, created, skipped, home);
        write(home.resolve("profiles/codex-implement.yaml"), CODEX_IMPLEMENT, created, skipped, home);
        write(home.resolve("profiles/codex-visual-qa.yaml"), CODEX_VISUAL_QA, created, skipped, home);
        write(home.resolve("prompts/reviewer.md"), REVIEWER_PROMPT, created, skipped, home);
        write(home.resolve("prompts/implementer.md"), IMPLEMENTER_PROMPT, created, skipped, home);
        write(home.resolve("prompts/visual-qa.md"), VISUAL_QA_PROMPT, created, skipped, home);
        write(home.resolve("schemas/reviewer.json"), REVIEWER_SCHEMA, created, skipped, home);
        write(home.resolve("schemas/implementer.json"), IMPLEMENTER_SCHEMA, created, skipped, home);
        write(home.resolve("schemas/visual-qa.json"), VISUAL_QA_SCHEMA, created, skipped, home);
        return new Result(home, created, skipped);
    }

    private void write(Path target, String content, List<String> created, List<String> skipped, Path home)
            throws IOException {
        String label = home.relativize(target).toString().replace('\\', '/');
        if (Files.exists(target)) { skipped.add(label); return; }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        created.add(label);
    }

    private static final String POLICY = """
            version: 1

            # Which profiles may fill which role. This is the only file that names a vendor.
            roles:
              reviewer:
                profiles: [grok-review, claude-review]
                strategy: rotate
                # Hard constraint: a reviewer may not share a vendor with the implementer whose
                # work it reviews. A model reviewing its own output shares its own blind spots,
                # which is the only defensible reason to run more than one vendor at all.
                require_independent_vendor: true
              implementer:
                profiles: [codex-implement]
                strategy: first
                require_independent_vendor: false

              # Eyes on the result, not on the diff. Deliberately absent from this list until
              # you turn it on: the machine harness already screenshots the page and asserts
              # what is measurable, and this role costs a vendor call on top of that. Uncomment
              # it once `codex-visual-qa` is verified, and it will run after review for any task
              # whose contract sets `visual_qa.required: true`.
              #
              # visual_qa:
              #   profiles: [codex-visual-qa]
              #   strategy: first
              #   require_independent_vendor: false

            review:
              # A low-risk task still passes every machine gate; it just does not pay a reviewer.
              required_for_risk: [medium, high]
            """;

    private static final String GROK_REVIEW = """
            version: 1
            profile: grok-review
            role: reviewer
            vendor: grok
            # A label unless the args below pass it on. Warden makes the value available as
            # {{model}}; each vendor spells its own flag, so the profile writes the flag out.
            # When the vendor reports a different model, the run report says so.
            model: grok-4.6-build
            command: grok
            runner: direct
            read_only: true

            # Every flag below was established by running it, not by reading documentation.
            #   --json-schema        constrains the FIRST response, so an agentic review that
            #                        must read a diff never happens; the schema goes in the
            #                        prompt instead and warden validates the answer.
            #   --permission-mode    both `plan` and `dontAsk` ended the run at the first tool
            #                        call with stopReason=cancelled.
            #   --always-approve     the flag that actually auto-approves tool execution.
            #   --reasoning-effort   must be pinned here; unset it is inherited from
            #                        ~/.grok/config.toml, which makes cost depend on ambient state.
            args:
              - "--prompt-file"
              - "{{prompt_file}}"
              - "--output-format"
              - "json"
              - "--always-approve"
              - "--reasoning-effort"
              - "high"
              - "--max-turns"
              - "12"
              - "--disable-web-search"
              - "--verbatim"

            limits:
              wall_clock_minutes: 25

            prompt_template: prompts/reviewer.md
            json_schema: schemas/reviewer.json
            artifact:
              required_fields: [role, task_id, status, verdict, summary, findings]

            verification:
              verified_on: "2026-08-25"
              note: "Completed a full independent review end to end: 16 minutes, 17 turns, verdict fail with one confirmed P1, worktree untouched."
            """;

    private static final String CLAUDE_REVIEW = """
            version: 1
            profile: claude-review
            role: reviewer
            vendor: claude
            command: claude
            runner: direct
            read_only: true

            args:
              - "-p"
              - "{{prompt}}"
              - "--output-format"
              - "json"
              - "--max-turns"
              - "12"
              - "--allowedTools"
              - "Read,Grep,Glob"

            limits:
              wall_clock_minutes: 25

            prompt_template: prompts/reviewer.md
            json_schema: schemas/reviewer.json
            artifact:
              required_fields: [role, task_id, status, verdict, summary, findings]

            verification:
              # UNVERIFIED. The resolver refuses this profile until verified_on is filled in.
              # Run the probe, confirm the four points below, then set the date.
              probe: 'claude -p "Reply with exactly: ok" --output-format json --max-turns 1'
              what_to_check:
                - "exits 0 without opening an interactive session"
                - "stdout is a JSON envelope; note which key carries the answer"
                - "--allowedTools is accepted and the run still completes"
                - "whether a cost figure is reported, and under which key"
            """;

    private static final String CODEX_IMPLEMENT = """
            version: 1
            profile: codex-implement
            role: implementer
            vendor: codex
            command: codex
            runner: direct
            # This role writes to the workspace. The task must also grant
            # authority.workspace_write, or warden refuses the run.
            read_only: false

            # `exec -` reads the prompt from standard input. That is not a style choice: codex
            # is installed by npm as a .cmd shim, Windows runs a .cmd through cmd.exe, and
            # cmd.exe cuts a multi-line argument at its first newline and drops every argument
            # after it. An inline "{{prompt}}" reached codex as one line with --json stripped
            # off, and the run looked normal. Warden now refuses that combination outright.
            #
            # `--approve-for-me` is the non-interactive write flag. It cannot be combined with
            # `--sandbox` (Codex exits 2 saying so); it already routes approvals through the
            # workspace-write sandbox. Established by a 177ms refusal, then a 105s writing run.
            args: ["exec", "-", "--json", "--skip-git-repo-check", "--approve-for-me"]
            prompt_delivery: stdin

            limits:
              wall_clock_minutes: 40

            prompt_template: prompts/implementer.md
            json_schema: schemas/implementer.json
            artifact:
              required_fields: [role, task_id, status, summary, files_changed]

            # Established by running it against a spent subscription: exit 1, JSONL on stdout,
            # the refusal in a {"type":"error"} event. The default signatures already match it;
            # this is here as the place to add a wording Warden does not know yet.
            quota:
              signatures: []

            verification:
              # UNVERIFIED, and the riskiest profile to guess at: it runs with write access.
              # Probe it standalone before letting a loop drive it.
              probe: 'codex exec "Reply with exactly: ok" --json --skip-git-repo-check'
              what_to_check:
                - "exits 0 without prompting for approval"
                - "edits files without an extra approval flag, or find the non-interactive flag"
                - "which envelope key carries the final message"
                - "whether a turn limit and a cost figure are available"
            """;

    private static final String CODEX_VISUAL_QA = """
            version: 1
            profile: codex-visual-qa
            role: visual_qa
            vendor: codex
            command: codex
            runner: direct
            read_only: true

            # This is the only role that needs eyes, and `-i` is what gives it any. The
            # machine harness has already asserted what a machine can assert; this profile
            # exists for what it cannot — a control that is present, correct and unreadable,
            # a layout that collapses, text over text. Without `attachments.flag` the model
            # would receive filenames and review them as prose.
            args: ["exec", "-", "--json", "--skip-git-repo-check"]
            prompt_delivery: stdin
            attachments:
              flag: "-i"

            limits:
              wall_clock_minutes: 15

            prompt_template: prompts/visual-qa.md
            json_schema: schemas/visual-qa.json
            artifact:
              required_fields: [role, task_id, status, verdict, summary, findings]

            verification:
              # UNVERIFIED. Until verified_on is set the resolver refuses it, and the loop
              # falls back to the machine harness alone.
              probe: 'codex exec "Describe this image in one sentence." -i some-screenshot.png --json --skip-git-repo-check'
              what_to_check:
                - "the image is actually read: the answer describes THIS screenshot, not a generic one"
                - "-i accepts more than one file, repeated once per image"
                - "exits 0 and puts the final message in the JSONL event stream"
                - "whether a cost figure is reported, and under which key"
            """;

    private static final String VISUAL_QA_PROMPT = """
            # Visual QA — you are looking at screenshots, not at code

            Task `{{task_id}}` (run `{{run_id}}`) in project `{{project}}`.

            Goal
            : {{goal}}

            Scenarios the machine harness was asked to check
            {{visual_scenarios}}

            Screenshots attached to this message
            {{screenshots}}

            ## What has already been settled without you

            A headless browser loaded the page at each viewport, located the elements the
            scenarios name, and asserted their visibility, their reaction to a click and the
            absence of console errors. Those facts are in the report below. **Do not re-litigate
            them.** If the harness says a control is visible, it measured it.

            ```json
            {{context}}
            ```

            ## What only you can answer

            Look at the images. Report what a machine cannot measure:

            - text that is clipped, overlapping, or unreadable against its background;
            - a control that exists and is technically visible but is off-screen, behind
              something, or too small to hit;
            - a layout that has collapsed, overflowed, or lost its alignment at this viewport;
            - a state that is plainly wrong for the stated goal — an empty list where there
              should be content, a spinner that never resolved, a placeholder left in.

            You are not reviewing the code. You are answering one question: **does the result
            look right at these viewports.**

            ## What counts as a finding

            Every finding must name the screenshot it came from and describe what is visible in
            it. `expected` and `actual` must both be things a person can check by opening the
            same file.

            - `P1` — the goal is not achieved, or the result is unusable at this viewport.
            - `P2` — a real visual defect with bounded impact.  `P3` — cosmetic.
            - `confidence: confirmed` only for what you can see in an attached image. If you are
              reasoning about what probably happens, say `plausible` rather than inflating it.

            If an image did not reach you, say so in `summary` and return
            `status: "aborted"`. A verdict on images you could not see is worth less than an
            honest refusal, and Warden checks for exactly that.

            `verdict` is `fail` if there is at least one P1, else `pass`.

            ## Output contract

            Print one JSON object on stdout and nothing after it. No markdown fence.

            ```json
            {{schema_pretty}}
            ```
            """;

    private static final String VISUAL_QA_SCHEMA = """
            {
              "title": "Visual QA artifact",
              "type": "object",
              "required": ["role", "task_id", "status", "verdict", "summary", "findings"],
              "properties": {
                "role": { "const": "visual_qa" },
                "task_id": { "type": "string" },
                "run_id": { "type": "string" },
                "status": { "enum": ["completed", "aborted"] },
                "verdict": { "enum": ["pass", "fail"] },
                "summary": { "type": "string" },
                "images_seen": {
                  "type": "array",
                  "items": { "type": "string" }
                },
                "findings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["severity", "path", "message", "expected", "actual", "confidence"],
                    "properties": {
                      "severity": { "enum": ["P1", "P2", "P3"] },
                      "path": { "type": "string" },
                      "viewport": { "type": "string" },
                      "message": { "type": "string" },
                      "scenario": { "type": "string" },
                      "expected": { "type": "string" },
                      "actual": { "type": "string" },
                      "suggestion": { "type": "string" },
                      "confidence": { "enum": ["confirmed", "plausible"] }
                    }
                  }
                }
              }
            }
            """;

    private static final String REVIEWER_PROMPT = """
            # Reviewer — independent, read-only, machine-checkable

            Reviewing task `{{task_id}}` (run `{{run_id}}`) in project `{{project}}`, risk `{{risk}}`.

            Goal
            : {{goal}}

            Explicit non-goals
            {{non_goals}}

            Diff to review
            : everything changed between commit `{{diff_base_commit}}` and the current working
              tree, including untracked files. Use `git diff {{diff_base_commit}}` and
              `git ls-files --others --exclude-standard`.

            Declared scope
            : {{scope_paths}}

            Acceptance commands the machine gate runs
            {{acceptance_commands}}

            ## Hard constraints

            1. **You are read-only.** Do not create, modify or delete any file, and run no command
               that changes state. This is verified after you exit by comparing a content
               fingerprint of the worktree: if anything changed, your review is discarded whatever
               it says.
            2. **Emit your answer as JSON on stdout**, matching the schema below. Do not write it
               to a file.
            3. **Report, do not repair.**

            ## What counts as a finding

            A finding is a claim someone else can check. Every one must carry `expected`, `actual`
            and a `scenario` that makes the difference observable. If you cannot write those three,
            it is an opinion — verify it, or leave it out.

            - `P1` — the pipeline can produce a wrong or unsafe outcome: a gate that fails open, a
              check that can be bypassed, data loss, a documented path that does not work.
            - `P2` — real defect, bounded damage.  `P3` — cosmetic; keep these few.
            - `confidence: confirmed` only if you actually observed it. Otherwise say `plausible`
              rather than inflating it.

            `verdict` is `fail` if there is at least one P1, else `pass`.

            Judge whether each acceptance command covers what it appears to cover. A command that
            passes while testing nothing relevant is a P1 finding, not a passing check.

            ## Output contract

            Print one JSON object on stdout and nothing after it. No markdown fence.

            ```json
            {{schema_pretty}}
            ```

            ## Previous attempt

            {{context}}
            """;

    private static final String IMPLEMENTER_PROMPT = """
            # Implementer — do the work, inside the declared bounds

            Task `{{task_id}}`, run `{{run_id}}`, project `{{project}}`, risk `{{risk}}`.

            Goal
            : {{goal}}

            Explicit non-goals
            {{non_goals}}

            You may change files under these paths and nowhere else
            : {{scope_paths}}

            The work is done when all of these pass
            {{acceptance_commands}}

            Authority granted by the task
            : {{authority}}

            Diff base
            : `{{diff_base_commit}}`

            ## Hard constraints

            1. **Stay inside the declared scope.** A change outside it fails the run mechanically.
               If the task cannot be done within those paths, stop and say so — do not widen it.
            2. **Do not edit the task contract or the acceptance commands** to make checks pass.
               The contract is hashed before and after; changing it aborts the run. Fix the cause,
               never the check.
            3. **Run the acceptance commands yourself before you finish.** Every extra fix round
               costs real money and real wall-clock.
            4. Do not commit, push, merge or create branches. Landing is a human decision.

            ## Previous attempt

            {{context}}

            If the section above is empty this is the first attempt. Otherwise it holds the exact
            machine output or review findings that sent the work back. Address the specific
            difference it names; if you believe it is wrong, say so with evidence rather than
            leaving it unaddressed.

            ## Output contract

            Print one JSON object on stdout and nothing after it:

            ```json
            {{schema_pretty}}
            ```

            `status` is `completed` only if you ran the acceptance commands and they passed. If
            something blocked you, use `blocked` — an honest blocked answer is worth more than a
            green one that was never run.
            """;

    private static final String REVIEWER_SCHEMA = """
            {
              "title": "Reviewer artifact",
              "type": "object",
              "required": ["role", "task_id", "status", "verdict", "summary", "findings"],
              "properties": {
                "role": { "const": "reviewer" },
                "task_id": { "type": "string" },
                "run_id": { "type": "string" },
                "status": { "enum": ["completed", "aborted"] },
                "verdict": { "enum": ["pass", "fail"] },
                "summary": { "type": "string" },
                "findings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["severity", "path", "message", "expected", "actual", "confidence"],
                    "properties": {
                      "severity": { "enum": ["P1", "P2", "P3"] },
                      "path": { "type": "string" },
                      "line": { "type": "integer" },
                      "message": { "type": "string" },
                      "scenario": { "type": "string" },
                      "expected": { "type": "string" },
                      "actual": { "type": "string" },
                      "suggestion": { "type": "string" },
                      "confidence": { "enum": ["confirmed", "plausible"] }
                    }
                  }
                },
                "acceptance_review": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["command", "assessment", "verdict"],
                    "properties": {
                      "command": { "type": "string" },
                      "evidence": { "type": "string" },
                      "assessment": { "type": "string" },
                      "verdict": { "enum": ["pass", "fail", "inconclusive"] }
                    }
                  }
                }
              }
            }
            """;

    private static final String IMPLEMENTER_SCHEMA = """
            {
              "title": "Implementer artifact",
              "type": "object",
              "required": ["role", "task_id", "status", "summary", "files_changed"],
              "properties": {
                "role": { "const": "implementer" },
                "task_id": { "type": "string" },
                "run_id": { "type": "string" },
                "status": { "enum": ["completed", "blocked"] },
                "summary": { "type": "string" },
                "files_changed": { "type": "array", "items": { "type": "string" } },
                "commands_run": { "type": "array", "items": { "type": "string" } },
                "remaining_risks": { "type": "array", "items": { "type": "string" } }
              }
            }
            """;
}
