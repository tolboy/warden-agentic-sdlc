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
                new dev.warden.config.ConfigFileStoreTest(),
                new dev.warden.RuntimeTest(),
                new dev.warden.WorktreeLeaseTest(),
                new dev.warden.DirectCaptureTest(),
                new dev.warden.ProfileVerifierTest(),
                new dev.warden.RoleResolverTest(),
                new dev.warden.QuotaSignalTest(),
                new dev.warden.VendorAvailabilityTest(),
                new dev.warden.RoleRunnerTest(),
                new dev.warden.WorkflowTest(),
                new dev.warden.TaskLoopTest(),
                new dev.warden.HeartbeatTest(),
                new dev.warden.LandMessageTest(),
                new dev.warden.LandCommandTest(),
                new dev.warden.DoCommandTest(),
                new dev.warden.PlannerTest(),
                new dev.warden.run.PreparationAccountingTest(),
                new dev.warden.VisualQaTest(),
                new dev.warden.OrcaClientTest(),
                new dev.warden.OrcaIsolationTest(),
                new dev.warden.LaunchSettingsTest(),
                new dev.warden.DashboardTest(),
                new dev.warden.ConfigViewTest(),
                new dev.warden.NextRunTest(),
                new dev.warden.SettingsEditorTest(),
                new dev.warden.RunAnalyticsTest(),
                new dev.warden.OrcaSettlementTest(),
                new dev.warden.OrcaLifecycleTest(),
                new dev.warden.OrcaWorkspaceTest(),
                new dev.warden.OrcaRunIdentityTest(),
                new dev.warden.OrcaRecoveryTest(),
                new dev.warden.OrcaStartTest(),
                new dev.warden.OrcaDecisionGateTest(),
                new dev.warden.NextStepTest(),
                new dev.warden.RestoreContractTest(),
                new dev.warden.LocalHttpExecutorTest(),
                new dev.warden.ConnectionTest(),
                new dev.warden.ApiExecutorTest(),
                new dev.warden.ApiConfigTest(),
                new dev.warden.ApiContextTest(),
                new dev.warden.ProcessEnvironmentTest(),
                new dev.warden.execution.ApiReceiptTest(),
                new dev.warden.LedgerTest(),
                new dev.warden.MeasurementAccountingTest(),
                new dev.warden.LedgerCompareTest(),
                new dev.warden.PairedEvaluationTest(),
                new dev.warden.HomeCorpusTest(),
                new dev.warden.CorpusReaderTest(),
                new dev.warden.PilotConfigTest(),
                new dev.warden.PilotPrepareTest(),
                new dev.warden.ApprovalStoreTest(),
                new dev.warden.StatusCommandTest(),
                new dev.warden.InitializerTest(),
                new dev.warden.RosterTest(),
                new dev.warden.RunOverrideTest(),
                new dev.warden.OperatorContinuationTest(),
                new dev.warden.DecisionPageTest(),
                new dev.warden.RunStartUnderOrcaTest()
        );

        Check check = new Check();
        for (String requested : args) {
            if (suites.stream().noneMatch(suite -> suite.name().equals(requested))) {
                throw new IllegalArgumentException("Unknown suite: " + requested);
            }
        }
        long registryStarted = System.nanoTime();
        for (Suite suite : suites) {
            if (args.length > 0 && java.util.Arrays.stream(args).noneMatch(suite.name()::equals)) continue;
            check.beginSuite(suite.name());
            int passedBefore = check.passed();
            int failedBefore = check.failures().size();
            long suiteStarted = System.nanoTime();
            System.out.println("[suite] " + suite.name() + " starting");
            System.out.flush();
            try {
                suite.run(check);
            } catch (Exception failure) {
                check.that(suite.name() + " threw " + failure, false);
            }
            System.out.printf(java.util.Locale.ROOT,
                    "[suite] %s: %d passed, %d failed; elapsed_ms=%.1f%n",
                    suite.name(), check.passed() - passedBefore,
                    check.failures().size() - failedBefore,
                    (System.nanoTime() - suiteStarted) / 1_000_000.0);
        }

        System.out.println();
        for (Check.Failure failure : check.failures()) {
            System.out.println("  FAIL  [" + failure.suite() + "] " + failure.description());
            System.out.println("        " + failure.detail());
        }
        System.out.printf(java.util.Locale.ROOT, "test_elapsed_ms=%.1f%n",
                (System.nanoTime() - registryStarted) / 1_000_000.0);
        System.out.printf("%nchecks: %d passed, %d failed%n", check.passed(), check.failures().size());
        if (!check.failures().isEmpty()) System.exit(1);
    }
}
