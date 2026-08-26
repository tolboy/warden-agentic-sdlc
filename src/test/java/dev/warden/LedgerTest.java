package dev.warden;

import dev.warden.ledger.EvidenceLedger;
import dev.warden.ledger.LedgerReader;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class LedgerTest implements Suite {
    @Override public String name() { return "ledger"; }

    @Override public void run(Check check) throws Exception {
        Path root = Files.createTempDirectory("warden-ledger-");
        try {
            new EvidenceLedger(root, "pass").append("machine_gate", Map.of("ok", true, "code", "passed"));
            new EvidenceLedger(root, "fail").append("machine_gate", Map.of("ok", false, "code", "failed"));
            Map<String, Object> summary = new LedgerReader().summarize(root);
            check.eq("two runs counted", 2L, summary.get("run_count"));
            check.eq("pass counted", 1L, summary.get("passed"));
            check.eq("failure counted", 1L, summary.get("failed"));
            check.that("future comparison dimensions declared",
                    String.valueOf(summary.get("future_dimensions")).contains("vendor"));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
