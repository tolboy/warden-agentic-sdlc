package dev.warden.config;

import dev.warden.yaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code warden roster}: see and change which profile fills which role, and a profile's
 * model/effort, without a YAML round-trip.
 *
 * Policy and profile files are written by people and carry comments that are the record of
 * why a flag is there. Re-emitting the parsed tree would drop those comments. So this command
 * copies the file, then rewrites the one or two target lines by indentation-aware matching,
 * then re-parses. If the parser refuses the result, the copy is restored byte-for-byte.
 */
public final class RosterCommand {

    public record Outcome(boolean ok, Map<String, Object> report, String text) {
        public int exitCode() { return ok ? 0 : 1; }
    }

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public Outcome run(Path home, String[] args) throws IOException {
        int index = 0;
        if (args.length > 0 && args[index].equals("roster")) index++;
        if (index >= args.length || args[index].equals("--text")) {
            boolean text = false;
            for (int i = index; i < args.length; i++) {
                if (args[i].equals("--text")) text = true;
                else if (args[i].startsWith("-")) {
                    return fail("usage", "roster: unknown flag '" + args[i] + "'",
                            home.resolve("policy.yaml"), null, null);
                } else {
                    return fail("usage", "roster: unknown argument '" + args[i] + "'",
                            home.resolve("policy.yaml"), null, null);
                }
            }
            return list(home, text);
        }
        return switch (args[index]) {
            case "set" -> set(home, args, index + 1);
            case "model" -> model(home, args, index + 1);
            default -> fail("usage", "roster: unknown subcommand '" + args[index] + "'",
                    home.resolve("policy.yaml"), null, null);
        };
    }

    /**
     * Backup, write, re-parse. On parser refusal the original bytes are restored and the
     * backup is left in place. The suite drives this directly for the restore case.
     */
    public Outcome rewriteAndReparse(Path file, String edited, String parseKind, String successCode,
                                     List<String> before, List<String> after,
                                     Map<String, Object> extra) throws IOException {
        byte[] original = Files.readAllBytes(file);
        Path backup = copyBackup(file);
        try {
            Files.writeString(file, edited, StandardCharsets.UTF_8);
        } catch (IOException writeFailed) {
            Files.write(file, original);
            throw writeFailed;
        }
        try {
            String written = Files.readString(file, StandardCharsets.UTF_8);
            if ("profile".equals(parseKind)) Profile.parse(written, file.toString());
            else Policy.parse(written, file.toString());
        } catch (RuntimeException parseFailed) {
            Files.write(file, original);
            Map<String, Object> extraFail = extra == null ? Map.of() : extra;
            return fail("reparse_failed", String.valueOf(parseFailed.getMessage()),
                    file, backup, changed(before, after), extraFail);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", true);
        report.put("code", successCode);
        report.put("file", file.toString());
        report.put("backup", backup.toString());
        report.put("changed", changed(before, after));
        if (extra != null) report.putAll(extra);
        return new Outcome(true, report, null);
    }

    // ---------------------------------------------------------------- list

    private Outcome list(Path home, boolean text) throws IOException {
        UserConfig user = UserConfig.load(home);
        Path policyFile = home.resolve("policy.yaml");
        Map<String, Object> report = new LinkedHashMap<>();
        boolean loaded = user.policy() != null;
        report.put("ok", loaded);
        report.put("code", loaded ? "roster" : (user.policyPresent() ? "policy_unreadable" : "policy_not_found"));
        report.put("file", policyFile.toString());
        report.put("backup", null);
        report.put("changed", null);
        report.put("home", home.toString());

        List<Object> roles = new ArrayList<>();
        if (user.policy() != null) {
            for (Policy.RoleSpec spec : user.policy().roles().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", spec.role());
                row.put("strategy", spec.strategy());
                row.put("require_independent_vendor", spec.requireIndependentVendor());
                List<Object> profiles = new ArrayList<>();
                for (String name : spec.profiles()) {
                    profiles.add(profileRow(user, name));
                }
                row.put("profiles", profiles);
                roles.add(row);
            }
        }
        report.put("roles", roles);

        List<Object> workflow = new ArrayList<>();
        Workflow chain = user.policy() != null ? user.policy().workflow() : null;
        if (chain != null) {
            for (Workflow.Stage stage : chain.stages()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("stage", stage.name());
                row.put("run", stage.kind().jsonValue());
                if (stage.role() != null) {
                    row.put("role", stage.role());
                    row.put("fills", wouldFill(user, chain, stage));
                }
                row.put("when", stage.when());
                workflow.add(row);
            }
        }
        report.put("workflow", workflow);
        report.put("problems", user.problems());
        report.put("dangling_policy_references", user.danglingProfileReferences());
        String table = text ? renderText(report) : null;
        return new Outcome(loaded, report, table);
    }

    /**
     * Which profile this stage would get, by the roster's own arithmetic.
     *
     * A `rotate` role reads as one cell in the ROLE table and fills two stages with two
     * different vendors; which stage gets which was only ever visible in the summary of a
     * run that had already been paid for. The stage's position in the chain is what decides
     * it, and that position is knowable from the policy alone.
     *
     * Configuration only. A run additionally probes that the executable is there, refuses a
     * reader that shares a vendor with whoever wrote the candidate, and honours whatever
     * per-run overlay was typed — so this is what the roster says, and `warden run --dry-run`
     * is what the run says.
     */
    private static String wouldFill(UserConfig user, Workflow chain, Workflow.Stage stage) {
        Policy.RoleSpec spec = user.policy() == null ? null : user.policy().roles().get(stage.role());
        if (spec == null) return null;
        List<String> eligible = new ArrayList<>();
        for (String name : spec.profiles()) {
            Profile profile = user.profiles().get(name);
            if (profile != null && profile.role().equals(stage.role())) eligible.add(name);
        }
        if (eligible.isEmpty()) return null;
        if ("first".equals(spec.strategy())) return eligible.get(0);
        return eligible.get(Math.floorMod(chain.rotationPositionOf(stage), eligible.size()));
    }

    private static Map<String, Object> profileRow(UserConfig user, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("profile", name);
        Profile profile = user.profiles().get(name);
        if (profile == null) {
            row.put("vendor", null);
            row.put("model", null);
            row.put("effort", null);
            row.put("runner", null);
            row.put("read_only", null);
            row.put("verified", null);
            return row;
        }
        row.put("vendor", profile.vendor());
        row.put("model", profile.model());
        row.put("effort", profile.effort());
        row.put("runner", profile.runner());
        row.put("read_only", profile.readOnly());
        row.put("verified", verifiedOn(user.home().resolve("profiles").resolve(name + ".yaml")));
        return row;
    }

    @SuppressWarnings("unchecked")
    private static String verifiedOn(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            Object parsed = Yaml.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) return null;
            Object verification = root.get("verification");
            if (!(verification instanceof Map<?, ?> map)) return null;
            Object date = map.get("verified_on");
            return date == null ? null : String.valueOf(date);
        } catch (RuntimeException | IOException ignored) {
            return null;
        }
    }

    private static String renderText(Map<String, Object> report) {
        StringBuilder out = new StringBuilder();
        out.append("ROLE            STRATEGY  INDEPENDENT  PROFILES\n");
        Object roles = report.get("roles");
        if (roles instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                out.append(pad(String.valueOf(row.get("role")), 16));
                out.append(pad(String.valueOf(row.get("strategy")), 10));
                out.append(pad(String.valueOf(row.get("require_independent_vendor")), 13));
                out.append(renderProfileCells(row.get("profiles")));
                out.append('\n');
            }
        }
        out.append("\nWORKFLOW\n");
        Object workflow = report.get("workflow");
        if (workflow instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) continue;
                out.append("  ").append(row.get("stage"));
                out.append("  run=").append(row.get("run"));
                if (row.get("role") != null) out.append("  role=").append(row.get("role"));
                if (row.get("fills") != null) out.append("  fills=").append(row.get("fills"));
                Object when = row.get("when");
                out.append("  when=").append(when instanceof List<?> w && w.isEmpty() ? "always" : when);
                out.append('\n');
            }
        }
        out.append("\nPROBLEMS\n");
        Object problems = report.get("problems");
        if (problems instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append("  ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        } else {
            out.append("  (none)\n");
        }
        out.append("\nDANGLING_POLICY_REFERENCES\n");
        Object dangling = report.get("dangling_policy_references");
        if (dangling instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) out.append("  ").append(item).append('\n');
        } else {
            out.append("  (none)\n");
        }
        return out.toString();
    }

    private static String renderProfileCells(Object profiles) {
        if (!(profiles instanceof List<?> list) || list.isEmpty()) return "-";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(" | ");
            if (!(list.get(i) instanceof Map<?, ?> row)) continue;
            out.append(row.get("profile"));
            out.append(" vendor=").append(row.get("vendor"));
            out.append(" model=").append(row.get("model"));
            out.append(" effort=").append(row.get("effort"));
            out.append(" runner=").append(row.get("runner"));
            out.append(" read_only=").append(row.get("read_only"));
            out.append(" verified=").append(row.get("verified"));
        }
        return out.toString();
    }

    private static String pad(String value, int width) {
        if (value.length() >= width) return value + " ";
        return value + " ".repeat(width - value.length());
    }

    // ---------------------------------------------------------------- set

    private Outcome set(Path home, String[] args, int from) throws IOException {
        Path policyFile = home.resolve("policy.yaml");
        String role = null;
        String profilesRaw = null;
        String strategy = null;
        boolean allowMissing = false;
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--profiles" -> {
                    if (i + 1 >= args.length) {
                        return fail("usage", "roster set --profiles needs a value", policyFile, null, null);
                    }
                    profilesRaw = args[++i];
                }
                case "--strategy" -> {
                    if (i + 1 >= args.length) {
                        return fail("usage", "roster set --strategy needs a value", policyFile, null, null);
                    }
                    strategy = args[++i];
                }
                case "--allow-missing" -> allowMissing = true;
                default -> {
                    if (arg.startsWith("-")) {
                        return fail("usage", "roster set: unknown flag '" + arg + "'", policyFile, null, null);
                    }
                    if (role != null) {
                        return fail("usage", "roster set: unexpected argument '" + arg + "'", policyFile, null, null);
                    }
                    role = arg;
                }
            }
        }
        if (role == null || profilesRaw == null) {
            return fail("usage", "roster set <role> --profiles a,b[,c] [--strategy first|rotate]",
                    policyFile, null, null);
        }
        if (!Profile.ROLES.contains(role)) {
            return fail("role_unknown", "role '" + role + "' is not a known role; expected one of "
                    + Profile.ROLES, policyFile, null, null);
        }
        if (strategy != null && !Policy.STRATEGIES.contains(strategy)) {
            return fail("strategy_unknown", "strategy must be one of " + Policy.STRATEGIES + ", got "
                    + strategy, policyFile, null, null);
        }
        List<String> names = splitNames(profilesRaw);
        if (names.isEmpty()) {
            return fail("usage", "roster set --profiles must list at least one profile",
                    policyFile, null, null);
        }

        for (String name : names) {
            Path profileFile = home.resolve("profiles").resolve(name + ".yaml");
            if (!Files.isRegularFile(profileFile)) {
                if (allowMissing) continue;
                return fail("profile_not_found", "profile '" + name + "' does not exist under profiles/",
                        policyFile, null, null);
            }
            Profile profile;
            try {
                profile = Profile.parse(Files.readString(profileFile, StandardCharsets.UTF_8),
                        profileFile.toString());
            } catch (RuntimeException unreadable) {
                return fail("profile_unreadable", String.valueOf(unreadable.getMessage()),
                        profileFile, null, null);
            }
            if (!role.equals(profile.role())) {
                return fail("profile_role_mismatch", "profile '" + name + "' has role '"
                        + profile.role() + "', not '" + role + "'", policyFile, null, null);
            }
        }

        if (!Files.isRegularFile(policyFile)) {
            return fail("policy_not_found", "no policy.yaml under " + home, policyFile, null, null);
        }

        Document document = Document.read(policyFile);
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        String editError = applySet(document.lines, role, names, strategy, before, after);
        if (editError != null) {
            return fail("policy_line_not_found", editError, policyFile, null, null);
        }
        return rewriteAndReparse(policyFile, document.render(), "policy", "roster_set",
                before, after, Map.of());
    }

    /**
     * @return an error message, or null on success
     */
    private static String applySet(List<String> lines, String role, List<String> names,
                                   String strategy, List<String> before, List<String> after) {
        int rolesLine = findKey(lines, 0, lines.size(), "roles", 0, Integer.MAX_VALUE);
        if (rolesLine < 0) return "policy_line_not_found: no roles: block";
        int rolesIndent = indent(lines.get(rolesLine));
        int rolesEnd = blockEnd(lines, rolesLine, rolesIndent);

        int roleLine = findKey(lines, rolesLine + 1, rolesEnd, role, rolesIndent + 1, Integer.MAX_VALUE);
        if (roleLine < 0) {
            int childIndent = existingChildIndent(lines, rolesLine, rolesEnd, rolesIndent + 2);
            int roleIndent = Math.max(rolesIndent + 2, childIndent - 2);
            if (roleIndent < rolesIndent + 2) roleIndent = rolesIndent + 2;
            String pad = indentOf(roleIndent);
            String nested = indentOf(roleIndent + 2);
            boolean independent = "reviewer".equals(role) || "visual_qa".equals(role);
            List<String> block = new ArrayList<>();
            block.add(pad + role + ":");
            block.add(nested + "profiles: " + flowList(names));
            block.add(nested + "strategy: " + (strategy == null ? "first" : strategy));
            block.add(nested + "require_independent_vendor: " + independent);
            int insertAt = lastNonBlankBefore(lines, rolesEnd, rolesLine);
            lines.addAll(insertAt, block);
            before.addAll(List.of());
            after.addAll(block);
            return null;
        }

        int roleIndent = indent(lines.get(roleLine));
        int roleEnd = blockEnd(lines, roleLine, roleIndent);
        int profilesLine = findKey(lines, roleLine + 1, roleEnd, "profiles", roleIndent + 1, Integer.MAX_VALUE);
        if (profilesLine < 0) return "policy_line_not_found: no profiles: line under roles." + role;
        int profilesEnd = profilesSpanEnd(lines, profilesLine, roleEnd);
        List<String> replaced = new ArrayList<>(lines.subList(profilesLine, profilesEnd));
        before.addAll(replaced);
        String profilesIndent = indentOf(indent(lines.get(profilesLine)));
        String newProfiles = profilesIndent + "profiles: " + flowList(names);
        replaceRange(lines, profilesLine, profilesEnd, List.of(newProfiles));
        after.add(newProfiles);
        int shift = 1 - replaced.size();
        roleEnd += shift;

        if (strategy != null) {
            int strategyLine = findKey(lines, roleLine + 1, roleEnd, "strategy", roleIndent + 1, Integer.MAX_VALUE);
            String newStrategy = profilesIndent + "strategy: " + strategy;
            if (strategyLine < 0) {
                lines.add(profilesLine + 1, newStrategy);
                after.add(newStrategy);
            } else {
                before.add(lines.get(strategyLine));
                lines.set(strategyLine, newStrategy);
                after.add(newStrategy);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- model

    private Outcome model(Path home, String[] args, int from) throws IOException {
        Path profilesDir = home.resolve("profiles");
        String name = null;
        String model = null;
        String effort = null;
        boolean keepVerified = false;
        for (int i = from; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--model" -> {
                    if (i + 1 >= args.length) {
                        return fail("usage", "roster model --model needs a value", profilesDir, null, null);
                    }
                    model = args[++i];
                }
                case "--effort" -> {
                    if (i + 1 >= args.length) {
                        return fail("usage", "roster model --effort needs a value", profilesDir, null, null);
                    }
                    effort = args[++i];
                }
                case "--keep-verified" -> keepVerified = true;
                default -> {
                    if (arg.startsWith("-")) {
                        return fail("usage", "roster model: unknown flag '" + arg + "'",
                                profilesDir, null, null);
                    }
                    if (name != null) {
                        return fail("usage", "roster model: unexpected argument '" + arg + "'",
                                profilesDir, null, null);
                    }
                    name = arg;
                }
            }
        }
        if (name == null || model == null) {
            return fail("usage", "roster model <profile> --model X [--effort Y] [--keep-verified]",
                    profilesDir, null, null);
        }
        Path file = profilesDir.resolve(name + ".yaml");
        if (!Files.isRegularFile(file)) {
            return fail("profile_not_found", "profile '" + name + "' does not exist under profiles/",
                    file, null, null);
        }
        Profile profile;
        try {
            profile = Profile.parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (RuntimeException unreadable) {
            return fail("profile_unreadable", String.valueOf(unreadable.getMessage()), file, null, null);
        }
        if (effort != null) {
            Outcome refused = refuseEffort(effort, model, profile, file);
            if (refused != null) return refused;
        }
        Outcome unforwarded = refuseUnforwardedModel(profile, file);
        if (unforwarded != null) return unforwarded;

        Document document = Document.read(file);
        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        String editError = applyModel(document.lines, model, effort, keepVerified, before, after);
        if (editError != null) {
            return fail("policy_line_not_found", editError, file, null, null);
        }
        // A model the args spell out literally is the same label with extra steps: the line
        // above changes and the vendor is still asked for the old one. Every hand-written
        // profile on the maintainer's machine was like that, so `roster model` was a rename.
        if ("direct".equals(profile.runner()) && profile.model() != null) {
            forwardModel(document.lines, profile.model(), before, after);
            String unreached = unreachedModel(document.render(), file, profile.model());
            if (unreached != null) return fail("model_not_forwarded", unreached, file, null, null);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("verify_with", "warden profiles --verify " + name);
        extra.put("profile", name);
        String probeWarning = probeWarning(document.lines, profile.verificationProbe() != null);
        if (probeWarning != null) extra.put("probe_warning", probeWarning);
        return rewriteAndReparse(file, document.render(), "profile", "roster_model",
                before, after, extra);
    }

    /**
     * A direct profile whose args pass no model at all keeps the vendor's default whatever
     * `model:` says, so rewriting that line would report a switch that never happens. Same
     * rule as effort: the file is left alone and the operator is told which line to add.
     */
    private static Outcome refuseUnforwardedModel(Profile profile, Path file) {
        if (!"direct".equals(profile.runner())) return null;
        boolean placeholder = profile.args().stream().anyMatch(a -> a.contains("{{model}}"));
        boolean literal = profile.model() != null && profile.args().contains(profile.model());
        if (placeholder || literal) return null;
        return fail("model_not_forwarded", "profile '" + profile.name() + "' passes no model to "
                + "the vendor: its args carry neither {{model}} nor the current model"
                + (profile.model() == null ? "" : " '" + profile.model() + "'")
                + ", so a new name would only be a label and the vendor would keep its default. "
                + "Add the vendor's model flag followed by \"{{model}}\" to args, then run this again",
                file, null, null);
    }

    /**
     * Replace the old model where the profile spells it literally — an args item, and the
     * value after a model flag in the probe — with {@code {{model}}}, so the name on the
     * {@code model:} line is the one the vendor and the probe are given.
     */
    private static void forwardModel(List<String> lines, String oldModel, List<String> before,
                                     List<String> after) {
        // Top-level keys sit at the document's own indent, which the reader accepts at any
        // width; assuming column 0 skipped a profile indented as a whole.
        int base = documentIndent(lines);
        String old = java.util.regex.Pattern.quote(oldModel);
        int argsLine = findKey(lines, 0, lines.size(), "args", base, base);
        if (argsLine >= 0) {
            String header = lines.get(argsLine);
            if (stripComment(header).contains("[")) {
                // Any run of spaces between items; a fixed-width lookbehind missed nine.
                replaceLine(lines, argsLine, header.replaceAll("(?<=[\\[,])(\\s*)([\"']?)" + old
                        + "\\2(\\s*)(?=[,\\]])", "$1\"{{model}}\"$3"), before, after);
            } else {
                int end = blockEnd(lines, argsLine, indent(header));
                for (int i = argsLine + 1; i < end; i++) {
                    String line = lines.get(i);
                    // The scalar alone is replaced, so a comment after it stays where it was.
                    replaceLine(lines, i, line.replaceFirst("^(\\s*-\\s*)([\"']?)" + old
                            + "\\2(?=\\s*(#.*)?$)", "$1\"{{model}}\""), before, after);
                }
            }
        }
        int verification = findKey(lines, 0, lines.size(), "verification", base, base);
        if (verification < 0) return;
        int end = blockEnd(lines, verification, base);
        int probe = findKey(lines, verification + 1, end, "probe", base + 1, Integer.MAX_VALUE);
        if (probe < 0) return;
        String line = lines.get(probe);
        replaceLine(lines, probe, line.replaceAll("(--model[ =]|-m )" + old + "(?=[\\s'\"]|$)",
                "$1{{model}}"), before, after);
    }

    private static void replaceLine(List<String> lines, int index, String rewritten,
                                    List<String> before, List<String> after) {
        String line = lines.get(index);
        if (rewritten.equals(line)) return;
        before.add(line);
        lines.set(index, rewritten);
        after.add(rewritten);
    }

    /** The indent of the first key line, which is where the reader puts the document's root. */
    private static int documentIndent(List<String> lines) {
        for (String line : lines) {
            if (isBlankOrComment(line) || line.strip().equals("---")) continue;
            return indent(line);
        }
        return 0;
    }

    /**
     * Why the rewritten profile would still ask the vendor for the old model, or null when it
     * would not. The text edit follows the file's spelling and the reader follows YAML, and the
     * two can disagree (an escaped character, a layout the edit does not recognise). The
     * reader has the last word: a profile whose parsed args still name the old model, or carry
     * no {{model}}, is not written, because reporting a switch the vendor never sees is the
     * defect this command exists to prevent.
     */
    private static String unreachedModel(String rewritten, Path file, String oldModel) {
        Profile parsed;
        try {
            parsed = Profile.parse(rewritten, file.toString());
        } catch (RuntimeException unreadable) {
            return null; // rewriteAndReparse reports it and restores the original bytes
        }
        boolean placeholder = parsed.args().stream().anyMatch(a -> a.contains("{{model}}"));
        if (placeholder && !parsed.args().contains(oldModel)) return null;
        return "profile '" + parsed.name() + "' spells the model '" + oldModel + "' in args in "
                + "a way roster could not rewrite, so the vendor would still be asked for it; "
                + "nothing was written. Replace that args item with \"{{model}}\" by hand, then run "
                + "this again";
    }

    /**
     * Said, not refused: a probe is free text, and one that asks the vendor for its default
     * may be deliberate. But `profiles --verify` would then stamp the new model on the
     * strength of a call to another one.
     */
    private static String probeWarning(List<String> lines, boolean hasProbe) {
        if (!hasProbe) {
            return "the profile has no verification.probe, so `warden profiles --verify` has "
                    + "nothing to run; write one that passes \"{{model}}\" before relying on it";
        }
        int base = documentIndent(lines);
        int verification = findKey(lines, 0, lines.size(), "verification", base, base);
        int end = verification < 0 ? lines.size() : blockEnd(lines, verification, base);
        int probe = verification < 0 ? -1
                : findKey(lines, verification + 1, end, "probe", base + 1, Integer.MAX_VALUE);
        if (probe >= 0 && lines.get(probe).contains("{{model}}")) return null;
        return "verification.probe does not pass {{model}}, so `warden profiles --verify` would "
                + "call the vendor's default model and stamp this one";
    }

    /**
     * Same wording {@link Profile#parse} uses. Refusing here keeps the file untouched rather
     * than writing a document the parser would bounce.
     */
    private static Outcome refuseEffort(String effort, String model, Profile profile, Path file) {
        if (!effort.matches("[a-z][a-z0-9_-]*")) {
            return fail("effort_invalid",
                    "effort must be a provider effort level, not a command or blank value",
                    file, null, null);
        }
        if ("local".equals(profile.runner())) {
            return fail("effort_invalid",
                    "runner: local does not support effort; it cannot silently drop it",
                    file, null, null);
        }
        String effectiveModel = model != null ? model : profile.model();
        if ("orca".equals(profile.runner()) && (effectiveModel == null || effectiveModel.isBlank())) {
            return fail("effort_invalid", "runner: orca effort requires an explicit model",
                    file, null, null);
        }
        if ("direct".equals(profile.runner())
                && profile.args().stream().noneMatch(a -> a.contains("{{effort}}"))) {
            return fail("effort_invalid",
                    "runner: direct effort requires {{effort}} in args; replace the literal effort value",
                    file, null, null);
        }
        return null;
    }

    private static String applyModel(List<String> lines, String model, String effort,
                                     boolean keepVerified, List<String> before, List<String> after) {
        int modelLine = findKey(lines, 0, lines.size(), "model", 0, Integer.MAX_VALUE);
        String newModel = (modelLine >= 0 ? indentOf(indent(lines.get(modelLine))) : "")
                + "model: " + yamlScalar(model);
        if (modelLine < 0) {
            int vendorLine = findKey(lines, 0, lines.size(), "vendor", 0, Integer.MAX_VALUE);
            int insertAt = vendorLine >= 0 ? vendorLine + 1 : 1;
            String pad = vendorLine >= 0 ? indentOf(indent(lines.get(vendorLine))) : "";
            newModel = pad + "model: " + yamlScalar(model);
            lines.add(insertAt, newModel);
            after.add(newModel);
            modelLine = insertAt;
        } else {
            before.add(lines.get(modelLine));
            lines.set(modelLine, newModel);
            after.add(newModel);
        }

        if (effort != null) {
            int effortLine = findKey(lines, 0, lines.size(), "effort", 0, Integer.MAX_VALUE);
            String pad = indentOf(indent(lines.get(modelLine)));
            String newEffort = pad + "effort: " + yamlScalar(effort);
            if (effortLine < 0) {
                lines.add(modelLine + 1, newEffort);
                after.add(newEffort);
            } else {
                before.add(lines.get(effortLine));
                lines.set(effortLine, newEffort);
                after.add(newEffort);
            }
        }

        if (!keepVerified) {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (!isKey(lines.get(i), "verified_on")) continue;
                before.add(lines.get(i));
                lines.remove(i);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- file + line helpers

    static Path copyBackup(Path file) throws IOException {
        String stamp = LocalDateTime.now().format(BACKUP_STAMP);
        String base = file.getFileName().toString() + ".before-roster-" + stamp;
        Path dir = file.getParent() == null ? Path.of(".") : file.getParent();
        Path candidate = dir.resolve(base);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = dir.resolve(base + "-" + suffix);
            suffix++;
        }
        Files.copy(file, candidate);
        return candidate;
    }

    private static Map<String, Object> changed(List<String> before, List<String> after) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("before", List.copyOf(before));
        value.put("after", List.copyOf(after));
        return value;
    }

    private static Outcome fail(String code, String message, Path file, Path backup,
                                Map<String, Object> changed) {
        return fail(code, message, file, backup, changed, Map.of());
    }

    private static Outcome fail(String code, String message, Path file, Path backup,
                                Map<String, Object> changed, Map<String, Object> extra) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("ok", false);
        report.put("code", code);
        report.put("message", message);
        report.put("file", file == null ? null : file.toString());
        report.put("backup", backup == null ? null : backup.toString());
        report.put("changed", changed);
        report.putAll(extra);
        return new Outcome(false, report, null);
    }

    private static List<String> splitNames(String raw) {
        List<String> names = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.strip();
            if (name.isEmpty()) continue;
            names.add(name);
        }
        return names;
    }

    private static String flowList(List<String> names) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) out.append(", ");
            out.append(yamlScalar(names.get(i)));
        }
        return out.append(']').toString();
    }

    private static String yamlScalar(String value) {
        if (value.matches("[A-Za-z0-9_./-]+")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static int findKey(List<String> lines, int from, int to, String key, int minIndent, int maxIndent) {
        for (int i = from; i < to && i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) continue;
            int ind = indent(line);
            if (ind < minIndent || ind > maxIndent) continue;
            if (isKey(line, key)) return i;
        }
        return -1;
    }

    private static int blockEnd(List<String> lines, int keyLine, int keyIndent) {
        for (int i = keyLine + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            if (indent(line) <= keyIndent) return i;
        }
        return lines.size();
    }

    private static int profilesSpanEnd(List<String> lines, int profilesLine, int roleEnd) {
        String stripped = stripComment(lines.get(profilesLine)).strip();
        if (!stripped.equals("profiles:")) return profilesLine + 1;
        int pIndent = indent(lines.get(profilesLine));
        int end = profilesLine + 1;
        while (end < roleEnd && end < lines.size()) {
            String line = lines.get(end);
            if (line.isBlank()) {
                end++;
                continue;
            }
            int ind = indent(line);
            if (ind <= pIndent) break;
            String content = line.stripLeading();
            if (content.startsWith("- ") || content.equals("-") || content.startsWith("#")) {
                end++;
                continue;
            }
            break;
        }
        while (end > profilesLine + 1 && lines.get(end - 1).isBlank()) end--;
        return end;
    }

    private static int lastNonBlankBefore(List<String> lines, int end, int after) {
        for (int i = end - 1; i > after; i--) {
            if (!lines.get(i).isBlank()) return i + 1;
        }
        return end;
    }

    private static int existingChildIndent(List<String> lines, int parentLine, int parentEnd, int fallback) {
        if (parentLine < 0) return fallback;
        int parentIndent = indent(lines.get(parentLine));
        for (int i = parentLine + 1; i < parentEnd && i < lines.size(); i++) {
            String line = lines.get(i);
            if (isBlankOrComment(line)) continue;
            int ind = indent(line);
            if (ind > parentIndent) return ind;
        }
        return fallback;
    }

    private static void replaceRange(List<String> lines, int from, int to, List<String> replacement) {
        lines.subList(from, to).clear();
        lines.addAll(from, replacement);
    }

    private static boolean isBlankOrComment(String line) {
        String trimmed = line.strip();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static boolean isKey(String line, String key) {
        if (isBlankOrComment(line)) return false;
        String content = stripComment(line).stripLeading();
        if (!content.startsWith(key)) return false;
        int n = key.length();
        return n < content.length() && content.charAt(n) == ':';
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String indentOf(int width) {
        return " ".repeat(Math.max(0, width));
    }

    /** Drop a `#` comment that sits outside quotes, matching the YAML reader. */
    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && inDouble) { i++; continue; }
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || line.charAt(i - 1) == ' ')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** Split on the file's own newline so a rewrite can put the same one back. */
    static final class Document {
        final String newline;
        final List<String> lines;

        Document(String newline, List<String> lines) {
            this.newline = newline;
            this.lines = lines;
        }

        static Document read(Path file) throws IOException {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            String[] parts = text.split(java.util.regex.Pattern.quote(newline), -1);
            List<String> lines = new ArrayList<>(parts.length);
            for (String part : parts) lines.add(part);
            return new Document(newline, lines);
        }

        String render() {
            return String.join(newline, lines);
        }
    }
}
