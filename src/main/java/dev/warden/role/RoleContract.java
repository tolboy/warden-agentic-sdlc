package dev.warden.role;

import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.git.GitRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terms one stage was judged under, written down so a later run can prove they still hold.
 *
 * A verdict carried across runs rests on two claims. That the bytes it read have not moved is
 * answered by the source fingerprint and the `.warden` hashes. That the same judge, under the
 * same rules, would be asked again is answered by nothing at all — and that half was missing.
 *
 * Both hashes cover the *project's* `.warden` tree. Who may fill a role, whether that role
 * must differ from the implementer, which prompt it is given and which schema its answer must
 * satisfy all live in the operator's own `~/.warden`, which neither hash touches. So an
 * operator could swap the reviewer roster for a different vendor with different rules,
 * continue the run, and have the new reviewer's stage satisfied by the old reviewer's verdict.
 * The stage name matched, and a stage name is a label, not a contract. Reproduced on stubs:
 * `role_runs: 0`, `ready_for_human`, and the mandatory new reviewer never called.
 *
 * What is captured is deliberately the whole surface rather than a chosen subset. Model,
 * effort and `read_only` are in it because an Opus reviewer at max effort and the same profile
 * dropped to low are not the same reader, and a reviewer that quietly became writable is not a
 * reviewer. Being too eager to decline reuse costs a vendor call; being too willing to accept
 * it reports a review that never happened.
 */
public final class RoleContract {

    private RoleContract() {}

    /** The keys compared on resume, in the order a person would want to hear about them. */
    private static final List<String> COMPARED = List.of(
            "role", "roster", "strategy", "require_independent_vendor",
            "profile", "vendor", "model", "effort", "read_only",
            "prompt_template_sha256", "json_schema_sha256",
            // The stage's own routing. A stage name is stable across a workflow edit, so
            // `review` can keep its identity while changing what a finding from it does —
            // and a verdict reached under `on_findings: fix` is not evidence for a stage that
            // now stops on one.
            "on_fail", "on_findings", "recheck_after_fix", "fix_with");

    /** How a workflow stage routes, as the contract records it. */
    public static Map<String, Object> routingOf(dev.warden.config.Workflow.Stage stage) {
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("on_fail", stage.onFail());
        routing.put("on_findings", stage.onFindings());
        routing.put("recheck_after_fix", stage.recheckAfterFix());
        routing.put("fix_with", stage.fixWith());
        return routing;
    }

    /**
     * The contract this dispatch actually ran under.
     *
     * @param stage the workflow stage, recorded for a reader; not compared, because the stage
     *              identity is what selects which contract to compare in the first place
     */
    public static Map<String, Object> of(String stage, String role, Policy.RoleSpec spec,
                                         Profile profile, UserConfig user) {
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("stage", stage);
        contract.put("role", role);
        contract.put("roster", spec == null ? List.of() : List.copyOf(spec.profiles()));
        contract.put("strategy", spec == null ? null : spec.strategy());
        contract.put("require_independent_vendor",
                spec != null && spec.requireIndependentVendor());
        contract.put("profile", profile.name());
        contract.put("vendor", profile.vendor());
        contract.put("model", profile.model());
        contract.put("effort", profile.effort());
        contract.put("read_only", profile.readOnly());
        contract.put("prompt_template_sha256", digestOf(user, profile.promptTemplate()));
        contract.put("json_schema_sha256", digestOf(user, profile.jsonSchema()));
        return contract;
    }

    /**
     * How the terms have changed since {@code recorded} was written, or null when they have not.
     *
     * Conservative in every direction it can be. A recorded contract that is absent, a stage
     * the workflow no longer declares, a role no longer configured, a profile that no longer
     * exists, a profile that cannot fill the stage's current role, and a prompt template that
     * cannot be read are all reported as differences rather than waved through: the question
     * is whether the same judgement would be reached again, and none of those can answer it.
     *
     * The comparison is against the stage <em>as it stands now</em>, not against the role the
     * old row happened to name. A stage keeps its name across a workflow edit while its role
     * changes — `review` reassigned from `reviewer` to `architect`, say — and deriving the
     * "now" side from the old row's role compared the old reviewer against itself and found no
     * difference, letting the old reviewer's verdict stand in for the architect's. So the
     * caller passes the current {@link dev.warden.config.Workflow.Stage}, and its role is the
     * one the recorded contract must match.
     *
     * @param current the workflow stage this verdict would be reused for, as the workflow
     *                declares it now; null when the workflow no longer declares that stage
     */
    public static String differenceFrom(Object recorded, dev.warden.config.Workflow.Stage current,
                                        UserConfig user) {
        if (!(recorded instanceof Map<?, ?> was)) {
            // Every stage, not only the judging ones. Which model wrote the code under which
            // authority is as much a term of "this stage is done" as who read it, and a
            // summary written before contracts existed can demonstrate neither.
            return "that run recorded no role contract for it, so there is no way to show the "
                    + "same profile under the same rules would fill it again";
        }
        if (current == null) return "the workflow no longer declares that stage";
        String role = current.role();
        Policy.RoleSpec spec = user.policy() == null ? null : user.policy().roles().get(role);
        if (spec == null) return "role '" + role + "' is no longer configured";
        Object name = was.get("profile");
        Profile profile = user.profiles().get(String.valueOf(name));
        if (profile == null) {
            return "profile '" + name + "', which filled it, no longer exists";
        }
        // The stage's role may have changed under a stable name. The recorded profile fills
        // exactly one role, so a profile that cannot fill the current role is proof on its own
        // that the earlier verdict is a verdict about a different job.
        if (!role.equals(profile.role())) {
            return "the stage now runs role '" + role + "', which profile '" + name
                    + "' cannot fill (it is a '" + profile.role() + "' profile)";
        }
        // Build the "now" side from the current stage's role, spec and routing, so a moved
        // roster, independence rule or routing shows up as a difference against what ran.
        Map<String, Object> now = of(current.name(), role, spec, profile, user);
        now.putAll(routingOf(current));
        for (String key : COMPARED) {
            Object before = was.get(key);
            Object after = now.get(key);
            if (java.util.Objects.equals(normalise(before), normalise(after))) continue;
            return key + " changed from " + describe(before) + " to " + describe(after);
        }
        return null;
    }

    /**
     * JSON round-trips a list as a list and a boolean as a boolean, but a value that was
     * absent and a value that is null are the same thing here, and comparing them by identity
     * would report a difference nobody made.
     */
    private static Object normalise(Object value) {
        if (value instanceof List<?> items) return List.copyOf(items);
        return value;
    }

    private static String describe(Object value) {
        return value == null ? "none" : "'" + value + "'";
    }

    /** The bytes of a file the role's behaviour depends on, or null when there is no file. */
    private static String digestOf(UserConfig user, String relative) {
        if (relative == null) return null;
        try {
            Path file = user.resolve(relative);
            // Not an error here. A missing template fails the dispatch on its own, with a
            // better message than this class could give; on the resume side it shows up as a
            // difference, which is the conservative answer.
            return Files.isRegularFile(file) ? GitRepository.sha256(file) : "missing:" + relative;
        } catch (Exception unreadable) {
            return "unreadable:" + relative;
        }
    }
}
