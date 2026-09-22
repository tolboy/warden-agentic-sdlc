package dev.warden.run;

import dev.warden.approval.ApprovalException;
import dev.warden.approval.HumanDecision;
import dev.warden.config.ConfigLoader;
import dev.warden.config.TaskSpec;
import dev.warden.json.Json;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** An exact acceptance-block edit. Suggestions are data until a human approves this digest. */
public final class ContractProposal {
    private ContractProposal() {}

    public static Map<String, Object> prepare(Path root, Map<String, Object> summary) {
        if (!(summary.get("next_step") instanceof Map<?, ?> step)
                || !"fix_contract".equals(step.get("kind"))
                || !(step.get("findings") instanceof List<?> findings)) return Map.of();
        List<?> commands = null;
        for (Object item : findings) {
            if (!(item instanceof Map<?, ?> finding) || !"contract_gap".equals(finding.get("category"))) continue;
            if (!(finding.get("proposed_acceptance") instanceof List<?> proposed)) continue;
            if (commands != null && !commands.equals(proposed)) return Map.of(); // conflicting proposals need an editor
            commands = proposed;
        }
        if (commands == null || commands.isEmpty() || commands.size() > 20
                || commands.stream().anyMatch(command -> !(command instanceof String text)
                || text.isBlank() || text.length() > 2000 || text.contains("\n") || text.contains("\r"))) return Map.of();
        try {
            var loaded = new ConfigLoader().load(root, String.valueOf(summary.get("task_id")));
            Path target = confined(root, loaded.taskFile());
            String before = Files.readString(target);
            String after = replaceAcceptance(before, commands);
            if (before.equals(after)) return Map.of();
            TaskSpec.parse(after, target.toString()).resolve(loaded.project(), target.toString());
            Map<String, Object> proposal = new LinkedHashMap<>();
            proposal.put("path", relativeTo(root, target));
            proposal.put("before_sha256", hash(before));
            proposal.put("after", after);
            proposal.put("commands", commands);
            proposal.put("sha256", digest(proposal));
            return proposal;
        } catch (Exception invalid) {
            return Map.of(); // retain the normal failure gate; never offer an unvalidated edit
        }
    }

    static String replaceAcceptance(String before, List<?> commands) {
        String newline = before.contains("\r\n") ? "\r\n" : "\n";
        List<String> kept = new ArrayList<>();
        boolean removing = false;
        for (String line : before.split("\\r?\\n", -1)) {
            if (line.startsWith("acceptance:")) { removing = true; continue; }
            if (removing && !line.isBlank() && !Character.isWhitespace(line.charAt(0))
                    && !line.startsWith("#")) removing = false;
            if (!removing) kept.add(line);
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).isBlank()) kept.remove(kept.size() - 1);
        kept.add("acceptance:");
        for (Object command : commands) kept.add("  - " + Json.write(command));
        return String.join(newline, kept) + newline;
    }

    public static String question(Map<String, Object> proposal) {
        return "Apply acceptance edit to " + proposal.get("path") + ": " + Json.write(proposal.get("commands"))
                + ". Other task fields and named checks stay unchanged. Start the next run; rerun reviews."
                + " Proposal SHA256 " + proposal.get("sha256");
    }

    public static void apply(Path root, HumanDecision decision) throws IOException {
        Map<String, Object> summary = Json.parseObject(Files.readString(root.resolve(decision.summaryPath())));
        if (!(summary.get("contract_proposal") instanceof Map<?, ?> raw))
            throw new ApprovalException("proposal_missing", "no concrete contract proposal was published");
        Map<String, Object> proposal = new LinkedHashMap<>();
        raw.forEach((key, value) -> proposal.put(String.valueOf(key), value));
        String digest = digest(proposal);
        if (!digest.equals(proposal.get("sha256")) || !decision.reason().endsWith("Proposal SHA256 " + digest))
            throw new ApprovalException("proposal_changed", "proposal differs from the approved question");
        Path target = confined(root, root.resolve(String.valueOf(proposal.get("path"))));
        String after = String.valueOf(proposal.get("after"));
        String current = Files.readString(target);
        if (current.equals(after)) return; // recover a crash between the atomic edit and decision write
        if (!hash(current).equals(proposal.get("before_sha256")))
            throw new ApprovalException("contract_changed", "contract changed after the proposal was shown");
        var loaded = new ConfigLoader().load(root, target.toString());
        TaskSpec.parse(after, target.toString()).resolve(loaded.project(), target.toString());
        Path temp = Files.createTempFile(target.getParent(), ".warden-proposal-", ".tmp");
        try {
            Files.writeString(temp, after);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    /**
     * The file must physically sit under this project's {@code .warden/tasks}.
     * Windows may spell the same directory as an 8.3 name, a junction, or a {@code \\?\}
     * prefix; {@code toRealPath()} of the file is compared with {@code toRealPath()} of
     * the tasks directory, never with the unresolved {@code Path} the caller held.
     */
    private static Path confined(Path root, Path target) throws IOException {
        Path realTasks = root.toAbsolutePath().normalize().toRealPath()
                .resolve(".warden").resolve("tasks").toRealPath();
        Path realFile = target.toAbsolutePath().normalize().toRealPath();
        if (!realFile.startsWith(realTasks) || !Files.isRegularFile(realFile)) {
            throw new ApprovalException("proposal_outside_tasks",
                    "proposal must target a real file under .warden/tasks");
        }
        return realFile;
    }

    private static String relativeTo(Path root, Path target) throws IOException {
        return root.toAbsolutePath().normalize().toRealPath()
                .relativize(target.toAbsolutePath().normalize().toRealPath())
                .toString().replace('\\', '/');
    }

    private static String digest(Map<String, Object> proposal) {
        return hash(Json.write(List.of(String.valueOf(proposal.get("path")),
                String.valueOf(proposal.get("before_sha256")), String.valueOf(proposal.get("after")),
                proposal.getOrDefault("commands", List.of()))));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
