package dev.warden.ledger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Child-process helper for durability tests. Honours {@code warden.ledger.crash} and
 * {@code warden.ledger.trace} so a parent can halt this JVM at a protocol checkpoint.
 */
public final class CorpusCrashDriver {

    private CorpusCrashDriver() {}

    public static void main(String[] args) throws Exception {
        Path home = null;
        Path project = null;
        String runId = "crash";
        String type = "role_run";
        String eventId = null;
        String payload = "ok";
        int count = 1;
        boolean recoverOnly = false;
        for (int index = 0; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--home" -> home = Path.of(args[++index]);
                case "--project" -> project = Path.of(args[++index]);
                case "--run-id" -> runId = args[++index];
                case "--type" -> type = args[++index];
                case "--event-id" -> eventId = args[++index];
                case "--payload" -> payload = args[++index];
                case "--count" -> count = Integer.parseInt(args[++index]);
                case "--recover" -> recoverOnly = true;
                default -> throw new IllegalArgumentException("unknown argument: " + arg);
            }
        }
        if (home == null || project == null) {
            throw new IllegalArgumentException("--home and --project are required");
        }
        if (recoverOnly) {
            HomeCorpus.recoverProject(home, project);
            return;
        }
        EvidenceLedger ledger = new EvidenceLedger(project, runId, home);
        for (int n = 0; n < count; n++) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("ok", true);
            evidence.put("code", payload);
            evidence.put("role", "implementer");
            evidence.put("profile", "stub");
            evidence.put("vendor", "stubvendor");
            evidence.put("cost_usd", 0.1);
            if (eventId != null) evidence.put("event_id", eventId);
            ledger.append(type, evidence);
        }
    }
}
