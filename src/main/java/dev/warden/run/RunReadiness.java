package dev.warden.run;

import dev.warden.config.CheckSettings;
import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import dev.warden.execution.ModelCheck;
import dev.warden.execution.VendorSettingsService;
import dev.warden.process.ProcessRunner;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Launch boundary shared by run/do/continuations. Embedders can supply their own transport. */
@FunctionalInterface
public interface RunReadiness {
    Map<String, Object> check(UserConfig user, Path project, List<Profile> profiles, boolean strictCost,
                              Consumer<String> progress) throws Exception;

    static RunReadiness live(ProcessRunner processes) {
        return using(new ModelCheck(processes));
    }

    /** Shared by production and offline transport tests; only the selected representatives infer. */
    static RunReadiness using(ModelCheck checks) {
        return (user, project, profiles, strictCost, progress) -> {
            if (!CheckSettings.load(user.home()).beforeRun())
                return Map.of("ok", true, "code", "check_disabled_by_user", "results", List.of());
            if (profiles.isEmpty()) return Map.of("ok", true, "code", "check_no_models", "results", List.of());
            // A token ceiling is not a monetary reservation. Never spend ahead of a strict cap.
            if (strictCost) return ModelCheck.failure("check_strict_cost_unproven");
            Map<String, Profile> representatives = new LinkedHashMap<>();
            for (Profile profile : profiles) {
                String group = profile.connectionRef() != null
                        ? VendorSettingsService.vendorId(profile) + "|" + profile.model()
                        : "local".equals(profile.runner()) ? profile.name() : VendorSettingsService.vendorId(profile);
                representatives.putIfAbsent(group, profile);
            }
            Map<String, Profile> scoped = new LinkedHashMap<>();
            for (Profile profile : representatives.values()) scoped.putIfAbsent(profile.name() + "@" + profile.effort(), profile);
            UserConfig snapshot = new UserConfig(user.home(), user.policy(), scoped, user.problems(),
                    user.policyPresent(), user.connections());
            Map<String,Object> report = new LinkedHashMap<>(checks.run(snapshot, project, new LinkedHashSet<>(scoped.keySet()), progress));
            report.put("scope", "cli_vendor_representatives_and_selected_api_models");
            report.put("selected_profiles", profiles.size());
            report.put("checked_representatives", scoped.size());
            report.put("assumption", "A CLI pong confirms its vendor/auth/channel using one selected model. Other CLI models share that availability assumption; each selected API model is checked separately.");
            return report;
        };
    }
}
