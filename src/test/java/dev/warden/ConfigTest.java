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
            project: living-horizon
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
        check.eq("project name", "living-horizon", project.project());
        check.eq("named check set", List.of("npm run check", "npm run build"), project.checks().get("full"));
        check.eq("named scope", List.of("src/routes", "src/lib/components"), project.scopes().get("ui"));
        check.eq("default risk", "medium", project.defaultRisk());

        // A project with no executable definition of "done" cannot be gated at all.
        check.rejects("checks are mandatory", "at least one named command set",
                () -> ProjectConfig.parse("version: 1\nproject: p\n", "project.yaml"));
        check.rejects("unknown top-level key refused", "unsupported key 'chekcs'",
                () -> ProjectConfig.parse(PROJECT.replace("checks:", "chekcs:"), "project.yaml"));
        check.rejects("defaults.checks must name a real set", "which is not defined under checks",
                () -> ProjectConfig.parse(PROJECT.replace("checks: full", "checks: nonexistent"), "project.yaml"));
        check.rejects("unsafe scope path refused", "not a safe repository-relative path",
                () -> ProjectConfig.parse(PROJECT.replace("\"scripts\"", "\"../../etc\""), "project.yaml"));
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
