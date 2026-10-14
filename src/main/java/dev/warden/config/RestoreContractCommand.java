package dev.warden.config;

import dev.warden.git.GitRepository;
import dev.warden.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Restore the run's pinned bytes without removing any operator files. */
public final class RestoreContractCommand {
    public Map<String, Object> run(Path projectRoot, String runId) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("run_id", runId);
        try {
            if (runId == null || !runId.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,79}")) {
                throw new IOException("unsafe run id: " + runId);
            }
            Path root = projectRoot.toAbsolutePath().normalize();
            Path contract = root.resolve(WardenTree.EVIDENCE).resolve(runId).resolve("contract");
            Path manifest = contract.resolve("manifest.json");
            if (!Files.isDirectory(contract) || !Files.isRegularFile(manifest)) {
                throw new IOException("No pinned contract manifest for run " + runId + " at " + manifest);
            }
            Map<String, Object> pins = new TreeMap<>(Json.parseObject(Files.readString(manifest)));
            // Verify every recovery copy before changing the working tree.
            for (var pin : pins.entrySet()) {
                String file = pin.getKey();
                Path destination = root.resolve(file).normalize();
                if (!file.startsWith(".warden/") || file.contains("\\")
                        || !destination.startsWith(root.resolve(".warden"))
                        || destination.startsWith(root.resolve(WardenTree.EVIDENCE))
                        || !root.relativize(destination).toString().replace('\\', '/').equals(file)) {
                    throw new IOException("Unsafe pinned contract path: " + file);
                }
                Path copy = contract.resolve(file);
                if (!Files.isRegularFile(copy) || !GitRepository.contentSha256(copy).equals(pin.getValue())) {
                    throw new IOException("Pinned contract copy is missing or has a SHA-256 mismatch: " + file);
                }
            }
            List<String> added = new ArrayList<>();
            for (String file : WardenTree.snapshot(root).keySet()) {
                if (!pins.containsKey(file)) added.add(file);
            }
            List<String> restored = new ArrayList<>();
            for (var pin : pins.entrySet()) {
                Path destination = root.resolve(pin.getKey());
                Files.createDirectories(destination.getParent());
                Files.copy(contract.resolve(pin.getKey()), destination, StandardCopyOption.REPLACE_EXISTING);
                if (!GitRepository.contentSha256(destination).equals(pin.getValue())) {
                    throw new IOException("Restored contract SHA-256 mismatch: " + pin.getKey());
                }
                restored.add(pin.getKey());
            }
            report.put("ok", true);
            report.put("restored", restored);
            report.put("added_files", added);
            report.put("message", added.isEmpty() ? "Pinned contract restored and SHA-256 verified"
                    : "Pinned contract restored and SHA-256 verified; added files left untouched and need a manual decision");
        } catch (Exception failure) {
            report.put("code", "restore_contract_failed");
            report.put("message", String.valueOf(failure.getMessage()));
        }
        return report;
    }
}
