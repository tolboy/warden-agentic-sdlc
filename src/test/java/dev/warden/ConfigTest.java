package dev.warden;

import dev.warden.config.ConfigException;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.ProjectConfig;
import dev.warden.config.RepoPath;
import dev.warden.config.TaskSpec;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;

public final class ConfigTest implements Suite {

    @Override public String name() { return "config"; }

    private static final String PROJECT = """
            version: 1
            project: example-app
            base_ref: origin/main
            checks:
              fast: ["npm run check"]
              full: ["npm run check", "npm run build"]
            scopes:
              ui:      ["src/routes", "src/lib/components"]
              harness: ["scripts"]
            defaults:
              checks: full
              risk: medium
              max_fix_attempts: 2
              timeout_minutes: 30
            """;

    @Override public void run(Check check) {
        ProjectConfig project = ProjectConfig.parse(PROJECT, "project.yaml");
        check.eq("project name", "example-app", project.project());
        check.eq("named check set", List.of("npm run check", "npm run build"), project.checks().get("full"));
        check.eq("named scope", List.of("src/routes", "src/lib/components"), project.scopes().get("ui"));
        check.eq("default risk", "medium", project.defaultRisk());
        check.eq("a project that declares no setup asks for none", List.of(), project.setup());
        check.eq("an existing project opts into no pre-agent baseline by default",
                null, project.defaultBaselineChecks());

        ProjectConfig withBaseline = ProjectConfig.parse(PROJECT.replace(
                "  checks: full\n", "  checks: full\n  baseline_checks: fast\n"), "project.yaml");
        check.eq("the baseline names an explicit project check set", "fast",
                withBaseline.defaultBaselineChecks());
        TaskSpec.ResolvedTask baselineResolved = TaskSpec.parse("""
                version: 1
                id: baseline-order
                goal: preserve project health while adding a focused behaviour
                scope: ui
                checks: ["npm run focused"]
                acceptance: ["npm run check", "npm run acceptance"]
                """, "task.yaml").resolve(withBaseline, "task.yaml");
        check.eq("baseline commands are retained separately",
                List.of("npm run check"), baselineResolved.baselineCommands());
        check.eq("and form an ordered deduplicated floor under final acceptance",
                List.of("npm run check", "npm run focused", "npm run acceptance"),
                baselineResolved.acceptanceCommands());
        check.rejects("a baseline must name a declared check set",
                "defaults.baseline_checks names 'missing'",
                () -> ProjectConfig.parse(PROJECT.replace(
                        "  checks: full\n", "  checks: full\n  baseline_checks: missing\n"),
                        "project.yaml"));
        check.rejects("an empty check set cannot masquerade as a green baseline",
                "names an empty command set",
                () -> ProjectConfig.parse(PROJECT.replace(
                        "  checks: full\n", "  checks: full\n  baseline_checks: empty\n")
                        .replace("  full: [\"npm run check\", \"npm run build\"]",
                                "  full: [\"npm run check\", \"npm run build\"]\n  empty: []"),
                        "project.yaml"));

        // What has to happen in a checkout before any of `checks` can run. Most projects have
        // nothing to say here; a `git worktree` of an npm project has no node_modules, and
        // without this its gates fail for a reason no implementer put there.
        ProjectConfig staged = ProjectConfig.parse(PROJECT.replace("checks:\n",
                "setup:\n  - \"npm ci\"\nchecks:\n"), "project.yaml");
        check.eq("and one that does keeps the order it wrote", List.of("npm ci"), staged.setup());

        // A project with no executable definition of "done" cannot be gated at all.
        check.rejects("checks are mandatory", "at least one named command set",
                () -> ProjectConfig.parse("version: 1\nproject: p\n", "project.yaml"));
        check.rejects("unknown top-level key refused", "unsupported key 'chekcs'",
                () -> ProjectConfig.parse(PROJECT.replace("checks:", "chekcs:"), "project.yaml"));
        check.rejects("defaults.checks must name a real set", "which is not defined under checks",
                () -> ProjectConfig.parse(PROJECT.replace("checks: full", "checks: nonexistent"), "project.yaml"));
        check.rejects("unsafe scope path refused", "not a safe repository-relative path",
                () -> ProjectConfig.parse(PROJECT.replace("\"scripts\"", "\"../../etc\""), "project.yaml"));

        // "Everything" has exactly one spelling, and it is a token nobody types by accident.
        // `.` and `*` stay refused, so a blast radius cannot become unbounded through a typo.
        ProjectConfig whole = ProjectConfig.parse(
                PROJECT.replace("harness: [\"scripts\"]", "harness: [\"<repository>\"]"), "project.yaml");
        check.eq("the whole-repository token survives normalisation as itself",
                List.of("<repository>"), whole.scopes().get("harness"));
        check.rejects("and cannot be mixed with a path that would read as a narrowing",
                "already means every path",
                () -> ProjectConfig.parse(
                        PROJECT.replace("harness: [\"scripts\"]", "harness: [\"scripts\", \"<repository>\"]"),
                        "project.yaml"));
        check.rejects("a bare dot is still not a scope", "not a safe repository-relative path",
                () -> ProjectConfig.parse(PROJECT.replace("\"scripts\"", "\".\""), "project.yaml"));

        // An explicitly empty command set is a statement — "this project has no command yet".
        // A missing checks block is an omission, and stays refused.
        ProjectConfig noCommands = ProjectConfig.parse("""
                version: 1
                project: fresh
                checks:
                  fast: []
                scopes:
                  code: ["<repository>"]
                """, "project.yaml");
        check.eq("an explicitly empty check set is legal", List.of(), noCommands.checks().get("fast"));

        String freshTask = """
                version: 1
                id: first
                goal: Build the first page
                risk: medium
                scope: code
                checks: fast
                """;
        check.rejects("a task that defines done nowhere is still refused",
                "no executable definition of 'done'",
                () -> TaskSpec.parse(freshTask + """
                        visual_qa:
                          required: false
                          scenarios: []
                        """, "task.yaml").resolve(noCommands, "task.yaml"));
        TaskSpec.ResolvedTask byPixels = TaskSpec.parse(freshTask + """
                visual_qa:
                  required: true
                  scenarios: ["1280x720: no-console-errors"]
                """, "task.yaml").resolve(noCommands, "task.yaml");
        check.eq("but browser scenarios are an executable definition of done on their own",
                List.of("1280x720: no-console-errors"), byPixels.visualQa().scenarios());
        check.eq("and the task carries no command it would have had to invent",
                List.of(), byPixels.acceptanceCommands());
        // How a scenario names its control is the adapter's grammar and is checked at
        // preflight, not here: `warden land` re-reads the task file, so refusing at parse time
        // would strand a run a person had already accepted. See VisualQaTest.
        TaskSpec.ResolvedTask byCopy = TaskSpec.parse(freshTask + """
                visual_qa:
                  required: true
                  scenarios: ["1280x720: text=Save visible"]
                """, "task.yaml").resolve(noCommands, "task.yaml");
        check.eq("the parser carries a scenario it is not the judge of",
                List.of("1280x720: text=Save visible"), byCopy.visualQa().scenarios());
        check.rejects("unsafe base_ref refused", "not a safe git revision name",
                () -> ProjectConfig.parse(PROJECT.replace("origin/main", "origin/../main"), "project.yaml"));
        check.rejects("wrong version refused", "version must be 1",
                () -> ProjectConfig.parse(PROJECT.replace("version: 1", "version: 2"), "project.yaml"));

        // Tasks resolve names against the project; after resolution nothing is indirect.
        TaskSpec.ResolvedTask resolved = TaskSpec.parse("""
                version: 1
                id: settings-button
                goal: Add a Settings button
                risk: low
                scope: ui
                checks: fast
                acceptance: ["npm run test -- settings"]
                """, "task.yaml").resolve(project, "task.yaml");
        check.eq("resolved id", "settings-button", resolved.id());
        check.eq("named scope expanded", List.of("src/routes", "src/lib/components"), resolved.scopePaths());
        check.eq("checks plus acceptance",
                List.of("npm run check", "npm run test -- settings"), resolved.acceptanceCommands());
        check.eq("task risk overrides project default", "low", resolved.risk());
        check.eq("inherits fix budget", 2L, resolved.maxFixAttempts());

        TaskSpec.ResolvedTask minimal = TaskSpec.parse("""
                version: 1
                id: tiny
                goal: something small
                scope: harness
                """, "task.yaml").resolve(project, "task.yaml");
        check.eq("falls back to default checks",
                List.of("npm run check", "npm run build"), minimal.acceptanceCommands());
        check.eq("falls back to default risk", "medium", minimal.risk());

        TaskSpec.ResolvedTask explicit = TaskSpec.parse("""
                version: 1
                id: explicit
                goal: explicit paths and commands
                scope: ["src/lib/server", "docs/api"]
                checks: ["./gradlew test"]
                """, "task.yaml").resolve(project, "task.yaml");
        check.eq("explicit scope paths", List.of("src/lib/server", "docs/api"), explicit.scopePaths());
        check.eq("explicit command", List.of("./gradlew test"), explicit.acceptanceCommands());

        check.rejects("scope is mandatory", "scope is required",
                () -> TaskSpec.parse("version: 1\nid: x\ngoal: g\n", "task.yaml"));
        // `scope: name` is a NAME. A typo must not quietly become a path that matches nothing,
        // because a blast radius pointing at a non-existent directory never reports anything
        // out of scope — it fails open.
        check.rejects("typo in a bare scope name refused", "which is not defined under scopes",
                () -> TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: nope\n", "task.yaml")
                        .resolve(project, "task.yaml"));
        check.rejects("the error suggests the list form", "scope: [\"nope\"]",
                () -> TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: nope\n", "task.yaml")
                        .resolve(project, "task.yaml"));
        check.rejects("typo in a bare check name refused", "which is not defined under checks",
                () -> TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: ui\nchecks: nope\n", "task.yaml")
                        .resolve(project, "task.yaml"));
        check.eq("the list form still allows an explicit path", List.of("nope"),
                TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: [\"nope\"]\n", "task.yaml")
                        .resolve(project, "task.yaml").scopePaths());
        check.eq("the list form mixes names and paths", List.of("scripts", "src/extra"),
                TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: [harness, \"src/extra\"]\n", "task.yaml")
                        .resolve(project, "task.yaml").scopePaths());
        check.rejects("bad id refused", "lowercase slug",
                () -> TaskSpec.parse("version: 1\nid: Not A Slug\ngoal: g\nscope: ui\n", "task.yaml"));
        check.rejects("bad risk refused", "must be one of",
                () -> TaskSpec.parse("version: 1\nid: x\ngoal: g\nscope: ui\nrisk: urgent\n", "task.yaml"));

        // Profiles: read-only is the default so a typo cannot grant write access.
        Profile reviewer = Profile.parse("""
                version: 1
                profile: grok-review
                role: reviewer
                vendor: grok
                command: grok
                args: ["--prompt-file", "{{prompt_file}}", "--always-approve"]
                limits: { wall_clock_minutes: 20 }
                """, "grok-review.yaml");
        check.eq("profile role", "reviewer", reviewer.role());
        check.eq("profile vendor", "grok", reviewer.vendor());
        check.eq("args preserved", 3, reviewer.args().size());
        check.that("read_only defaults to true", reviewer.readOnly());
        check.eq("runner defaults to direct", "direct", reviewer.runner());
        check.eq("non-visual legacy profile needs no vision capability", null, reviewer.vision());
        check.that("unverified profile is flagged", !reviewer.verified());

        Profile implementer = Profile.parse("""
                version: 1
                profile: codex-implement
                role: implementer
                vendor: codex
                command: codex
                read_only: false
                verification:
                  verified_on: 2026-08-25
                """, "codex.yaml");
        check.that("write access must be explicit", !implementer.readOnly());
        check.that("verified profile is flagged", implementer.verified());

        Profile visual = Profile.parse("""
                version: 1
                profile: codex-eyes
                role: visual_qa
                vendor: codex
                command: codex
                runner: direct
                attachments: { flag: "-i" }
                capabilities:
                  vision:
                    delivery: cli_attachment
                    verification: required
                verification:
                  verified_on: 2026-08-27
                """, "codex-eyes.yaml");
        check.eq("direct vision delivery is explicit", "cli_attachment", visual.vision().delivery());
        check.that("verified visual probe makes vision eligible", visual.hasVerifiedVision());

        Profile legacyVisual = Profile.parse("""
                version: 1
                profile: legacy-eyes
                role: visual_qa
                vendor: codex
                command: codex
                attachments: { flag: "-i" }
                verification: { verified_on: 2026-08-27 }
                """, "legacy-eyes.yaml");
        check.eq("legacy direct attachment maps to capability", "cli_attachment",
                legacyVisual.vision().delivery());

        Profile orcaVisual = Profile.parse("""
                version: 1
                profile: orca-eyes
                role: visual_qa
                vendor: codex
                command: codex
                runner: orca
                capabilities:
                  vision: { delivery: workspace_file, verification: required }
                verification: { verified_on: 2026-08-27 }
                """, "orca-eyes.yaml");
        check.eq("orca vision uses workspace files", "workspace_file", orcaVisual.vision().delivery());

        Profile unverifiedVisual = Profile.parse("""
                version: 1
                profile: unverified-eyes
                role: visual_qa
                vendor: codex
                command: codex
                attachments: { flag: "-i" }
                capabilities:
                  vision: { delivery: cli_attachment, verification: required }
                verification:
                  probe: 'codex exec -i known.png "describe it"'
                """, "unverified-eyes.yaml");
        check.that("visual profile remains parseable for the verification workflow",
                !unverifiedVisual.hasVerifiedVision());

        check.rejects("visual role without pixel delivery is refused", "visual_qa requires capabilities.vision",
                () -> Profile.parse("version: 1\nprofile: eyes\nrole: visual_qa\nvendor: v\ncommand: c\n", "eyes.yaml"));
        check.rejects("cli attachment requires an actual flag", "requires attachments.flag",
                () -> Profile.parse("""
                        version: 1
                        profile: eyes
                        role: visual_qa
                        vendor: v
                        command: c
                        capabilities:
                          vision: { delivery: cli_attachment, verification: required }
                        """, "eyes.yaml"));
        check.rejects("orca cannot claim CLI attachment delivery", "runner: orca supports vision only",
                () -> Profile.parse("""
                        version: 1
                        profile: eyes
                        role: visual_qa
                        vendor: v
                        command: c
                        runner: orca
                        attachments: { flag: "-i" }
                        capabilities:
                          vision: { delivery: cli_attachment, verification: required }
                        """, "eyes.yaml"));
        check.rejects("local cannot claim vision; the HTTP adapter sends only text",
                "does not support capabilities.vision",
                () -> Profile.parse("""
                        version: 1
                        profile: eyes
                        role: visual_qa
                        vendor: ollama
                        command: ollama
                        runner: local
                        endpoint: http://127.0.0.1:11434/v1/chat/completions
                        capabilities:
                          vision: { delivery: workspace_file, verification: required }
                        """, "eyes.yaml"));
        check.rejects("vision verification requirement is mandatory", "capabilities.vision.verification",
                () -> Profile.parse("""
                        version: 1
                        profile: eyes
                        role: visual_qa
                        vendor: v
                        command: c
                        capabilities:
                          vision: { delivery: workspace_file }
                        """, "eyes.yaml"));

        check.rejects("typo in read_only is refused, not ignored", "unsupported key 'readonly'",
                () -> Profile.parse("version: 1\nprofile: p\nrole: reviewer\nvendor: v\ncommand: c\nreadonly: false\n", "p.yaml"));
        check.rejects("unknown role refused", "must be one of",
                () -> Profile.parse("version: 1\nprofile: p\nrole: janitor\nvendor: v\ncommand: c\n", "p.yaml"));
        check.rejects("unknown runner refused", "must be one of",
                () -> Profile.parse("version: 1\nprofile: p\nrole: reviewer\nvendor: v\ncommand: c\nrunner: kubernetes\n", "p.yaml"));

        Policy policy = Policy.parse("""
                version: 1
                roles:
                  implementer:
                    profiles: [codex-implement]
                    strategy: first
                  reviewer:
                    profiles: [grok-review, claude-review]
                    strategy: rotate
                    require_independent_vendor: true
                review:
                  required_for_risk: [medium, high]
                """, "policy.yaml");
        check.eq("two roles configured", 2, policy.roles().size());
        check.eq("rotation strategy", "rotate", policy.roles().get("reviewer").strategy());
        check.that("independence required", policy.roles().get("reviewer").requireIndependentVendor());
        check.that("review required for medium risk", policy.reviewRequired("medium"));
        check.that("review not required for low risk", !policy.reviewRequired("low"));

        check.rejects("unknown role in policy refused", "is not a known role",
                () -> Policy.parse("version: 1\nroles:\n  janitor:\n    profiles: [x]\n", "policy.yaml"));
        check.rejects("bad strategy refused", "must be one of",
                () -> Policy.parse("version: 1\nroles:\n  reviewer:\n    profiles: [x]\n    strategy: random\n", "policy.yaml"));

        // Path safety underpins every blast-radius decision.
        check.eq("normalises a clean path", "src/lib", RepoPath.normalize("./src/lib/"));
        check.eq("normalises backslashes", "src/lib", RepoPath.normalize("src\\lib"));
        check.eq("rejects parent escape", null, RepoPath.normalize("../etc"));
        check.eq("rejects embedded parent", null, RepoPath.normalize("src/../../etc"));
        check.eq("rejects absolute", null, RepoPath.normalize("/etc/passwd"));
        check.eq("rejects drive letter", null, RepoPath.normalize("C:/Windows"));
        check.eq("rejects glob", null, RepoPath.normalize("src/*"));
        check.that("prefix match does not leak across siblings",
                !RepoPath.isWithin("docs/agentic-extra/x", "docs/agentic"));
        check.that("prefix match accepts a real child", RepoPath.isWithin("docs/agentic/x", "docs/agentic"));
        check.that("exact match is within", RepoPath.isWithin("docs", "docs"));

        // Every problem in one pass, not just the first.
        try {
            ProjectConfig.parse("version: 9\nchecks: {}\n", "project.yaml");
            check.that("multi-issue report", false);
        } catch (ConfigException failure) {
            check.that("reports several issues at once", failure.issues().size() >= 3);
        }
    }
}
