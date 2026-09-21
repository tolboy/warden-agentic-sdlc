package dev.warden.config;

import dev.warden.role.RoleRunner;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Who fills a stage, at what effort, on which runner — for this run only.
 *
 * The home roster is the operator's subscriptions. Editing it to try Opus on xhigh for one
 * reading, or to host the second reader on Orca so the dashboard shows WORKING, used to
 * rewrite {@code ~/.warden} and drop {@code verified_on}. A live Unity run paid for that
 * dance. This overlay is the same instruction, scoped to one invocation, and it never
 * touches those files.
 *
 * Keys are workflow stage names ({@code review-second}, {@code look}), not roles: two
 * reviewer stages share a role and a rotate strategy, and the operator thinks in stages.
 *
 * <h2>What an overlay may not do</h2>
 *
 * An overlay chooses among things the operator already declared and verified. It is not a
 * second, weaker way to declare a profile:
 *
 * <ul>
 *   <li>{@code profile} is a pin, and a pin is filtered by the roster's independence rule
 *       like any other choice — see {@link RoleRunner#pinForRun}. Choosing a reader has
 *       never been permission to waive the contract.</li>
 *   <li>{@code effort} is refused unless it will actually reach the vendor. Profile parsing
 *       has always enforced that ({@code {{effort}}} in args for a direct CLI, an explicit
 *       model for Orca, never for {@code runner: local}); an overlay that skipped it would
 *       write {@code xhigh} into the evidence and change nothing about the call.</li>
 *   <li>{@code host: orca} moves the stage to a profile the operator declared with
 *       {@code runner: orca}. It does not rewrite a direct profile into one, because Orca's
 *       {@code worker-start} forwards agent, model and effort and nothing else: the tool
 *       grants, the sandbox, the turn ceiling and the MCP configuration would all be
 *       dropped, and the verification stamp on the direct profile is a statement about the
 *       channel that was probed, not about this one.</li>
 * </ul>
 *
 * {@link #problem} answers all three before the first paid stage, so a refusal costs
 * nothing. {@link #adapt} then only performs what was already found admissible.
 */
public final class RunOverride {

    public static final RunOverride NONE = new RunOverride(Map.of(), Map.of(), Map.of());

    private static final Set<String> ROW_KEYS = Set.of("profile", "effort", "host");

    private final Map<String, String> profileByStage;
    private final Map<String, String> effortByStage;
    private final Map<String, String> hostByStage;

    public RunOverride(Map<String, String> profileByStage, Map<String, String> effortByStage,
                       Map<String, String> hostByStage) {
        this.profileByStage = Map.copyOf(profileByStage);
        this.effortByStage = Map.copyOf(effortByStage);
        this.hostByStage = Map.copyOf(hostByStage);
    }

    public boolean isEmpty() {
        return profileByStage.isEmpty() && effortByStage.isEmpty() && hostByStage.isEmpty();
    }

    /** Every stage this overlay says anything about, for a preflight and for an error. */
    public Set<String> stages() {
        Set<String> named = new java.util.LinkedHashSet<>(profileByStage.keySet());
        named.addAll(effortByStage.keySet());
        named.addAll(hostByStage.keySet());
        return java.util.Collections.unmodifiableSet(named);
    }

    /** {@code other} wins on a colliding stage. */
    public RunOverride merged(RunOverride other) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        Map<String, String> profiles = new LinkedHashMap<>(profileByStage);
        profiles.putAll(other.profileByStage);
        Map<String, String> efforts = new LinkedHashMap<>(effortByStage);
        efforts.putAll(other.effortByStage);
        Map<String, String> hosts = new LinkedHashMap<>(hostByStage);
        hosts.putAll(other.hostByStage);
        return new RunOverride(profiles, efforts, hosts);
    }

    /**
     * Apply the profile choices, as a person's choices and not as the ladder's.
     *
     * {@link RoleRunner#pinForRun} keeps the roster's independence filter over the pin. A
     * person choosing which reader runs is not a person waiving
     * {@code require_independent_vendor}; a run that wants the weaker reading still has to
     * say {@code review_assurance: same_vendor_peer} in the contract, where it is recorded.
     */
    public void pin(RoleRunner roles) {
        if (roles == null) return;
        for (Map.Entry<String, String> pin : profileByStage.entrySet()) {
            roles.pinForRun(pin.getKey(), pin.getValue());
        }
    }

    /**
     * The profile this stage will actually dispatch, after the host and effort overlays.
     *
     * Host is applied first and effort second, so an effort named for a stage lands on
     * whichever profile ends up running it rather than on the one the roster happened to
     * pick. Pins are applied through {@link #pin}; this only mutates the already-chosen
     * profile. Anything this cannot do honestly it leaves alone — {@link #problem} has
     * already refused those before a vendor was paid.
     */
    public Profile adapt(String stage, Profile chosen, Map<String, Profile> roster) {
        if (chosen == null || stage == null) return chosen;
        Profile next = chosen;
        if ("orca".equals(hostByStage.get(stage))) {
            Profile hosted = onOrca(next, roster);
            if (hosted != null) next = hosted;
        }
        String effort = effortByStage.get(stage);
        if (effort != null && !effort.equals(next.effort()) && effortReaches(next, effort)) {
            next = next.withEffort(effort);
        }
        return next;
    }

    /**
     * Why this stage's overlay cannot be honoured, or null when it can.
     *
     * Called from the loop's preflight, in the same breath as the missing-MCP and
     * unavailable-reader checks and for the same reason: a roster fault that is knowable
     * from configuration alone must never be discovered after the implementer has run.
     */
    public String problem(String stage, Profile chosen, Map<String, Profile> roster) {
        if (chosen == null || stage == null) return null;
        Profile next = chosen;
        if ("orca".equals(hostByStage.get(stage))) {
            Profile hosted = onOrca(next, roster);
            if (hosted == null) return hostRefusal(stage, next, roster);
            next = hosted;
        }
        String effort = effortByStage.get(stage);
        if (effort != null && !effort.equals(next.effort()) && !effortReaches(next, effort)) {
            return effortRefusal(stage, next, effort);
        }
        return null;
    }

    /**
     * Whether a declared effort would reach the vendor, by exactly the rules
     * {@link Profile#parse} applies to a profile that declares one on disk.
     *
     * The overlay cannot be looser than the file: an effort that a home profile would be
     * refused for is an effort this run would only write into evidence.
     */
    private static boolean effortReaches(Profile profile, String effort) {
        if (!effort.matches("[a-z][a-z0-9_-]*")) return false;
        return switch (profile.runner()) {
            case "local" -> false;
            case "orca" -> profile.model() != null && !profile.model().isBlank();
            default -> profile.args().stream().anyMatch(argument -> argument.contains("{{effort}}"));
        };
    }

    private static String effortRefusal(String stage, Profile profile, String effort) {
        String why = switch (profile.runner()) {
            case "local" -> "runner: local starts no process and cannot carry an effort level";
            case "orca" -> "runner: orca needs an explicit model before an effort means anything";
            default -> "profile '" + profile.name() + "' has no {{effort}} in its args, so the "
                    + "level would be recorded and never sent";
        };
        if (!effort.matches("[a-z][a-z0-9_-]*")) {
            why = "'" + effort + "' is not a provider effort level";
        }
        return "--effort " + stage + "=" + effort + " cannot reach the vendor: " + why
                + ". Add {{effort}} to the profile's args (and remove the literal level), or drop"
                + " the flag — an effort Warden cannot deliver is worse than none, because the"
                + " evidence would claim it.";
    }

    /**
     * The profile that runs this stage as an Orca worker, or null when none can.
     *
     * Order matters. A profile already on Orca is itself. Otherwise the operator's declared
     * twin — same role, same vendor, same agent, {@code runner: orca}, verified — is the
     * answer, because it is the configuration they probed for that channel. A direct
     * profile's verification never transfers to a different transport, even without args.
     */
    private static Profile onOrca(Profile chosen, Map<String, Profile> roster) {
        if ("orca".equals(chosen.runner())) return chosen;
        Profile twin = twinOf(chosen, roster, true);
        if (twin != null) return twin;
        return null;
    }

    /**
     * The declared {@code runner: orca} sibling of this profile, or null.
     *
     * @param verifiedOnly the resolver's rule — an unverified profile does not run. Passed
     *                     false only to write a better refusal: "the twin is there, stamp it"
     *                     is a different instruction from "write one", and the operator who
     *                     prepared a twin and never probed it deserves the first.
     */
    public static Profile twinOf(Profile chosen, Map<String, Profile> roster, boolean verifiedOnly) {
        if (roster == null) return null;
        for (Profile candidate : roster.values().stream()
                .sorted(java.util.Comparator.comparing(Profile::name)).toList()) {
            if (!"orca".equals(candidate.runner())) continue;
            if (!candidate.role().equals(chosen.role())) continue;
            if (!candidate.vendor().equals(chosen.vendor())) continue;
            if (!java.util.Objects.equals(candidate.model(), chosen.model())) continue;
            if (candidate.readOnly() != chosen.readOnly()) continue;
            if (verifiedOnly && !candidate.verified()) continue;
            if (java.util.Objects.equals(candidate.command(), chosen.command())) return candidate;
        }
        return null;
    }

    private String hostRefusal(String stage, Profile chosen, Map<String, Profile> roster) {
        Profile unstamped = twinOf(chosen, roster, false);
        if (unstamped != null) {
            return "--host " + stage + "=orca found the Orca twin '" + unstamped.name()
                    + "', but it carries no verified_on, and the resolver does not dispatch an "
                    + "unverified profile. Run its probe once and stamp it — "
                    + "`warden profiles --verify " + unstamped.name() + " --confirm` — and this "
                    + "flag will use it. The direct profile's stamp cannot stand in: it was "
                    + "earned on a channel that forwards argv, and this one does not.";
        }
        StringBuilder lost = new StringBuilder();
        if (!chosen.args().isEmpty()) lost.append("its argv (").append(chosen.args().size())
                .append(" entries, including any tool grants, sandbox and turn ceiling)");
        if (chosen.mcpConfig() != null) {
            if (lost.length() > 0) lost.append(", ");
            lost.append("its MCP configuration ").append(chosen.mcpConfig());
        }
        if (chosen.vision() != null) {
            if (lost.length() > 0) lost.append(", ");
            lost.append("its ").append(chosen.vision().delivery()).append(" vision delivery");
        }
        if (lost.isEmpty()) lost.append("the direct channel's verification");
        return "--host " + stage + "=orca cannot host profile '" + chosen.name() + "': Orca's "
                + "worker-start forwards agent, model and effort only, so " + lost + " would be "
                + "dropped, and the verification stamp on this profile was earned on the direct "
                + "channel. Declare the Orca twin you want instead — a profile with role: "
                + chosen.role() + ", vendor: " + chosen.vendor() + ", command: " + chosen.command()
                + ", runner: orca, prompt_delivery: workspace_file — verify it once, and this "
                + "flag will find it.";
    }

    public boolean hostedOnOrca(String stage) {
        return stage != null && "orca".equals(hostByStage.get(stage));
    }

    /** The profile pin for {@code stage}, or null when this overlay does not name one. */
    public String pinnedProfile(String stage) {
        return stage == null ? null : profileByStage.get(stage);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        if (!profileByStage.isEmpty()) value.put("profile", new LinkedHashMap<>(profileByStage));
        if (!effortByStage.isEmpty()) value.put("effort", new LinkedHashMap<>(effortByStage));
        if (!hostByStage.isEmpty()) value.put("host", new LinkedHashMap<>(hostByStage));
        return value;
    }

    /** The overlay a {@link #toMap} wrote, read back — for the run that Conductor starts. */
    public static RunOverride fromMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return NONE;
        if (!Set.of("profile", "effort", "host").containsAll(value.keySet())) {
            throw new IllegalArgumentException("run override contains unknown fields");
        }
        Map<String, String> profiles = stringMap(value.get("profile"));
        Map<String, String> efforts = stringMap(value.get("effort"));
        Map<String, String> hosts = stringMap(value.get("host"));
        if (hosts.values().stream().anyMatch(host -> !"orca".equals(host))) {
            throw new IllegalArgumentException("run override host must be orca");
        }
        if (profiles.isEmpty() && efforts.isEmpty() && hosts.isEmpty()) return NONE;
        return new RunOverride(profiles, efforts, hosts);
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> value = new LinkedHashMap<>();
        if (raw == null) return value;
        if (!(raw instanceof Map<?, ?>)) throw new IllegalArgumentException("run override requires maps");
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (!(key instanceof String name) || name.isBlank()
                        || !(item instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("run override requires non-empty string entries");
                }
                value.put(name, text);
            });
        }
        return value;
    }

    /**
     * CLI: {@code --use review-second=claude-review --effort review-second=xhigh
     * --host review-second=orca}. Repeatable. Unknown flags are not this class's problem.
     */
    public static RunOverride fromArgs(String[] args) {
        if (args == null || args.length == 0) return NONE;
        Map<String, String> profiles = new LinkedHashMap<>();
        Map<String, String> efforts = new LinkedHashMap<>();
        Map<String, String> hosts = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index++) {
            String flag = args[index];
            if (Set.of("--use", "--effort", "--host").contains(flag)
                    && (index + 1 == args.length || args[index + 1].startsWith("--"))) {
                throw new IllegalArgumentException(flag + " needs stage=value");
            }
            if ("--use".equals(flag)) putPair(profiles, args[++index], "--use");
            else if ("--effort".equals(flag)) putPair(efforts, args[++index], "--effort");
            else if ("--host".equals(flag)) putPair(hosts, args[++index], "--host");
        }
        for (Map.Entry<String, String> host : hosts.entrySet()) {
            if (!"orca".equals(host.getValue())) {
                throw new IllegalArgumentException("--host " + host.getKey() + "=" + host.getValue()
                        + " is not supported; the only overlay runner is orca");
            }
        }
        if (profiles.isEmpty() && efforts.isEmpty() && hosts.isEmpty()) return NONE;
        return new RunOverride(profiles, efforts, hosts);
    }

    /**
     * Task YAML {@code use:}. Stage names are free; unknown row keys are refused.
     * An empty or absent block is {@link #NONE}.
     */
    public static RunOverride parse(Values node) {
        if (node == null || node.keys().isEmpty()) return NONE;
        Map<String, String> profiles = new LinkedHashMap<>();
        Map<String, String> efforts = new LinkedHashMap<>();
        Map<String, String> hosts = new LinkedHashMap<>();
        for (String stage : node.keys()) {
            Values row = node.optMap(stage).rejectUnknownKeys(ROW_KEYS);
            String profile = row.optString("profile", null);
            String effort = row.optString("effort", null);
            String host = row.optString("host", null);
            if (profile != null) profiles.put(stage, profile);
            if (effort != null) efforts.put(stage, effort);
            if (host != null) {
                if (!"orca".equals(host)) {
                    node.collector().add("use." + stage + ".host must be orca when present, got '"
                            + host + "'");
                } else {
                    hosts.put(stage, host);
                }
            }
        }
        if (profiles.isEmpty() && efforts.isEmpty() && hosts.isEmpty()) return NONE;
        return new RunOverride(profiles, efforts, hosts);
    }

    /** The {@code use:} block as a task file would carry it, or "" when there is nothing to say. */
    public String toYaml() {
        if (isEmpty()) return "";
        StringBuilder yaml = new StringBuilder("use:\n");
        for (String stage : stages()) {
            yaml.append("  ").append(stage).append(":\n");
            if (profileByStage.containsKey(stage)) {
                yaml.append("    profile: ").append(profileByStage.get(stage)).append('\n');
            }
            if (effortByStage.containsKey(stage)) {
                yaml.append("    effort: ").append(effortByStage.get(stage)).append('\n');
            }
            if (hostByStage.containsKey(stage)) {
                yaml.append("    host: ").append(hostByStage.get(stage)).append('\n');
            }
        }
        return yaml.toString();
    }

    private static void putPair(Map<String, String> into, String raw, String flag) {
        int eq = raw.indexOf('=');
        if (eq <= 0 || eq == raw.length() - 1) {
            throw new IllegalArgumentException(flag + " needs stage=value, got '" + raw + "'");
        }
        String stage = raw.substring(0, eq).strip();
        String value = raw.substring(eq + 1).strip();
        if (stage.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException(flag + " needs stage=value, got '" + raw + "'");
        }
        into.put(stage, value);
    }
}
