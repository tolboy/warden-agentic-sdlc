package dev.warden.run;

import dev.warden.Main;
import dev.warden.config.ConfigException;
import dev.warden.config.ConfigLoader;
import dev.warden.config.PilotPrepareSpec;
import dev.warden.config.Policy;
import dev.warden.config.Profile;
import dev.warden.config.RepoPath;
import dev.warden.config.TaskDraft;
import dev.warden.config.TaskSpec;
import dev.warden.config.UserConfig;
import dev.warden.config.Workflow;
import dev.warden.git.GitRepository;
import dev.warden.json.Json;
import dev.warden.process.ProcessRunner;
import dev.warden.role.RoleResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code warden pilot prepare --spec FILE --output DIR}
 *
 * Turns an explicit operator spec into a reviewable, self-contained pilot bundle. It copies
 * two named profiles and the shipped three-stage pilot policy into an isolated config home,
 * writes target {@code project.yaml}/{@code task.yaml} beside a runbook, and validates the
 * result with the same parsers a live run would use. It does not execute configured checks,
 * call a vendor, invoke {@code run}/{@code do}/{@code role}, or write into the target
 * checkout or the process global home.
 */
public final class PilotPrepareCommand {

    public static final String PREPARED_CODE = "prepared";
    public static final String OUTPUT_EXISTS = "output_exists";
    public static final String INVALID_SPEC = "invalid_spec";
    public static final String PREPARE_REFUSED = "prepare_refused";
    public static final String USAGE = "usage";

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration WHICH_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_NESTED_RESOURCES = 32;
    private static final Pattern PROFILE_LINE = Pattern.compile("(?m)^profile:\\s*.*$");
    private static final Set<String> SECRET_NAMES = Set.of(
            ".env", ".env.local", ".env.production", "credentials", "credentials.json",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    public record Options(Path spec, Path output) {}

    public record Outcome(boolean ok, String code, Map<String, Object> report) {
        public int exitCode() { return ok ? 0 : 1; }
    }

    private final ProcessRunner processes;
    /**
     * {@code true} uses {@code where.exe} as argv. {@code false} walks {@code PATH} as
     * files and never starts a shell. Tests force {@code false} so a metacharacter payload
     * cannot hide behind the Windows branch. Live {@code run}/{@code role} lookup is unchanged.
     */
    private final boolean windowsExecutableLookup;

    public PilotPrepareCommand() { this(new ProcessRunner(), windowsOs()); }

    public PilotPrepareCommand(ProcessRunner processes) { this(processes, windowsOs()); }

    /**
     * @param windowsExecutableLookup production passes the host OS; the POSIX shell-injection
     *        regression passes {@code false} so the filesystem walk is exercised on Windows too
     */
    public PilotPrepareCommand(ProcessRunner processes, boolean windowsExecutableLookup) {
        this.processes = processes;
        this.windowsExecutableLookup = windowsExecutableLookup;
    }

    static boolean windowsOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public Outcome run(String[] args) {
        try {
            return run(parse(args));
        } catch (IllegalArgumentException usage) {
            return fail(USAGE, List.of(usage.getMessage()), Map.of(
                    "next", "warden pilot prepare --spec <json-file> --output <new-directory>"));
        }
    }

    public static Options parse(String[] args) {
        if (args.length < 2 || !"prepare".equals(args[1])) {
            throw new IllegalArgumentException(
                    "unknown pilot subcommand; use `warden pilot prepare --spec FILE --output DIR`");
        }
        String spec = null;
        String output = null;
        for (int index = 2; index < args.length; index++) {
            String arg = args[index];
            switch (arg) {
                case "--spec" -> spec = needValue(args, ++index, "--spec");
                case "--output" -> output = needValue(args, ++index, "--output");
                default -> throw new IllegalArgumentException("pilot prepare: unknown argument '" + arg + "'");
            }
        }
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("pilot prepare requires --spec <json-file>");
        }
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("pilot prepare requires --output <new-directory>");
        }
        return new Options(Path.of(spec).toAbsolutePath().normalize(),
                Path.of(output).toAbsolutePath().normalize());
    }

    public Outcome run(Options options) {
        if (Files.exists(options.output())) {
            return fail(OUTPUT_EXISTS, List.of(
                    "output '" + options.output() + "' already exists; this command never overwrites "
                            + "or reuses a bundle. Choose a new directory"),
                    Map.of("output", options.output().toString(), "preserved", true));
        }
        if (!Files.isRegularFile(options.spec())) {
            return fail(INVALID_SPEC, List.of("spec file not found: " + options.spec()),
                    Map.of("spec", options.spec().toString()));
        }

        PilotPrepareSpec spec;
        try {
            spec = PilotPrepareSpec.parse(Files.readString(options.spec(), StandardCharsets.UTF_8),
                    options.spec().toString());
        } catch (ConfigException | Json.JsonException invalid) {
            return fail(INVALID_SPEC, issuesOf(invalid), Map.of("spec", options.spec().toString()));
        } catch (IOException unreadable) {
            return fail(INVALID_SPEC, List.of("cannot read spec: " + unreadable.getMessage()),
                    Map.of("spec", options.spec().toString()));
        }

        ConfigException.Collector issues = new ConfigException.Collector(options.spec().toString());
        List<String> unresolved = new ArrayList<>();
        Set<String> copiedResources = new LinkedHashSet<>();

        if (!Files.isDirectory(spec.targetRoot())) {
            issues.add("target.root is not an existing directory: " + spec.targetRoot()
                    + ". This command reads it for provenance only and will not create it");
        }
        if (!Files.isDirectory(spec.sourceHome())) {
            issues.add("profiles.source_home is not an existing directory: " + spec.sourceHome());
        }
        UserConfig source = null;
        Profile implementer = null;
        Profile reviewer = null;
        if (Files.isDirectory(spec.sourceHome())) {
            try {
                source = UserConfig.load(spec.sourceHome());
            } catch (IOException load) {
                issues.add("cannot load profiles.source_home: " + load.getMessage());
            }
        }
        if (source != null) {
            implementer = source.profiles().get(spec.implementerProfile());
            reviewer = source.profiles().get(spec.reviewerProfile());
            if (implementer == null) {
                issues.add("implementer profile '" + spec.implementerProfile()
                        + "' was not found in " + spec.sourceHome() + "/profiles. Known: "
                        + source.profiles().keySet());
            } else {
                checkWriter(issues, implementer);
            }
            if (reviewer == null) {
                issues.add("reviewer profile '" + spec.reviewerProfile()
                        + "' was not found in " + spec.sourceHome() + "/profiles. Known: "
                        + source.profiles().keySet());
            } else {
                checkReviewer(issues, reviewer);
            }
            if (implementer != null && reviewer != null
                    && implementer.vendor().equals(reviewer.vendor())) {
                issues.add("reviewer vendor '" + reviewer.vendor()
                        + "' matches the implementer; require_independent_vendor cannot be satisfied");
            }
        }

        Path policyTemplate = locateShipped("examples/pilot/policy.yaml");
        if (policyTemplate == null) {
            issues.add("cannot locate the shipped examples/pilot/policy.yaml; run from a Warden "
                    + "checkout or set WARDEN_HOME");
        }

        if (issues.hasIssues()) {
            return fail(PREPARE_REFUSED, issues.issues(), Map.of(
                    "spec", options.spec().toString(),
                    "output", options.output().toString()));
        }

        Path parent = options.output().getParent();
        if (parent == null) parent = Path.of(".").toAbsolutePath().normalize();
        try {
            Files.createDirectories(parent);
        } catch (IOException mkdir) {
            return fail(PREPARE_REFUSED, List.of("cannot create output parent: " + mkdir.getMessage()),
                    Map.of("output", options.output().toString()));
        }
        Path staging = parent.resolve(".warden-pilot-prepare-" + UUID.randomUUID());
        try {
            Files.createDirectories(staging);
            Path configHome = staging.resolve("config");
            Path targetWarden = staging.resolve("target").resolve(".warden");
            Files.createDirectories(configHome.resolve("profiles"));
            Files.createDirectories(targetWarden.resolve("tasks"));

            Files.copy(policyTemplate, configHome.resolve("policy.yaml"));
            copyProfile(source, implementer, spec.sourceHome(), configHome,
                    PilotPrepareSpec.IMPLEMENTER_BUNDLE_NAME, copiedResources, unresolved, issues);
            copyProfile(source, reviewer, spec.sourceHome(), configHome,
                    PilotPrepareSpec.REVIEWER_BUNDLE_NAME, copiedResources, unresolved, issues);
            writeProject(targetWarden.resolve("project.yaml"), spec);
            writeTask(targetWarden.resolve("tasks").resolve(spec.taskId() + ".yaml"), spec);

            if (issues.hasIssues()) {
                return fail(PREPARE_REFUSED, issues.issues(), Map.of(
                        "spec", options.spec().toString(),
                        "output", options.output().toString()));
            }

            Generated generated = validateGenerated(staging, spec, issues, unresolved);
            if (issues.hasIssues() || generated == null) {
                return fail(PREPARE_REFUSED, issues.issues(), Map.of(
                        "spec", options.spec().toString(),
                        "output", options.output().toString()));
            }

            String previewRunId = "pilot-preview-" + LocalDate.now().toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().substring(0, 8);
            Files.writeString(staging.resolve("RUNBOOK.md"), runbook(options, spec, generated,
                    previewRunId, unresolved), StandardCharsets.UTF_8);
            Files.writeString(staging.resolve("observations.md"), observations(spec, generated),
                    StandardCharsets.UTF_8);
            Map<String, String> fileHashes = hashTree(staging);
            Map<String, Object> report = buildReport(options, spec, generated,
                    copiedResources, unresolved, fileHashes, previewRunId);
            Files.writeString(staging.resolve("prepare-report.json"), Json.writePretty(report),
                    StandardCharsets.UTF_8);

            if (Files.exists(options.output())) {
                return fail(OUTPUT_EXISTS, List.of(
                        "output '" + options.output() + "' appeared before publish; the existing "
                                + "directory was left unchanged"),
                        Map.of("output", options.output().toString(), "preserved", true));
            }
            try {
                Files.move(staging, options.output());
            } catch (IOException move) {
                try {
                    Files.move(staging, options.output(), StandardCopyOption.COPY_ATTRIBUTES);
                } catch (IOException retry) {
                    return fail(PREPARE_REFUSED, List.of("cannot publish bundle: " + retry.getMessage()),
                            Map.of("output", options.output().toString()));
                }
            }
            staging = null;
            return new Outcome(true, PREPARED_CODE, report);
        } catch (Exception failure) {
            return fail(PREPARE_REFUSED, List.of(String.valueOf(failure.getMessage())), Map.of(
                    "spec", options.spec().toString(),
                    "output", options.output().toString()));
        } finally {
            if (staging != null) deleteQuietly(staging);
        }
    }

    private static void checkWriter(ConfigException.Collector issues, Profile profile) {
        if (!"implementer".equals(profile.role())) {
            issues.add("implementer profile '" + profile.name() + "' has role '" + profile.role()
                    + "', expected implementer");
        }
        if (profile.readOnly()) {
            issues.add("implementer profile '" + profile.name()
                    + "' is read_only; the writer must be writable");
        }
        if (!profile.verified()) {
            issues.add("implementer profile '" + profile.name()
                    + "' is unverified; this command does not invent verification.verified_on");
        }
    }

    private static void checkReviewer(ConfigException.Collector issues, Profile profile) {
        if (!"reviewer".equals(profile.role())) {
            issues.add("reviewer profile '" + profile.name() + "' has role '" + profile.role()
                    + "', expected reviewer");
        }
        if (!profile.readOnly()) {
            issues.add("reviewer profile '" + profile.name()
                    + "' is writable; the independent reviewer must be read_only");
        }
        if (!profile.verified()) {
            issues.add("reviewer profile '" + profile.name()
                    + "' is unverified; this command does not invent verification.verified_on");
        }
    }

    private void copyProfile(UserConfig source, Profile profile, Path sourceHome, Path destHome,
                             String bundleName, Set<String> copiedResources, List<String> unresolved,
                             ConfigException.Collector issues) throws IOException {
        Path sourceFile = sourceHome.resolve("profiles").resolve(profile.name() + ".yaml");
        if (!Files.isRegularFile(sourceFile)) {
            issues.add("profile file missing: " + sourceFile);
            return;
        }
        if (Files.isSymbolicLink(sourceFile) || !contained(sourceHome, sourceFile)) {
            issues.add("profile file '" + sourceFile + "' is a symlink or escapes the source home");
            return;
        }
        String yaml = Files.readString(sourceFile, StandardCharsets.UTF_8);
        String remapped = PROFILE_LINE.matcher(yaml).replaceFirst("profile: " + bundleName);
        Path destFile = destHome.resolve("profiles").resolve(bundleName + ".yaml");
        Files.writeString(destFile, remapped, StandardCharsets.UTF_8);
        Profile copied = Profile.parse(remapped, destFile.toString());
        if (!bundleName.equals(copied.name())) {
            issues.add("failed to remap profile name to " + bundleName);
        }
        if (copied.readOnly() != profile.readOnly()
                || !String.valueOf(copied.model()).equals(String.valueOf(profile.model()))
                || !String.valueOf(copied.effort()).equals(String.valueOf(profile.effort()))
                || copied.verified() != profile.verified()
                || !copied.args().equals(profile.args())) {
            issues.add("copying profile '" + profile.name()
                    + "' would have changed model, effort, args, grants or verified_on");
        }
        copyResource(source, sourceHome, destHome, profile.promptTemplate(), copiedResources,
                unresolved, issues);
        copyResource(source, sourceHome, destHome, profile.jsonSchema(), copiedResources,
                unresolved, issues);
        copiedResources.add("profiles/" + bundleName + ".yaml");
    }

    private void copyResource(UserConfig source, Path sourceHome, Path destHome, String reference,
                              Set<String> copiedResources, List<String> unresolved,
                              ConfigException.Collector issues) throws IOException {
        if (reference == null || reference.isBlank()) return;
        Path resolved = source.resolve(reference);
        if (Path.of(reference).isAbsolute()) {
            unresolved.add("absolute resource left in place, not copied: " + reference);
            return;
        }
        copyRelative(sourceHome, destHome, reference, resolved, copiedResources, unresolved, issues, 0);
    }

    private void copyRelative(Path sourceHome, Path destHome, String declared, Path resolved,
                              Set<String> copiedResources, List<String> unresolved,
                              ConfigException.Collector issues, int depth) throws IOException {
        if (depth > MAX_NESTED_RESOURCES) {
            issues.add("too many nested resources starting from '" + declared
                    + "'; refuse rather than copy a directory tree");
            return;
        }
        String relative = relativeInside(sourceHome, resolved);
        if (relative == null) {
            issues.add("resource '" + declared + "' is not a safe path inside the source configuration home");
            return;
        }
        if (isSecretName(relative)) {
            issues.add("refusing to copy secret or environment file '" + relative + "'");
            return;
        }
        if (copiedResources.contains(relative)) return;
        if (Files.isSymbolicLink(resolved)) {
            issues.add("resource '" + relative + "' is a symlink; refuse rather than copy it");
            return;
        }
        if (!Files.isRegularFile(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            issues.add("resource '" + relative + "' is missing or not a regular file");
            return;
        }
        Path destination = destHome.resolve(relative).normalize();
        if (!contained(destHome, destination)) {
            issues.add("resource '" + relative + "' would escape the destination configuration home");
            return;
        }
        Files.createDirectories(destination.getParent());
        Files.copy(resolved, destination);
        copiedResources.add(relative);
        followNestedJsonRefs(sourceHome, destHome, destination, resolved, copiedResources,
                unresolved, issues, depth + 1);
    }

    private void followNestedJsonRefs(Path sourceHome, Path destHome, Path copied, Path sourceFile,
                                      Set<String> copiedResources, List<String> unresolved,
                                      ConfigException.Collector issues, int depth) throws IOException {
        String name = copied.getFileName().toString().toLowerCase();
        if (!name.endsWith(".json")) return;
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(copied, StandardCharsets.UTF_8));
        } catch (RuntimeException notJson) {
            return;
        }
        List<String> refs = new ArrayList<>();
        collectRefs(parsed, refs);
        for (String ref : refs) {
            if (ref.startsWith("http://") || ref.startsWith("https://")) {
                unresolved.add("external schema $ref left unresolved: " + ref);
                continue;
            }
            // A $ref is a URI reference. Its fragment names a place inside a document, not a
            // file: `#/$defs/text` stays in the same file, and `defs.json#/$defs/text` needs
            // only `defs.json`. Resolving the whole string as a path refused a valid schema.
            int fragment = ref.indexOf('#');
            String file = fragment < 0 ? ref : ref.substring(0, fragment);
            if (file.isBlank()) continue;
            if (file.matches("[A-Za-z][A-Za-z0-9+.-]+:.*")) {
                unresolved.add("schema $ref with a URI scheme left unresolved: " + ref);
                continue;
            }
            Path nested = sourceFile.getParent().resolve(file).normalize();
            copyRelative(sourceHome, destHome, file, nested, copiedResources, unresolved, issues, depth);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectRefs(Object node, List<String> refs) {
        if (node instanceof Map<?, ?> map) {
            Object ref = map.get("$ref");
            if (ref instanceof String value && !value.isBlank()) refs.add(value);
            for (Object value : map.values()) collectRefs(value, refs);
        } else if (node instanceof List<?> list) {
            for (Object value : list) collectRefs(value, refs);
        }
    }

    private static void writeProject(Path file, PilotPrepareSpec spec) throws IOException {
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("project: ").append(TaskDraft.quote(spec.project())).append('\n');
        yaml.append("base_ref: ").append(TaskDraft.quote(spec.base())).append("\n\n");
        yaml.append("checks:\n");
        if (!spec.baselineCommands().isEmpty()) {
            yaml.append("  ").append(PilotPrepareSpec.BASELINE_CHECK_NAME).append(":\n");
            for (String command : spec.baselineCommands()) {
                yaml.append("    - ").append(TaskDraft.quote(command)).append('\n');
            }
        }
        yaml.append("  ").append(PilotPrepareSpec.ACCEPTANCE_CHECK_NAME).append(":\n");
        for (String command : spec.acceptanceCommands()) {
            yaml.append("    - ").append(TaskDraft.quote(command)).append('\n');
        }
        yaml.append("\nscopes:\n  ").append(PilotPrepareSpec.SCOPE_NAME).append(":\n");
        for (String path : spec.scope()) {
            yaml.append("    - ").append(TaskDraft.quote(path)).append('\n');
        }
        yaml.append("\ndefaults:\n");
        yaml.append("  checks: ").append(PilotPrepareSpec.ACCEPTANCE_CHECK_NAME).append('\n');
        if (!spec.baselineCommands().isEmpty()) {
            yaml.append("  baseline_checks: ").append(PilotPrepareSpec.BASELINE_CHECK_NAME).append('\n');
        }
        yaml.append("  risk: ").append(spec.risk()).append('\n');
        yaml.append("  max_fix_attempts: ").append(spec.maxFixAttempts()).append('\n');
        yaml.append("  timeout_minutes: ").append(spec.timeoutMinutes()).append('\n');
        Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
    }

    private static void writeTask(Path file, PilotPrepareSpec spec) throws IOException {
        List<String> nonGoals = new ArrayList<>(spec.nonGoals());
        if (nonGoals.isEmpty()) {
            nonGoals.add("Do not modify Warden, the frozen task contract, or the acceptance checker");
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("version: 1\n");
        yaml.append("id: ").append(spec.taskId()).append('\n');
        yaml.append("goal: ").append(TaskDraft.quote(spec.goal())).append('\n');
        yaml.append("non_goals:\n");
        for (String item : nonGoals) yaml.append("  - ").append(TaskDraft.quote(item)).append('\n');
        yaml.append("risk: ").append(spec.risk()).append('\n');
        yaml.append("scope: ").append(PilotPrepareSpec.SCOPE_NAME).append('\n');
        yaml.append("checks: ").append(PilotPrepareSpec.ACCEPTANCE_CHECK_NAME).append('\n');
        if (spec.reproductionCommand() != null) {
            yaml.append("reproduce: [").append(TaskDraft.quote(spec.reproductionCommand())).append("]\n");
        }
        yaml.append("authority:\n");
        yaml.append("  workspace_write: true\n");
        yaml.append("  network: false\n");
        yaml.append("  land: false\n");
        yaml.append("visual_qa:\n");
        yaml.append("  required: false\n");
        yaml.append("  scenarios: []\n");
        yaml.append("budgets:\n");
        yaml.append("  max_role_runs: ").append(spec.maxRoleRuns()).append('\n');
        yaml.append("  max_cost_usd: ").append(spec.maxCostUsd()).append('\n');
        if (spec.maxElapsedMinutes() != null) {
            yaml.append("  max_elapsed_minutes: ").append(spec.maxElapsedMinutes()).append('\n');
        }
        yaml.append("max_fix_attempts: ").append(spec.maxFixAttempts()).append('\n');
        yaml.append("timeout_minutes: ").append(spec.timeoutMinutes()).append('\n');
        Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
    }

    private Generated validateGenerated(Path staging, PilotPrepareSpec spec,
                                        ConfigException.Collector issues, List<String> unresolved)
            throws Exception {
        Path targetRoot = staging.resolve("target");
        Path configHome = staging.resolve("config");
        ConfigLoader.Loaded loaded;
        try {
            loaded = new ConfigLoader().load(targetRoot, spec.taskId());
        } catch (RuntimeException | IOException invalid) {
            issues.add("generated project/task failed validation: " + invalid.getMessage());
            return null;
        }
        TaskSpec.ResolvedTask resolved = loaded.resolved();
        if (!resolved.baselineCommands().equals(spec.baselineCommands())) {
            issues.add("generated baseline commands " + resolved.baselineCommands()
                    + " do not match the spec " + spec.baselineCommands());
        }
        for (String command : spec.acceptanceCommands()) {
            if (resolved.baselineCommands().contains(command)) {
                issues.add("acceptance command '" + command + "' landed in the green baseline");
            }
            if (!resolved.acceptanceCommands().contains(command)) {
                issues.add("acceptance command '" + command + "' is missing from the generated task");
            }
        }
        UserConfig user;
        try {
            user = UserConfig.load(configHome);
        } catch (IOException load) {
            issues.add("generated config home failed to load: " + load.getMessage());
            return null;
        }
        if (!user.policyPresent() || user.policy() == null) {
            issues.add("generated config home has no loadable policy.yaml");
            return null;
        }
        for (Map.Entry<String, String> problem : user.problems().entrySet()) {
            issues.add("generated " + problem.getKey() + ": " + problem.getValue());
        }
        if (!user.danglingProfileReferences().isEmpty()) {
            issues.add("generated policy references missing profiles: " + user.danglingProfileReferences());
        }
        Policy policy = user.policy();
        if (!"stop".equals(policy.failoverMode())) {
            issues.add("pilot policy must stop on quota exhaustion, got " + policy.failoverMode());
        }
        if (!"full".equals(policy.repairReserve())) {
            issues.add("pilot policy must use budget.repair_reserve: full, got " + policy.repairReserve());
        }
        Policy.RoleSpec writerRole = policy.roles().get("implementer");
        Policy.RoleSpec reviewerRole = policy.roles().get("reviewer");
        if (writerRole == null || writerRole.profiles().size() != 1
                || !PilotPrepareSpec.IMPLEMENTER_BUNDLE_NAME.equals(writerRole.profiles().get(0))) {
            issues.add("pilot policy must use a singleton writer roster of " + PilotPrepareSpec.IMPLEMENTER_BUNDLE_NAME);
        }
        if (reviewerRole == null || !reviewerRole.requireIndependentVendor()) {
            issues.add("pilot policy must require an independent reviewer");
        }
        Profile writer = user.profiles().get(PilotPrepareSpec.IMPLEMENTER_BUNDLE_NAME);
        Profile reader = user.profiles().get(PilotPrepareSpec.REVIEWER_BUNDLE_NAME);
        if (writer == null || reader == null) {
            issues.add("generated config home is missing remapped pilot profiles");
            return null;
        }
        CallPlan plan = new CallPlan(policy.workflow(), stage -> false);
        int requiredCalls = requiredCallBudget(plan, policy.workflow());
        if (spec.maxRoleRuns() < requiredCalls) {
            issues.add("budgets.max_role_runs is " + spec.maxRoleRuns()
                    + " but the three-stage one-repair workflow needs at least " + requiredCalls
                    + " (clean path " + plan.minimumToFinish() + ")");
        }
        try {
            new RoleResolver().resolve("implementer", policy, user.profiles(), null, 0,
                    profile -> executableObserved(profile, unresolved));
            new RoleResolver().resolve("reviewer", policy, user.profiles(), writer.vendor(), 0,
                    profile -> executableObserved(profile, unresolved));
        } catch (RoleResolver.Unresolvable unresolvedRole) {
            issues.add("generated roster cannot fill a required role: " + unresolvedRole.getMessage()
                    + " " + unresolvedRole.rejected());
        }
        if (issues.hasIssues()) return null;
        return new Generated(loaded, user, writer, reader, plan, requiredCalls,
                gitRevision(spec.targetRoot(), spec.base()),
                gitRevision(locateWardenRoot(), "HEAD"));
    }

    private static int requiredCallBudget(CallPlan plan, Workflow workflow) {
        int required = plan.minimumToFinish();
        List<Workflow.Stage> stages = workflow.stages();
        for (int index = 0; index < stages.size(); index++) {
            Workflow.Stage stage = stages.get(index);
            boolean repair = "fix".equals(stage.onFail()) || "fix".equals(stage.onFindings());
            if (!repair) continue;
            int through = plan.minimumToFinish() - plan.callsAfter(index);
            required = Math.max(required, through + plan.reserveForRepair(index));
        }
        return required;
    }

    /**
     * Presence only. Always returns true so a missing executable is an unresolved
     * observation, not a prepare refusal, and never starts the profile command.
     * Windows keeps {@code where.exe} as argv. POSIX walks {@code PATH} as files —
     * interpolating the name into {@code sh -c} would run {@code git; touch marker}.
     */
    private boolean executableObserved(Profile profile, List<String> unresolved) {
        if ("local".equals(profile.runner())) return true;
        String command = "orca".equals(profile.runner()) ? "orca" : profile.command();
        if (command == null || command.isBlank()) {
            unresolved.add("profile " + profile.name() + " has no command to resolve");
            return true;
        }
        if (command.contains("/") || command.contains("\\")) {
            Path path = Path.of(command);
            boolean present = Files.isRegularFile(path) || Files.isExecutable(path);
            if (!present) unresolved.add("absolute command not found (not executed): " + command);
            return true;
        }
        if (windowsExecutableLookup) {
            try {
                ProcessRunner.Result result = processes.run(
                        List.of("where.exe", command), Path.of("."), WHICH_TIMEOUT);
                if (!(result.ok() && !result.stdout().isBlank())) {
                    unresolved.add("command not found on PATH (not executed): " + command);
                }
            } catch (Exception ignored) {
                unresolved.add("command resolution skipped for " + command);
            }
            return true;
        }
        if (!presentOnPosixPath(command)) {
            unresolved.add("command not found on PATH (not executed): " + command);
        }
        return true;
    }

    /**
     * Treats {@code command} as one file name in each PATH directory. Never starts a
     * process, so metacharacters cannot become a shell. Safe to call from tests that
     * force the POSIX branch on Windows.
     */
    static boolean presentOnPosixPath(String command) {
        if (command == null || command.isEmpty() || command.indexOf('\0') >= 0) return false;
        if (command.indexOf('/') >= 0) return false;
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) return false;
        for (String dir : path.split(":", -1)) {
            if (dir.isEmpty()) continue;
            try {
                Path candidate = Path.of(dir).resolve(command);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            } catch (RuntimeException ignored) {
                // Illegal names and unusable PATH entries are "not found", not a crash.
            }
        }
        return false;
    }

    private Map<String, Object> buildReport(Options options, PilotPrepareSpec spec,
                                            Generated generated, Set<String> copiedResources,
                                            List<String> unresolved, Map<String, String> fileHashes,
                                            String previewRunId) throws IOException {
        Map<String, Object> declarations = new LinkedHashMap<>();
        declarations.put("spec_version", (long) PilotPrepareSpec.VERSION);
        declarations.put("task_id", spec.taskId());
        declarations.put("goal", spec.goal());
        declarations.put("risk", spec.risk());
        declarations.put("scope", spec.scope());
        declarations.put("baseline_commands", spec.baselineCommands());
        declarations.put("acceptance_commands", spec.acceptanceCommands());
        declarations.put("reproduction_command", spec.reproductionCommand());
        declarations.put("max_role_runs", spec.maxRoleRuns());
        declarations.put("max_cost_usd", spec.maxCostUsd());
        declarations.put("max_elapsed_minutes", spec.maxElapsedMinutes());
        declarations.put("max_fix_attempts", spec.maxFixAttempts());
        declarations.put("timeout_minutes", spec.timeoutMinutes());
        declarations.put("source_implementer_profile", spec.implementerProfile());
        declarations.put("source_reviewer_profile", spec.reviewerProfile());
        declarations.put("bundle_implementer_profile", PilotPrepareSpec.IMPLEMENTER_BUNDLE_NAME);
        declarations.put("bundle_reviewer_profile", PilotPrepareSpec.REVIEWER_BUNDLE_NAME);
        declarations.put("policy", "examples/pilot/policy.yaml");
        declarations.put("warden_version", Main.VERSION);
        declarations.put("slice", "P2 offline external-pilot preparation; not all of P2; not P2PLAN-17");

        Map<String, Object> writer = profileFacts(generated.writer());
        Map<String, Object> reader = profileFacts(generated.reviewer());

        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("target_root", spec.targetRoot().toString());
        observations.put("target_revision", generated.targetRevision());
        observations.put("warden_revision", generated.wardenRevision());
        observations.put("spec_sha256", GitRepository.contentSha256(options.spec()));
        observations.put("generated_file_sha256", fileHashes);
        observations.put("copied_resources", List.copyOf(copiedResources));
        observations.put("unresolved_dependencies", List.copyOf(unresolved));
        observations.put("required_call_budget", (long) generated.requiredCalls());
        observations.put("clean_path_calls", (long) generated.plan().minimumToFinish());
        observations.put("baseline_distinct_from_acceptance",
                disjoint(spec.baselineCommands(), spec.acceptanceCommands()));

        Map<String, Object> notRun = new LinkedHashMap<>();
        notRun.put("baseline_commands", "not_run");
        notRun.put("acceptance_commands", "not_run");
        notRun.put("reproduction_command", spec.reproductionCommand() == null ? "not_declared" : "not_run");
        notRun.put("vendor_calls", "not_run");
        notRun.put("warden_run", "not_run");
        notRun.put("warden_do", "not_run");
        notRun.put("warden_role", "not_run");
        notRun.put("worktrees_or_terminals", "not_created");

        Map<String, Object> notProven = new LinkedHashMap<>();
        notProven.put("semantic_acceptance_strength", "not_proven");
        notProven.put("green_baseline", "not_run");
        notProven.put("runtime_authentication", "not_proven");
        notProven.put("quota", "not_proven");
        notProven.put("live_readiness", "not_proven");
        // Both limits exist in Warden now; what preparation cannot prove is that a live
        // trial honours them. A strict money cap still does not exist.
        notProven.put("chain_limits_across_continuations", "not_run");
        notProven.put("execution_deadline", spec.maxElapsedMinutes() == null
                ? "not_declared" : "not_run");
        notProven.put("p4_strict_money_cap", "not_implemented");

        String installTo = spec.targetRoot().resolve(".warden").toString().replace('\\', '/');
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("config_home", "<bundle>/config");
        preview.put("working_directory", spec.targetRoot().toString());
        preview.put("install_target_config_to", installTo);
        preview.put("validate", "warden validate " + spec.taskId());
        preview.put("dry_run", "warden run " + spec.taskId() + " --run-id " + previewRunId + " --dry-run");
        preview.put("run_id", previewRunId);
        preview.put("env", Map.of(UserConfig.HOME_ENVIRONMENT_VARIABLE, "<bundle>/config"));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("code", PREPARED_CODE);
        report.put("status", "prepared_not_run");
        report.put("spec", options.spec().toString());
        report.put("bundle", options.output().toString());
        report.put("runbook", options.output().resolve("RUNBOOK.md").toString());
        report.put("report_path", options.output().resolve("prepare-report.json").toString());
        report.put("target_config_installs_to", installTo);
        report.put("declarations", declarations);
        report.put("profiles", Map.of("implementer", writer, "reviewer", reader));
        report.put("observations", observations);
        report.put("not_run", notRun);
        report.put("not_proven", notProven);
        report.put("preview", preview);
        report.put("reproduction_check", Map.of(
                "command", spec.reproductionCommand() == null ? "" : spec.reproductionCommand(),
                "status", spec.reproductionCommand() == null ? "not_declared" : "enforced_by_warden_run",
                "note", reproductionNote(spec)));
        report.put("operator_next_steps", List.of(
                "Review the generated project.yaml, task.yaml, policy and remapped profiles",
                "Copy bundle/target/.warden into " + installTo + " only after that review",
                "Set " + UserConfig.HOME_ENVIRONMENT_VARIABLE + " to the bundle config home",
                "Run the printed validate and dry-run with the fresh run id " + previewRunId,
                reproductionNote(spec),
                "Choose A/B order, known-spend tolerance and whether to authorise a later live run",
                "Do not treat this bundle as proof of quota, authentication or live readiness"));
        report.put("source_and_global_homes_mutated", false);
        report.put("target_checkout_mutated", false);
        return report;
    }

    private static Map<String, Object> profileFacts(Profile profile) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profile", profile.name());
        row.put("role", profile.role());
        row.put("vendor", profile.vendor());
        row.put("model", profile.model());
        row.put("effort", profile.effort());
        row.put("runner", profile.runner());
        row.put("read_only", profile.readOnly());
        row.put("verified", profile.verified());
        row.put("wall_clock_minutes", profile.wallClockMinutes());
        row.put("args", profile.args());
        return row;
    }

    private static String reproductionNote(PilotPrepareSpec spec) {
        return spec.reproductionCommand() == null
                ? "Reproduction is not_declared; acceptance strength is unchecked."
                : "Reproduction is enforced_by_warden_run: the live run will refuse to dispatch "
                        + "if the command passes on the unchanged base. A timeout is inconclusive.";
    }

    private static String runbook(Options options, PilotPrepareSpec spec, Generated generated,
                                  String previewRunId, List<String> unresolved) {
        String bundle = options.output().toString();
        String configHome = options.output().resolve("config").toString();
        String installTo = spec.targetRoot().resolve(".warden").toString();
        String reproduction = spec.reproductionCommand() == null
                ? "not_declared" : spec.reproductionCommand();
        String unresolvedBlock = unresolved.isEmpty()
                ? "None recorded.\n"
                : String.join("\n", unresolved.stream().map(item -> "- " + item).toList()) + "\n";
        return """
                # Offline pilot bundle

                This directory is a **reviewable preparation**, not a live run. `warden pilot prepare`
                did not execute baseline or acceptance commands, did not call a vendor, did not
                invoke `warden run` / `do` / `role`, and did not modify the target checkout or the
                process global Warden home.

                Delivered P2 slice: deterministic offline preparation from an explicit operator
                spec. This does **not** close all of P2 or P2PLAN-17. The task's call, cost and
                repair limits bound every `--continue` of this trial, and
                `budgets.max_elapsed_minutes`, when declared, bounds its execution time; a strict
                money cap does not exist, because a Codex call reports no price.

                ## Install the target contract

                After review, copy `target/.warden/` to:

                    %s

                That path is where the generated `project.yaml` and `tasks/%s.yaml` belong. This
                command did not copy them there.

                Isolated policy/config home (set before validate/run):

                    %s

                ## Preview (spends nothing; uses a fresh run id)

                PowerShell:

                ```powershell
                $env:%s = '%s'
                Set-Location '%s'
                warden validate %s
                warden run %s --run-id %s --dry-run
                ```

                POSIX:

                ```sh
                export %s='%s'
                cd '%s'
                warden validate %s
                warden run %s --run-id %s --dry-run
                ```

                A dry-run reserves that run id. A later live run needs a different one. Live is
                not authorised by this bundle.

                ## Declared checks (not_run)

                Green baseline (must stay distinct from the suite being repaired):

                %s

                Target red acceptance:

                %s

                Declared reproduction (preparation does not execute it):

                    %s

                %s

                ## Provenance

                | Fact | Value | Kind |
                |---|---|---|
                | Warden version | %s | declaration |
                | Warden revision | %s | observation, or explicitly unknown |
                | Target root | `%s` | declaration |
                | Target revision | %s | observation, or explicitly unknown |
                | Writer | %s / %s / %s | copied from the selected profile |
                | Reviewer | %s / %s / %s | copied from the selected profile |
                | Call cap | %s | declaration |
                | Repair | max_fix_attempts=%s, reserve=full, quota=stop | declaration |

                ## Unresolved operational dependencies

                %s
                ## Remaining operator decisions

                1. Confirm the task, base revision and that the red suite is not in the baseline.
                2. Review generated YAML, then install `.warden` into the target checkout.
                3. Confirm the two remapped profiles still name the intended model/effort.
                4. Review the reproduction contract before authorising a live run.
                5. Choose known-spend tolerance, A/B order, and whether to authorise a live run.
                6. Do not read this file as proof of quota, authentication, green baseline or live readiness.

                Bundle path: `%s`
                """.formatted(
                installTo, spec.taskId(), configHome,
                UserConfig.HOME_ENVIRONMENT_VARIABLE, configHome,
                spec.targetRoot(), spec.taskId(), spec.taskId(), previewRunId,
                UserConfig.HOME_ENVIRONMENT_VARIABLE, configHome.replace('\\', '/'),
                spec.targetRoot().toString().replace('\\', '/'), spec.taskId(), spec.taskId(),
                previewRunId,
                bullet(spec.baselineCommands()), bullet(spec.acceptanceCommands()),
                reproduction, reproductionNote(spec),
                Main.VERSION, generated.wardenRevision(), spec.targetRoot(),
                generated.targetRevision(),
                generated.writer().name(), generated.writer().model(), generated.writer().effort(),
                generated.reviewer().name(), generated.reviewer().model(), generated.reviewer().effort(),
                spec.maxRoleRuns(), spec.maxFixAttempts(),
                unresolvedBlock, bundle);
    }

    private static String observations(PilotPrepareSpec spec, Generated generated) {
        String reproduction = spec.reproductionCommand() == null
                ? "not_declared" : spec.reproductionCommand();
        String writer = generated.writer().vendor() + " " + generated.writer().model()
                + " " + generated.writer().effort() + " wall=" + generated.writer().wallClockMinutes();
        String reader = generated.reviewer().vendor() + " " + generated.reviewer().model()
                + " " + generated.reviewer().effort() + " wall=" + generated.reviewer().wallClockMinutes();
        return """
                # Pilot observation sheet

                Filled with **declarations** from `warden pilot prepare`. Measurement fields stay
                `not_run` / `unknown` because this command does not execute the trial.

                | Field | A: ordinary workflow | B: Warden |
                |---|---|---|
                | trial id and date | unknown | prepared, not started |
                | target repository and base commit | same base required | %s / %s |
                | Warden commit | — | %s |
                | task sentence and independent acceptance command | | %s / `%s` |
                | model, effort and limits per role | choose | writer: %s; reviewer: %s |
                | order (which arm ran first) | choose before live | choose before live |
                | setup_minutes | unknown | unknown |
                | active_operator_minutes | unknown | unknown |
                | tool_maintenance_minutes | unknown | unknown |
                | elapsed_minutes, start to accepted or stopped | not_run | not_run |
                | interventions: count, minutes and reason for each | not_run | not_run |
                | outcome and stop_reason | not_started | prepared_not_run |
                | accepted by the independent acceptance check | not_run | not_run |
                | defects found after acceptance | not_applicable | not_applicable |
                | calls (vendor attempts) | not_run | not_run |
                | known_cost_usd and unpriced_calls | not_run | not_run |
                | tokens by vendor, in that vendor's own semantics | not_run | not_run |
                | notes | | Reproduction `%s` is still not_run. Dry-run is not acceptance. |

                Waiting at the human gate is not active time. A stopped trial stays in the sample.
                A Warden patch needed in the middle of a live trial ends that trial.
                """.formatted(
                spec.targetRoot().toString().replace('\\', '/'), generated.targetRevision(),
                generated.wardenRevision(), spec.goal().replace("|", "\\|").replace("\n", " "),
                reproduction, writer, reader, reproduction);
    }

    private static String bullet(List<String> commands) {
        if (commands.isEmpty()) return "- (none declared)\n";
        StringBuilder out = new StringBuilder();
        for (String command : commands) out.append("- `").append(command).append("`\n");
        return out.toString();
    }

    private String gitRevision(Path root, String ref) {
        if (root == null || !Files.isDirectory(root)) return "unknown";
        if (ref == null || !RepoPath.isSafeRef(ref)) return "unknown";
        try {
            ProcessRunner.Result result = processes.run(
                    List.of("git", "-C", root.toString(), "rev-parse", "--verify", ref),
                    root, GIT_TIMEOUT);
            if (result.ok()) {
                String sha = result.stdout().strip();
                return sha.isEmpty() ? "unknown" : sha;
            }
        } catch (Exception ignored) {
            // Provenance is an observation. A missing git is unknown, not a prepare failure.
        }
        return "unknown";
    }

    static Path locateShipped(String relative) {
        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("WARDEN_HOME");
        if (home != null && !home.isBlank()) candidates.add(Path.of(home).resolve(relative));
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path dir = cwd; dir != null; dir = dir.getParent()) {
            candidates.add(dir.resolve(relative));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return null;
    }

    static Path locateWardenRoot() {
        Path policy = locateShipped("examples/pilot/policy.yaml");
        if (policy == null) return Path.of("").toAbsolutePath().normalize();
        return policy.getParent().getParent().getParent();
    }

    private static Map<String, String> hashTree(Path root) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                hashes.put(relative, GitRepository.contentSha256(file));
            }
        }
        return hashes;
    }

    private static String relativeInside(Path home, Path candidate) {
        Path base = home.toAbsolutePath().normalize();
        Path file = candidate.toAbsolutePath().normalize();
        if (!contained(base, file)) return null;
        String relative = base.relativize(file).toString().replace('\\', '/');
        if (relative.startsWith("../") || relative.equals("..") || relative.startsWith("..\\")) return null;
        return RepoPath.normalize(relative);
    }

    private static boolean contained(Path home, Path candidate) {
        Path base = home.toAbsolutePath().normalize();
        Path file = candidate.toAbsolutePath().normalize();
        return file.startsWith(base);
    }

    private static boolean isSecretName(String relative) {
        String name = Path.of(relative).getFileName().toString().toLowerCase();
        if (SECRET_NAMES.contains(name)) return true;
        if (name.endsWith(".pem") || name.endsWith(".pfx") || name.endsWith(".key")) return true;
        return name.startsWith(".env");
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        Set<String> overlap = new LinkedHashSet<>(left);
        overlap.retainAll(right);
        return overlap.isEmpty();
    }

    private static List<String> issuesOf(RuntimeException failure) {
        if (failure instanceof ConfigException config) return config.issues();
        return List.of(String.valueOf(failure.getMessage()));
    }

    private static Outcome fail(String code, List<String> issues, Map<String, Object> extra) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("code", code);
        report.put("issues", issues);
        report.put("message", String.join("; ", issues));
        report.putAll(extra);
        report.put("ready", false);
        return new Outcome(false, code, report);
    }

    private static String needValue(String[] args, int index, String flag) {
        if (index >= args.length) throw new IllegalArgumentException(flag + " needs a value");
        return args[index];
    }

    private static void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    private record Generated(ConfigLoader.Loaded loaded, UserConfig user, Profile writer,
                             Profile reviewer, CallPlan plan, int requiredCalls,
                             String targetRevision, String wardenRevision) {}
}
