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
        write(home.resolve("prompts/planner.md"), PLANNER_PROMPT, created, skipped, home);
        write(home.resolve("schemas/reviewer.json"), REVIEWER_SCHEMA, created, skipped, home);
        write(home.resolve("schemas/implementer.json"), IMPLEMENTER_SCHEMA, created, skipped, home);
        write(home.resolve("schemas/visual-qa.json"), VISUAL_QA_SCHEMA, created, skipped, home);
        write(home.resolve("schemas/planner.json"), PLANNER_SCHEMA, created, skipped, home);
        return new Result(home, created, skipped);
    }

    private void write(Path target, String content, List<String> created, List<String> skipped, Path home)
            throws IOException {
        String label = home.relativize(target).toString().replace('\\', '/');
        if (Files.exists(target)) { skipped.add(label); return; }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        created.add(label);
    }

    /** The shipped policy, exposed so the suite can prove Warden's own parser accepts it. */
    public static String policyTemplate() { return POLICY; }

    /**
     * The shipped planner schema. Preparation validates the draft against this even when a
     * profile set {@code enforce_schema: false}, because Warden compiles the draft and does
     * not trust it.
     */
    public static String plannerSchema() { return PLANNER_SCHEMA; }

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

            # What happens when the vendor filling a role reports a spent subscription and
            # another profile could take over.
            #
            #   confirm  stop, name the successor, and wait for a person. The default.
            #   auto     switch straight away; the swap is still written to the ledger as a
            #            role_failover event naming both vendors and who authorised it.
            #   stop     never switch, even when a candidate exists.
            #
            # `confirm` is the default because a failover changes who wrote the work, and on a
            # small roster it can cost the run its independent reviewer: two vendors minus one
            # spent subscription leaves one, and a model reviewing its own output is the thing
            # running two vendors was for. That is a judgement about the value of the result,
            # so it is the operator's.
            #
            # Record the choice with `warden approve <run-id> --decision switch`, then re-run
            # with `warden run <task> --run-id <new> --continue <run-id>`. The authorisation is
            # that recorded decision: it names one role and one profile, and nothing else.
            failover:
              on_quota_exhausted: confirm

            # How much of the remaining chain a repair round has to be affordable before it is
            # allowed to start.
            #
            #   full     (default) the repair, every judgement it invalidates, and every stage
            #            still owed. The run either finishes or does not begin the attempt.
            #   partial  the repair plus whatever will read its result. Allows paid progress
            #            this run cannot finish: a review that then passes is a verdict a
            #            continuation reuses for nothing.
            #
            # `full` is the default because `partial` is a real trade rather than a strictly
            # better one. In its favour, declining to spend does not save the calls — only
            # raising the ceiling unlocks the rest of the chain. Against it, a repair can
            # introduce a regression rather than remove one, and a person may decide not to
            # continue the task at all. Either way the arithmetic is in `budget_plan` before
            # the first vendor is dispatched, and `--dry-run` prints it.
            budget:
              repair_reserve: full

            # The order those roles run in, and what has to be true for each to run at all.
            # This block is optional; deleting it restores exactly the chain written below.
            # Reorder, drop or repeat stages here — nothing else has to change.
            #
            #   run:               role | machine_gates | visual_harness
            #   when:              every listed condition must hold, or the stage is skipped.
            #                      always · review_required · visual_qa_required ·
            #                      risk_low · risk_medium · risk_high
            #   on_fail:           stop | fix   (fix = hand the failure back, bounded by the
            #                      task's max_fix_attempts, then run this stage again)
            #   on_findings:       stop | fix   (a role that passed but filed P1 findings)
            #   recheck_after_fix: re-run this stage after any later stage's fix round, so a
            #                      fix cannot satisfy one check by breaking an earlier one
            #   sees:              the visual_harness stage whose screenshots a role receives
            #
            # A role stage is skipped when `roles:` above does not name that role, so the
            # visual_qa stage below costs nothing until you turn the role on.
            workflow:
              stages:
                - stage: implement
                  run: role
                  role: implementer
                  on_fail: stop

                - stage: gates
                  run: machine_gates
                  on_fail: fix
                  recheck_after_fix: true

                - stage: review
                  run: role
                  role: reviewer
                  when: [review_required]
                  on_fail: stop
                  on_findings: fix
                  # A fix round for the browser or the visual role edits code after the
                  # reviewer passed. Without this, the candidate a human accepts contains a
                  # diff no independent vendor ever read.
                  recheck_after_fix: true

                - stage: browser
                  run: visual_harness
                  when: [visual_qa_required]
                  on_fail: fix
                  recheck_after_fix: true

                - stage: look
                  run: role
                  role: visual_qa
                  when: [visual_qa_required]
                  sees: browser
                  on_fail: stop
                  on_findings: fix
            """;

    private static final String GROK_REVIEW = """
            version: 1
            profile: grok-review
            role: reviewer
            vendor: grok
            effort: high
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
              - "{{effort}}"
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

            # The prompt goes on stdin, not in argv. On Windows the JVM does not escape a
            # double quote inside an argument, and this prompt embeds the artifact schema:
            # a live run reached claude.exe as `error: unknown option` after being torn
            # apart at the first quote in the JSON.
            prompt_delivery: stdin
            args:
              - "-p"
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
              # The resolver refuses any profile without verified_on. Run
              #   warden profiles --verify claude-review
              # read the transcript against what_to_check, then add --confirm.
              probe: 'claude -p "Reply with exactly: ok" --output-format json --max-turns 1 --allowedTools Read'
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
              # The riskiest profile to guess at: it runs with write access. Probe it
              # standalone before letting a loop drive it:
              #   warden profiles --verify codex-implement
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
            capabilities:
              vision:
                delivery: cli_attachment
                # `warden profiles --verify` stamps the profile only after a human confirms
                # that the probe described the supplied pixels rather than merely their path.
                verification: required

            limits:
              wall_clock_minutes: 15

            prompt_template: prompts/visual-qa.md
            json_schema: schemas/visual-qa.json
            artifact:
              required_fields: [role, task_id, status, verdict, summary, findings]

            verification:
              # Until verified_on is set the resolver refuses it and the loop falls back to
              # the machine harness alone:
              #   warden profiles --verify codex-visual-qa
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

            How the images reach you
            : {{vision_note}}

            {{screenshots}}

            ## What has already been settled without you

            A headless browser loaded the page at each viewport, located the elements the
            scenarios name, and asserted their visibility, their reaction to a click and the
            absence of console errors. Those facts are in the report below. **Do not re-litigate
            them.** If the harness says a control is visible, it measured it.

            Each scenario's `a11y` list is ranked so the control it named is first, and each
            node carries a bounding box. Use those boxes to judge clipping and overlap; do
            not guess those from pixels when the snapshot has the numbers. A node with
            `ignored: true` is unreachable by assistive technology. A control whose `name`
            is empty has no accessible name — that is a finding.

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
            - two things drawn at sizes that cannot both be true: an object several times
              the height of the person beside it, an icon larger than the button holding it,
              a thumbnail that dwarfs its own caption. A machine can measure one element; only
              you are looking at two of them at once, and a picture is wrong long before any
              matcher notices.

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
              tree, including untracked files. These are the paths, already resolved:

            {{changed_files}}

            Open them with whatever file-reading tool you have. If — and only if — you are also
            allowed to run commands, `git diff {{diff_base_commit}}` and
            `git ls-files --others --exclude-standard` show the same set as a patch. Do not
            spend turns discovering which tools you have: a refused call costs a turn and tells
            you nothing about the work. Read the files above and review what is in them.

            Declared scope
            : {{scope_paths}}

            Acceptance commands the machine gate runs
            {{acceptance_commands}}

            Browser scenarios the harness asserts, after you and before a human
            {{visual_scenarios}}

            Those two lists are the whole of the machine acceptance, and the second one is not
            a plan — a headless browser runs it at the named viewports, clicks what it says to
            click, and fails the run when an assertion does not hold. So "no command verifies
            this change" is a finding only when **neither** list covers it. A live review once
            filed a P1 saying nothing asserted an attribute that the browser scenario on the
            next line asserted by name; the implementer was then sent to add a test for
            something already tested. Check both lists before writing that finding, and if the
            gap is real, say which list you would put the check in.

            ## Hard constraints

            1. **You are read-only.** Do not create, modify or delete any file, and run no command
               that changes state. This is verified after you exit by comparing a content
               fingerprint of the worktree: if anything changed, your review is discarded whatever
               it says. Your profile may also grant you no way to run commands at all — that is
               normal for this role and is not something to work around. Judge the acceptance
               commands by reading them and the code they cover, not by running them.
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

            Include `evidence_refs` (non-empty references to observed receipts, logs, or tests),
            `scenario`, `expected`, `actual`, and `scope_relation` (the acceptance it concerns).
            Use `supersedes` as an array of prior IDs when renaming or replacing a finding.
            Reopening a closed finding needs new evidence references. After any closure, every
            new P1 also needs evidence, even if it uses a new ID — including a finding closed
            by an earlier stage, and including a new ID whose only evidence_refs were already
            recorded. A reference already used for an earlier incarnation of a finding is not
            new merely because a later report replaced evidence_refs. Warden owns lifecycle
            status; omission closes a finding, while reporting it again reopens it subject to
            this check.

            Give every finding a `category`, because it decides who can act on it. Only the first
            is reliably the implementer's:

            - `product_defect` — wrong or unsafe behaviour in the work itself.
            - `contract_gap` — the task, its acceptance commands or its scenarios do not say what
              they need to say. An acceptance command too weak to catch half the goal is this, not
              a defect in the code that satisfied it.
            - `access_required` — you could not reach something you needed. Say what, and note
              that a failed network call is not by itself proof that access was the problem.
            - `investigation_evidence_gap` — you could not establish the fact you were asked about.
            - `tooling_failure`, `provider_unavailable`, `quota_exhausted` — the machinery, not
              the work.
            - `review_disagreement` — you believe an earlier finding was wrong. Say why, on the
              evidence.

            A finding an implementer cannot act on is not a reason to soften it. File it at the
            severity it deserves with the category that says whose it is. Only a product_defect
            P1 sends the work back to the implementer; a P1 in any other category stops the run
            for a person instead of buying another repair.

            If you are re-reading a candidate you have seen before, you will be told so and given
            back the ids you filed. Reuse an id for anything still open and give a new one only to
            something genuinely new, so a surviving objection can be told from a fresh one. Never
            lower a severity to let a run pass, and never drop a P1 you filed to let it pass
            either: a P1 that becomes a P2, or vanishes, on a candidate that did not change is
            refused, not believed.

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

            And when a headless browser can still assert every one of these. They are part of
            the definition of done, and for a project with no check command they are all of it
            — a matcher that finds nothing sends the work back to you as a fix round.
            {{visual_scenarios}}

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
                      "confidence": { "enum": ["confirmed", "plausible"] },
                      "category": {
                        "enum": ["product_defect", "investigation_evidence_gap", "access_required",
                                 "contract_gap", "tooling_failure", "quota_exhausted",
                                 "provider_unavailable", "review_disagreement"]
                      },
                      "id": { "type": "string" },
                      "evidence_refs": { "type": "array", "items": { "type": "string" } },
                      "supersedes": { "type": "array", "items": { "type": "string" } },
                      "scope_relation": { "type": "string" }
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

    private static final String PLANNER_PROMPT = """
            # Planner — read-only, evidence first, Warden validates what you draft

            Task `{{task_id}}`, run `{{run_id}}`, project `{{project}}`, risk `{{risk}}`.

            Operator goal (verbatim)
            : {{operator_goal}}

            Copy that wording into `operator_goal`. You may add a `goal_addition` or a
            `deliverable`. You may not replace the operator's sentence and you may not drop it.
            A draft whose `operator_goal` does not match what Warden handed you is refused.

            You are **not** the implementer. You do not edit the tree, you do not reach the
            network, and you do not run the work. You read the repository, then you draft a
            contract. Warden compiles that draft; Warden does not trust it.

            ## Hard constraints

            1. **You are read-only.** Do not create, modify or delete any file, and run no
               command that changes state. This is verified after you exit by comparing a
               content fingerprint of the worktree: if anything changed, your draft is
               discarded as a protocol failure, whatever it says.
            2. **Gather evidence before you draft.** Open the files that would be in scope,
               read the named checks and named scopes below, and only then fill the artifact.
               A contract invented from the goal sentence alone is how an unclear goal gets
               paid for twice.
            3. **Acceptance names a check that already exists.** Every `acceptance` entry
               must set `check` to one of the names under `checks:` in `.warden/project.yaml`,
               listed below. Do not invent a shell command. A string such as `npm test` is
               refused and is never promoted to a trusted acceptance command.
            4. **Scope is a name that already exists.** `scope` must be one of the named
               scopes below. An invented name is refused.
            5. **Do not ask for authority the invocation did not grant.** `required_access`
               may request less than the invocation authorised and may not request more.
               Asking for `land`, or for `network` that was not granted, is refused rather
               than granted. The invocation's grants are in the context below.
            6. **Ask for a visual contract when a person will look at the result.** Set
               `visual_qa.required` to true and give `visual_qa.scenarios` the harness already
               understands (`testid=`, `text=`, `css=`, `no-console-errors`). Do not invent a
               data-testid. If you omit `visual_qa`, Warden applies the same rule a
               `--prepare off` draft uses: a UI goal on a project that serves a preview gets
               the honest default scenarios, so preparation is not weaker than the
               deterministic drafter.

            Named checks in this project
            {{named_checks}}

            Named scopes in this project
            {{named_scopes}}

            Authority of this call (you): you have none
            : {{authority}}

            {{context}}

            ## What to put in the draft

            - `task_kind` — the kind of work, not a slogan.
            - `deliverable` — what "done" looks like, in one or two sentences.
            - `non_goals` — what this run will not do.
            - `subtasks` — a bounded list with `id`, `goal` and `depends_on`. Warden records
              them and does not execute them. Do not invent a scheduler.
            - `target` — `local` or `live`.
            - `risk` — `low`, `medium` or `high`.
            - `estimate` — why this is that size, not a promise.
            - `stop_conditions` — when a later role should stop and ask a person.

            ## Output contract

            Print one JSON object on stdout and nothing after it. No markdown fence.

            ```json
            {{schema_pretty}}
            ```
            """;

    private static final String PLANNER_SCHEMA = """
            {
              "title": "Planner artifact",
              "type": "object",
              "required": ["role", "task_id", "status", "operator_goal", "task_kind",
                           "deliverable", "non_goals", "subtasks", "acceptance", "target",
                           "required_access", "risk", "estimate", "stop_conditions", "scope"],
              "properties": {
                "role": { "const": "planner" },
                "task_id": { "type": "string" },
                "run_id": { "type": "string" },
                "status": { "enum": ["completed", "blocked"] },
                "operator_goal": { "type": "string" },
                "goal_addition": { "type": "string" },
                "task_kind": {
                  "enum": ["feature", "fix", "investigation", "chore", "docs", "unknown"]
                },
                "deliverable": { "type": "string" },
                "non_goals": {
                  "type": "array",
                  "items": { "type": "string" }
                },
                "subtasks": {
                  "type": "array",
                  "maxItems": 8,
                  "items": {
                    "type": "object",
                    "required": ["id", "goal"],
                    "properties": {
                      "id": { "type": "string" },
                      "goal": { "type": "string" },
                      "depends_on": {
                        "type": "array",
                        "items": { "type": "string" }
                      }
                    }
                  }
                },
                "acceptance": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["check"],
                    "properties": {
                      "check": { "type": "string" },
                      "covers": { "type": "string" }
                    }
                  }
                },
                "target": { "enum": ["local", "live"] },
                "visual_qa": {
                  "type": "object",
                  "properties": {
                    "required": { "type": "boolean" },
                    "scenarios": {
                      "type": "array",
                      "items": { "type": "string" }
                    }
                  }
                },
                "required_access": {
                  "type": "object",
                  "required": ["workspace_write", "network", "land"],
                  "properties": {
                    "workspace_write": { "type": "boolean" },
                    "network": { "type": "boolean" },
                    "land": { "type": "boolean" }
                  }
                },
                "risk": { "enum": ["low", "medium", "high"] },
                "scope": { "type": "string" },
                "estimate": {
                  "type": "object",
                  "required": ["rationale"],
                  "properties": {
                    "role_runs": { "type": "integer", "minimum": 1 },
                    "cost_usd": { "type": "number", "minimum": 0 },
                    "rationale": { "type": "string" }
                  }
                },
                "stop_conditions": {
                  "type": "array",
                  "items": { "type": "string" }
                },
                "summary": { "type": "string" }
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
