package dev.warden.execution;

import dev.warden.config.DecisionProfile;
import dev.warden.config.Profile;
import dev.warden.config.ServiceBinding;
import dev.warden.config.UserConfig;
import dev.warden.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * One derivation of "did this connection and model answer" for every settings surface.
 *
 * Source: the per-profile receipts in {@code checks/states.json}, kept only when their
 * configuration still matches the profile (a changed file, model, effort or connection makes an
 * old answer irrelevant). Service cards, model rows and the API-key table all read this, so a card
 * cannot say "not checked" while its models answered, and one failed model does not paint a
 * whole connection red.
 */
public final class CheckEvidence {
    private CheckEvidence() {}

    /** Current receipts by profile or classifier name. Unreadable history is treated as absent. */
    public static Map<String, Map<String, Object>> current(Path home, UserConfig user) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        Path states = home.resolve("checks/states.json");
        Map<String, Object> raw;
        try {
            if (!Files.isRegularFile(states) || Files.size(states) > 1024 * 1024) return result;
            raw = Json.parseObject(Files.readString(states));
        } catch (Exception unreadable) { return result; }
        Map<String, DecisionProfile> classifiers;
        try { classifiers = DecisionProfile.load(home); } catch (Exception invalid) { classifiers = Map.of(); }
        for (var entry : raw.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> value)) continue;
            String expected = null;
            try {
                Profile profile = user.profiles().get(entry.getKey());
                if (profile != null) expected = ModelCheck.configuration(profile, user);
                else if (classifiers.get(entry.getKey()) instanceof DecisionProfile classifier
                        && user.connections().get(classifier.connection()) != null)
                    expected = classifier.fingerprint(user.connections().get(classifier.connection()));
            } catch (Exception unavailable) { expected = null; }
            if (expected == null || !expected.equals(value.get("configuration"))) continue;
            @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) value;
            result.put(entry.getKey(), row);
        }
        return result;
    }

    /**
     * Card status for a set of profiles: {@code answered} when every checked model answered,
     * {@code partial} when some answered and some failed, {@code failed}, or {@code unchecked}.
     * Per model and account the newest current receipt wins.
     */
    public static Map<String, Object> status(List<Profile> profiles, List<DecisionProfile> classifiers,
                                             Map<String, Map<String, Object>> evidence) {
        Map<String, Map<String, Object>> latest = new TreeMap<>();
        for (Profile profile : profiles) {
            // An Orca transport failure is a launch-channel fact, not the service's; roles show it.
            if ("orca".equals(profile.runner())) continue;
            var binding = ServiceBinding.of(profile);
            consider(latest, key(binding.account(), profile.model()), profile.model(), binding.account(), evidence.get(profile.name()));
        }
        for (DecisionProfile classifier : classifiers)
            consider(latest, key(classifier.connection(), classifier.model()), classifier.model(), classifier.connection(), evidence.get(classifier.name()));
        var answered = new ArrayList<Map<String, Object>>();
        var failed = new ArrayList<Map<String, Object>>();
        String checkedAt = null;
        for (var row : latest.values()) {
            (Boolean.TRUE.equals(row.get("ok")) ? answered : failed).add(row);
            String at = (String) row.get("checked_at");
            if (at != null && (checkedAt == null || at.compareTo(checkedAt) > 0)) checkedAt = at;
        }
        String state = latest.isEmpty() ? "unchecked" : failed.isEmpty() ? "answered" : answered.isEmpty() ? "failed" : "partial";
        var result = new LinkedHashMap<String, Object>();
        result.put("state", state); result.put("answered", answered); result.put("failed", failed);
        result.put("checked_at", checkedAt);
        result.put("source", "per_model_receipts");
        return result;
    }

    /** The newest receipt for one model on a card, or null. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> forModel(Map<String, Object> status, String model) {
        Map<String, Object> best = null;
        for (String list : List.of("answered", "failed"))
            for (var row : (List<Map<String, Object>>) status.get(list))
                if (Objects.equals(model, row.get("model")) && (best == null
                        || String.valueOf(row.get("checked_at")).compareTo(String.valueOf(best.get("checked_at"))) > 0)) best = row;
        return best;
    }

    private static String key(String account, String model) { return account + "\u0000" + model; }

    private static void consider(Map<String, Map<String, Object>> latest, String key, String model, String account, Map<String, Object> receipt) {
        if (receipt == null || !(receipt.get("checked_at") instanceof String at)) return;
        var previous = latest.get(key);
        if (previous != null && String.valueOf(previous.get("checked_at")).compareTo(at) >= 0) return;
        var row = new LinkedHashMap<String, Object>();
        row.put("model", model); row.put("account", account); row.put("profile", receipt.get("source_profile") == null ? receipt.get("profile") : receipt.get("source_profile"));
        row.put("ok", Boolean.TRUE.equals(receipt.get("ok"))); row.put("code", receipt.get("code")); row.put("checked_at", at);
        latest.put(key, row);
    }
}
