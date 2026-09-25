package dev.warden.run;

import dev.warden.role.RoleRunner;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.util.List;
import java.util.Map;

/** A refunded planner start must remain free when preparation persists its spend. */
public final class PreparationAccountingTest implements Suite {
    @Override public String name() { return "preparation-accounting"; }

    @Override public void run(Check check) {
        var refunded = account(Map.of("vendor_attempts", List.of(Map.of("budget_charged", false))));
        check.eq("a planner fenced before its turn consumes no call", 0, refunded.runs());
        check.eq("nor an unpriced call", 0, refunded.unpriced());
        check.eq("nor dollars", 0.0, refunded.cost());

        var mixed = account(Map.of("budget_charged", false, "vendor_attempts", List.of(
                Map.of("cost_usd", 0.25), Map.of("code", "role_timeout"),
                Map.of("budget_charged", false))));
        check.eq("refunding the final attempt preserves earlier calls", 2, mixed.runs());
        check.eq("earlier reported cost survives", 0.25, mixed.cost());
        check.eq("an unknown earlier outcome stays unpriced", 1, mixed.unpriced());

        var single = account(Map.of("budget_charged", false));
        check.eq("a single-attempt report also preserves the refund", 0, single.runs());
        check.eq("and does not invent an unpriced call", 0, single.unpriced());
        var legacy = account(Map.of("code", "role_timeout"));
        check.eq("an older report without refund proof stays charged", 1, legacy.runs());
        check.eq("and conservatively unpriced", 1, legacy.unpriced());
    }

    private static Preparation.Spend account(Map<String, Object> details) {
        return Preparation.account(new RoleRunner.Outcome(false, "role_orca_start_failed", "planner",
                "orca-plan", "claude", Map.of(), null, details));
    }
}
