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
                new dev.warden.FindingsTest(),
                new dev.warden.SchemaTest(),
                new dev.warden.YamlTest(),
                new dev.warden.ConfigTest(),
                new dev.warden.RuntimeTest(),
                new dev.warden.ProfileVerifierTest(),
                new dev.warden.RoleResolverTest(),
                new dev.warden.QuotaSignalTest(),
                new dev.warden.RoleRunnerTest(),
                new dev.warden.WorkflowTest(),
                new dev.warden.TaskLoopTest(),
                new dev.warden.HeartbeatTest(),
                new dev.warden.LandMessageTest(),
                new dev.warden.DoCommandTest(),
                new dev.warden.PlannerTest(),
                new dev.warden.VisualQaTest(),
                new dev.warden.OrcaClientTest(),
                new dev.warden.LaunchSettingsTest(),
                new dev.warden.DashboardTest(),
                new dev.warden.OrcaSettlementTest(),
                new dev.warden.OrcaLifecycleTest(),
                new dev.warden.OrcaWorkspaceTest(),
                new dev.warden.OrcaRecoveryTest(),
                new dev.warden.OrcaDecisionGateTest(),
                new dev.warden.LocalHttpExecutorTest(),
                new dev.warden.LedgerTest(),
                new dev.warden.HomeCorpusTest(),
                new dev.warden.CorpusReaderTest(),
                new dev.warden.PilotConfigTest(),
                new dev.warden.ApprovalStoreTest(),
                new dev.warden.StatusCommandTest(),
                new dev.warden.InitializerTest()
        );

        Check check = new Check();
        for (String requested : args) {
            if (suites.stream().noneMatch(suite -> suite.name().equals(requested))) {
                throw new IllegalArgumentException("Unknown suite: " + requested);
            }
        }
        for (Suite suite : suites) {
            if (args.length > 0 && java.util.Arrays.stream(args).noneMatch(suite.name()::equals)) continue;
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
