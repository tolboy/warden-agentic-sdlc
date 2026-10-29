package dev.warden.execution;

import dev.warden.config.Profile;
import dev.warden.config.UserConfig;
import java.nio.file.Path;
import java.util.*;

/**
 * The single effort capability list shared by the settings UI, save validation and the task
 * router. Levels come from the service's loaded model catalog when it lists the model, else from
 * the built-in table; they are always returned weakest first. A profile whose launch does not
 * carry {{effort}} (or Orca) has no selectable levels at all.
 */
public final class EffortCapabilities {
    /** Canonical weakest-to-strongest order; catalog levels outside it keep catalog order at the end. */
    public static final List<String> ORDER = List.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra");

    /** Raised by save paths when an effort is not one of the model's levels. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) { super(message); }
    }

    private final Path home;
    private final UserConfig user;
    private final Map<String, Optional<VendorSettingsService.CatalogEfforts>> catalogs = new HashMap<>();

    public EffortCapabilities(Path home, UserConfig user) { this.home = home; this.user = user; }

    /** Levels for running {@code model} through {@code profile}'s launch; empty when effort cannot be chosen. */
    public List<String> levels(Profile profile, String model) {
        if (!launchBound(profile) || model == null) return List.of();
        if (home != null && user != null) {
            var catalog = catalogs.computeIfAbsent(VendorSettingsService.displayVendorId(profile),
                    id -> Optional.ofNullable(VendorSettingsService.catalogEfforts(home, user, profile)));
            var listed = catalog.map(models -> models.levels().get(model)).orElse(null);
            if (listed != null) {
                if (profile.connectionRef()!=null && !"openrouter_reasoning".equals(
                        catalog.map(c -> c.deliveries().get(model)).orElse(null))) return List.of();
                var offered=ordered(listed);
                return profile.connectionRef()==null?offered:offered.stream().filter(API_LEVELS::contains).toList();
            }
        }
        if(profile.connectionRef()!=null)return List.of();
        return builtIn(profile, model);
    }

    /** Same as {@link #levels} for the profile's configured model. */
    public List<String> levels(Profile profile) { return profile == null ? List.of() : levels(profile, profile.model()); }

    /** Capability provenance is separate from proof that a particular dispatch sent a value. */
    public Map<String,Object> describe(Profile profile) {
        var levels=levels(profile);
        var result=new LinkedHashMap<String,Object>();
        result.put("levels",levels);
        result.put("delivery",!launchBound(profile)?"unsupported":profile.connectionRef()!=null?
                levels.isEmpty()?"unsupported":"openrouter_reasoning":
                "orca".equals(profile.runner())?"orca_launch_setting":"cli_argument_template");
        var catalog=profile==null?null:catalogs.get(VendorSettingsService.displayVendorId(profile));
        var entry=catalog==null?null:catalog.orElse(null);
        boolean listed=entry!=null && entry.levels().containsKey(profile.model());
        result.put("source",listed?"model_catalog":levels.isEmpty()?"unavailable":"builtin_compatibility_table");
        result.put("source_description",listed?entry.source():null);
        result.put("observed_at",listed?entry.fetchedAt():null);
        result.put("provider_confirmed",false);
        return result;
    }

    /**
     * Throws {@link Unsupported} unless {@code effort} is valid for {@code model}. A level that
     * is unchanged and unknown to every source is kept rather than silently replaced.
     */
    public void require(Profile old, String model, String effort, boolean effortChanged) {
        if (effort == null) return;
        var levels = levels(old, model);
        if (profileTransport(old) ? !levels.contains(effort) : levels.isEmpty() ? effortChanged : !levels.contains(effort))
            throw new Unsupported("effort " + effort + " is not offered for " + model + "; supported: " + levels);
    }

    static boolean launchBound(Profile profile) {
        if (profile == null) return false;
        if(profileTransport(profile))return true;
        if(profile.harness()!=null)return false;
        return "orca".equals(profile.runner())
                || ("direct".equals(profile.runner()) && profile.args().stream().anyMatch(a -> a.contains("{{effort}}")));
    }

    public static final List<String> API_LEVELS=List.of("none","minimal","low","medium","high","xhigh","max");
    private static boolean profileTransport(Profile profile){
        return "api".equals(profile.runner()) || "opencode".equals(profile.harness());
    }
    /** Executable admission uses the same current model/connection catalog as routing and settings. */
    public String transportProblem(Profile profile){
        return profile.effort()==null || !profileTransport(profile) || levels(profile).contains(profile.effort())
                ? null : "role_effort_unsupported";
    }
    public static String problem(Profile profile){
        if(profile==null || profile.effort()==null || !profileTransport(profile))return null;
        try {
            if(profile.apiSettings()==null || profile.apiSettings().home()==null)return "role_effort_unsupported";
            Path home=profile.apiSettings().home();
            return new EffortCapabilities(home,UserConfig.load(home)).transportProblem(profile);
        } catch(Exception unavailable){return "role_effort_unsupported";}
    }

    /** Conservative table used only while no catalog lists the model. */
    static List<String> builtIn(Profile profile, String model) {
        String vendor = Profile.canonicalVendor(profile.modelVendor());
        if ("openai".equals(vendor) && model.startsWith("gpt-6")) return List.of("low", "medium", "high", "xhigh", "max");
        if ("x-ai".equals(vendor) && "grok-4.7".equals(model)) return List.of("low", "medium", "high", "xhigh");
        return List.of();
    }

    public static List<String> ordered(Collection<String> levels) {
        var known = new ArrayList<String>();
        var other = new ArrayList<String>();
        for (String level : new LinkedHashSet<>(levels)) (ORDER.contains(level) ? known : other).add(level);
        known.sort(Comparator.comparingInt(ORDER::indexOf));
        known.addAll(other);
        return List.copyOf(known);
    }
}
