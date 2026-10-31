package dev.warden;

import dev.warden.run.TaskLoop;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;

/** Infrastructure stops must not become code-repair requests after an API writer changed files. */
public final class HarnessFailureClassificationTest implements Suite {
    @Override
    public String name() {
        return "harness-failure-classification";
    }

    @Override
    public void run(Check check) {
        for (String code :
                List.of(
                        "harness_coding_unproven",
                        "role_harness_identity_unproven",
                        "role_harness_config_unproven",
                        "role_harness_version_unproven",
                        "role_harness_profile_changed",
                        "role_harness_contract_missing",
                        "role_harness_process_failed",
                        "role_harness_credential_reflected")) {
            check.that(
                    code + " requires an operator rather than a candidate repair",
                    TaskLoop.roleFailureRequiresOperator(code));
        }
        check.that(
                "ordinary model artifact errors retain the existing repair policy",
                !TaskLoop.roleFailureRequiresOperator("role_artifact_schema_violation"));
        check.that(
                "ordinary timeout retains the existing policy",
                !TaskLoop.roleFailureRequiresOperator("role_timeout"));
        check.that(
                "a missing reason is not an infrastructure stop",
                !TaskLoop.roleFailureRequiresOperator(null));
    }
}
