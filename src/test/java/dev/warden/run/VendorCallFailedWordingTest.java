package dev.warden.run;

import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.Map;

/** A failed API call is not described as a CLI process that failed. */
public final class VendorCallFailedWordingTest implements Suite {
    @Override public String name() { return "vendor-call-failed-wording"; }

    @Override public void run(Check check) {
        String api = TaskLoop.vendorCallFailed(Map.of("infrastructure_failure",
                Map.of("profile", "gonkarouter-glm-review", "role_code", "role_api_provider_error")));
        check.that("an API stop names the API call", api.contains("'gonkarouter-glm-review' API call failed"));
        check.that("and its code", api.contains("role_api_provider_error"));
        check.that("and points at the attempts, not stderr", api.contains("api_attempts") && !api.contains("stderr"));

        String cli = TaskLoop.vendorCallFailed(Map.of("infrastructure_failure",
                Map.of("profile", "codex-review", "role_code", "role_command_failed")));
        check.that("a CLI stop keeps the process wording", cli.contains("process failed"));
        check.that("and still points at stderr", cli.contains("stderr_tail"));
        check.that("an unknown profile still reads", TaskLoop.vendorCallFailed(Map.of()).contains("vendor process failed"));
    }
}
