package dev.warden.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chain of stages one task walks, declared in `~/.warden/policy.yaml`.
 *
 * Before this existed the chain was compiled into the loop, so "review twice", "look at the
 * page before asking a model to", or "skip review on a low-risk task" meant editing Java.
 * The order of stages, what each one runs, and when it runs at all now live beside the roles
 * and profiles they dispatch — one file answers who runs, in what order, and under which
 * condition.
 *
 * Two things are deliberately NOT configurable here.
 *
 * 1. Conditions are a closed set of named predicates evaluated in code, not an expression
 *    language. A workflow file decides which vendor gets write access to a repository; an
 *    evaluator would make that decision as strong as its parser, and an unknown predicate
 *    would silently be false — that is, silently permissive.
 * 2. What a failure is called. `reviewer_failed` and `gates_not_satisfied` are derived from
 *    the stage, so a renamed stage cannot rename the reason an operator greps for.
 */
public record Workflow(List<Stage> stages) {

    /** What a stage actually runs. */
    public enum Kind {
        /** Dispatch a vendor for one role. */
        ROLE("role"),
        /** The project's own acceptance commands plus blast-radius and contract checks. */
        MACHINE_GATES("machine_gates"),
        /** The browser harness: real viewports, real assertions, no vendor call. */
        VISUAL_HARNESS("visual_harness");

        private final String jsonValue;

        Kind(String jsonValue) { this.jsonValue = jsonValue; }

        public String jsonValue() { return jsonValue; }

        static Kind parse(String value) {
            for (Kind kind : values()) if (kind.jsonValue.equals(value)) return kind;
            return null;
        }

        static Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            for (Kind kind : values()) names.add(kind.jsonValue);
            return names;
        }
    }

    /**
     * Where a failing stage sends the run. `stop` ends the run at the human boundary; `fix`
     * hands the exact failure text back to the fix role, bounded by the task's
     * `max_fix_attempts`, and re-runs this stage plus every earlier stage that declared
     * `recheck_after_fix`.
     */
    public static final Set<String> RESPONSES = Set.of("stop", "fix");

    /**
     * The closed set of conditions. Each is a fact the controller already knows before the
     * stage runs — none of them consults a model, and none of them can be extended from a
     * config file without also being implemented here.
     */
    public static final Set<String> CONDITIONS = Set.of(
            "always",
            "review_required",
            "visual_qa_required",
            "risk_low",
            "risk_medium",
            "risk_high");

    private static final Set<String> STAGE_KEYS = Set.of(
            "stage", "run", "role", "when", "on_fail", "on_findings",
            "recheck_after_fix", "fix_with", "sees");
    private static final Set<String> TOP_LEVEL = Set.of("stages");

    /**
     * @param name             operator-chosen identity, unique in the workflow
     * @param kind             what this stage runs
     * @param role             the role a {@link Kind#ROLE} stage dispatches
     * @param when             every listed condition must hold, or the stage is skipped
     * @param onFail           `stop` or `fix` when the stage itself failed
     * @param onFindings       `stop` or `fix` when a role succeeded but reported P1 findings
     * @param recheckAfterFix  re-run this stage after any later stage's fix round
     * @param fixWith          the role handed the failure; `implementer` unless stated
     * @param sees             the visual-harness stage whose screenshots this role receives
     */
    public record Stage(String name, Kind kind, String role, List<String> when,
                        String onFail, String onFindings, boolean recheckAfterFix,
                        String fixWith, String sees) {

        public Stage {
            when = List.copyOf(when);
        }

        /** The label under which this stage appears in the run summary and the ledger. */
        public String label() {
            return switch (kind) {
                case ROLE -> role;
                case MACHINE_GATES -> "gates";
                case VISUAL_HARNESS -> "visual_qa";
            };
        }

        /**
         * The name of the fix-context file handed back to the implementer. Derived from what
         * the stage runs, not from its operator-chosen name: an operator renaming a stage
         * should not rename the evidence file a later run is compared against.
         */
        public String contextKind() {
            return switch (kind) {
                case MACHINE_GATES -> "gates";
                case VISUAL_HARNESS -> "visual";
                case ROLE -> switch (role) {
                    case "reviewer" -> "review";
                    case "visual_qa" -> "visual-review";
                    default -> role;
                };
            };
        }

        /** The stop reason when the stage itself failed, kept stable across renames. */
        public String failureReason() {
            return switch (kind) {
                case ROLE -> "visual_qa".equals(role) ? "visual_qa_role_failed" : role + "_failed";
                case MACHINE_GATES -> "gates_not_satisfied";
                // The harness reports its own code; `visual_qa_unavailable` and
                // `visual_qa_port_occupied` mean something different from a failing assertion.
                case VISUAL_HARNESS -> "visual_qa_failed";
            };
        }

        /** The stop reason when a role looked, objected, and still objects after the fixes. */
        public String findingsReason() {
            return "reviewer".equals(role) ? "blocking_findings_remain"
                    : "visual_qa".equals(role) ? "visual_findings_remain"
                    : role + "_findings_remain";
        }

        public Map<String, Object> toMap() {
            java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("stage", name);
            value.put("run", kind.jsonValue());
            if (role != null) value.put("role", role);
            value.put("when", when);
            value.put("on_fail", onFail);
            if (onFindings != null) value.put("on_findings", onFindings);
            value.put("recheck_after_fix", recheckAfterFix);
            value.put("fix_with", fixWith);
            if (sees != null) value.put("sees", sees);
            return value;
        }
    }

    /**
     * The chain Warden ran before it could be declared, kept as the default so an operator
     * who never writes a `workflow:` block gets exactly the documented loop.
     */
    public static Workflow builtIn() {
        return new Workflow(List.of(
                new Stage("implement", Kind.ROLE, "implementer", List.of(),
                        "stop", null, false, "implementer", null),
                new Stage("gates", Kind.MACHINE_GATES, null, List.of(),
                        "fix", null, true, "implementer", null),
                // recheck_after_fix, unlike the other role stages. A fix round for the
                // browser or the visual role edits code after the reviewer has passed, and
                // without this the candidate a human is asked to accept contains a diff no
                // independent vendor ever read. Measured: an implementer rewrote the app
                // shell in a browser fix round, and the review that had already passed was
                // reported as if it covered it.
                new Stage("review", Kind.ROLE, "reviewer", List.of("review_required"),
                        "stop", "fix", true, "implementer", null),
                new Stage("browser", Kind.VISUAL_HARNESS, null, List.of("visual_qa_required"),
                        "fix", null, true, "implementer", null),
                new Stage("look", Kind.ROLE, "visual_qa", List.of("visual_qa_required"),
                        "stop", "fix", false, "implementer", "browser")));
    }

    /** Parsed from the `workflow:` block of policy.yaml; errors accumulate on the collector. */
    static Workflow parse(Values root, String key) {
        Values workflow = root.optMap(key).rejectUnknownKeys(TOP_LEVEL);
        List<Values> declared = workflow.mapList("stages");
        if (declared.isEmpty()) {
            root.collector().add(key + ".stages must list at least one stage");
            return builtIn();
        }

        List<Stage> stages = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Values entry : declared) {
            entry.rejectUnknownKeys(STAGE_KEYS);
            String name = entry.requireString("stage");
            if (name == null) continue;
            if (!names.add(name)) {
                root.collector().add("workflow stage '" + name + "' is declared twice");
                continue;
            }
            String kindName = entry.requireEnum("run", Kind.names(), null);
            Kind kind = kindName == null ? null : Kind.parse(kindName);
            if (kind == null) {
                root.collector().add("workflow stage '" + name + "' must declare run: one of " + Kind.names());
                continue;
            }

            String role = entry.optString("role", null);
            if (kind == Kind.ROLE) {
                if (role == null) {
                    root.collector().add("workflow stage '" + name + "' runs a role and must name it");
                    continue;
                }
                if (!Profile.ROLES.contains(role)) {
                    root.collector().add("workflow stage '" + name + "' names unknown role '" + role
                            + "'; expected one of " + Profile.ROLES);
                    continue;
                }
            } else if (role != null) {
                root.collector().add("workflow stage '" + name + "' is not a role stage; remove role");
                continue;
            }

            List<String> when = entry.optStringList("when", List.of());
            List<String> conditions = new ArrayList<>();
            for (String condition : when) {
                if (!CONDITIONS.contains(condition)) {
                    root.collector().add("workflow stage '" + name + "' has unknown condition '"
                            + condition + "'; expected one of " + CONDITIONS);
                    continue;
                }
                if (!condition.equals("always")) conditions.add(condition);
            }

            String onFail = entry.requireEnum("on_fail", RESPONSES, "stop");
            String onFindings = entry.optString("on_findings", null);
            if (onFindings != null) {
                if (kind != Kind.ROLE) {
                    root.collector().add("workflow stage '" + name
                            + "' does not produce findings; remove on_findings");
                    onFindings = null;
                } else if (!RESPONSES.contains(onFindings)) {
                    root.collector().add("workflow stage '" + name + "'.on_findings must be one of "
                            + RESPONSES);
                    onFindings = "stop";
                }
            }
            boolean recheck = entry.optBool("recheck_after_fix", kind != Kind.ROLE);
            String fixWith = entry.optString("fix_with", "implementer");
            if (!Profile.ROLES.contains(fixWith)) {
                root.collector().add("workflow stage '" + name + "'.fix_with names unknown role '"
                        + fixWith + "'");
                fixWith = "implementer";
            }

            String sees = entry.optString("sees", null);
            if (sees != null && kind != Kind.ROLE) {
                root.collector().add("workflow stage '" + name + "' is not a role stage; remove sees");
                sees = null;
            }
            if (sees == null && kind == Kind.ROLE && "visual_qa".equals(role)) {
                sees = lastVisualHarness(stages);
                if (sees == null) {
                    root.collector().add("workflow stage '" + name + "' runs the visual_qa role but no "
                            + "visual_harness stage precedes it; a model cannot look at pixels nobody took");
                    continue;
                }
            }
            if (sees != null && !isEarlierVisualHarness(stages, sees)) {
                root.collector().add("workflow stage '" + name + "'.sees must name an earlier "
                        + "visual_harness stage, got '" + sees + "'");
                continue;
            }

            stages.add(new Stage(name, kind, role, conditions, onFail, onFindings, recheck, fixWith, sees));
        }
        return stages.isEmpty() ? builtIn() : new Workflow(stages);
    }

    private static String lastVisualHarness(List<Stage> earlier) {
        for (int index = earlier.size() - 1; index >= 0; index--) {
            if (earlier.get(index).kind() == Kind.VISUAL_HARNESS) return earlier.get(index).name();
        }
        return null;
    }

    private static boolean isEarlierVisualHarness(List<Stage> earlier, String name) {
        return earlier.stream()
                .anyMatch(stage -> stage.name().equals(name) && stage.kind() == Kind.VISUAL_HARNESS);
    }

    public Workflow {
        stages = List.copyOf(stages);
    }

    /**
     * A stage name is operator-chosen text; a directory name is not. Shared with the report
     * reader, which has to find the directory the loop wrote without re-deriving the rule.
     */
    public static String slug(String name) {
        String cleaned = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "stage" : cleaned;
    }

    /**
     * The stages that dispatch {@code role}, in declaration order.
     *
     * Two questions need this. Where a role's evidence is written: a role dispatched by more
     * than one stage cannot name its run directory after the role alone, or the later stage
     * overwrites the earlier one's prompt, raw output and artifact. And which profile a
     * {@code rotate} role gets: the position of the stage in this list is what makes the
     * pairing of two independent reviewers a property of the workflow rather than of how
     * many times anything happened to be dispatched before.
     */
    public List<Stage> stagesFor(String role) {
        return stages.stream()
                .filter(stage -> stage.kind() == Kind.ROLE && stage.role().equals(role))
                .toList();
    }

    /**
     * Where {@code stage} sits among the stages sharing its role, or -1 when it is the only
     * stage that dispatches that role.
     *
     * The -1 matters as much as the position. A role with one stage keeps rotating across
     * runs from persisted state, which is what `rotate` is for when two implementers should
     * alternate. A role with several stages must instead be spread over those stages, every
     * run, or the pairing an operator wrote down is at the mercy of how many dispatches
     * happened to precede it.
     */
    public int rotationPositionOf(Stage stage) {
        if (stage.kind() != Kind.ROLE) return -1;
        List<Stage> sharing = stagesFor(stage.role());
        if (sharing.size() < 2) return -1;
        for (int position = 0; position < sharing.size(); position++) {
            if (sharing.get(position).name().equals(stage.name())) return position;
        }
        return 0;
    }

    /** Every stage before {@code index} that asked to be re-run after a fix round. */
    public List<Stage> recheckBefore(int index) {
        List<Stage> earlier = new ArrayList<>();
        for (int position = 0; position < index && position < stages.size(); position++) {
            if (stages.get(position).recheckAfterFix()) earlier.add(stages.get(position));
        }
        return earlier;
    }

    public List<Map<String, Object>> toList() {
        return stages.stream().map(Stage::toMap).toList();
    }
}
