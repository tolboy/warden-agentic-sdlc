package dev.warden.testing;

import java.util.List;

/**
 * Explicit suite registry rather than classpath scanning: a test that is not listed here is
 * a test that does not run, and that fact is visible in a diff.
 */
public final class TestMain {

    public static void main(String[] args) {
        List<Suite> suites = List.of(
                new dev.warden.JsonTest(),
                new dev.warden.SchemaTest(),
                new dev.warden.YamlTest(),
                new dev.warden.ConfigTest(),
                new dev.warden.RuntimeTest(),
                new dev.warden.RoleResolverTest(),
                new dev.warden.QuotaSignalTest(),
                new dev.warden.OrcaClientTest(),
                new dev.warden.OrcaSettlementTest(),
                new dev.warden.LedgerTest(),
                new dev.warden.InitializerTest()
        );

        Check check = new Check();
        for (Suite suite : suites) {
            check.beginSuite(suite.name());
            try {
                suite.run(check);
            } catch (Exception failure) {
                check.that(suite.name() + " threw " + failure, false);
            }
        }

        System.out.println();
        for (Check.Failure failure : check.failures()) {
            System.out.println("  FAIL  [" + failure.suite() + "] " + failure.description());
            System.out.println("        " + failure.detail());
        }
        System.out.printf("%nchecks: %d passed, %d failed%n", check.passed(), check.failures().size());
        if (!check.failures().isEmpty()) System.exit(1);
    }
}
