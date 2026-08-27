package dev.warden.testing;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in vendor CLI, written in Java so the role suite runs identically on Windows and
 * POSIX.
 *
 * The first version used shell scripts, which silently skipped the entire role layer on
 * Windows — the platform this tool is actually operated from. A suite that reports "passed"
 * while covering nothing on the target platform is worse than no suite, so the stand-ins run
 * on the JVM that is already present.
 *
 * Modes mirror the behaviours worth pinning: an implementer that works, a reviewer that
 * answers inside a text envelope, a reviewer that secretly writes, and one that returns an
 * incomplete artifact.
 *
 * The two quota modes are transcribed from a live Codex run whose subscription was spent, not
 * invented: the message lands on stdout inside the structured event stream, the exit code is
 * the same 1 a bad flag produces, and stderr carries only unrelated transport noise. A
 * stand-in that made quota look distinctive would have tested a failure that does not occur.
 */
public final class StubVendor {

    /**
     * `--counter <file> --threshold <n>` makes a stand-in attempt-aware, so a test can force
     * exactly N fix rounds instead of hoping for them. Named flags rather than positions:
     * the profile also appends `--prompt-file <path>`, and a positional reading of that
     * silently turned a flag into a counter file.
     */
    private static String flag(String[] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) return args[index + 1];
        }
        return null;
    }

    private static boolean succeedsNow(String[] args) throws Exception {
        String counterPath = flag(args, "--counter");
        String threshold = flag(args, "--threshold");
        if (counterPath == null || threshold == null) return true;
        Path counter = Path.of(counterPath);
        int seen = Files.isRegularFile(counter)
                ? Integer.parseInt(Files.readString(counter).strip()) : 0;
        seen++;
        Files.writeString(counter, String.valueOf(seen), StandardCharsets.UTF_8);
        return seen >= Integer.parseInt(threshold);
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "review";
        switch (mode) {
            case "impl" -> {
                Path target = Path.of("src", "result.txt");
                Files.createDirectories(target.getParent());
                if (succeedsNow(args)) Files.writeString(target, "ok", StandardCharsets.UTF_8);
                else Files.writeString(target.getParent().resolve("partial.txt"), "not yet",
                        StandardCharsets.UTF_8);
                System.out.println("{\"structuredOutput\":{\"role\":\"implementer\",\"task_id\":\"hello\","
                        + "\"status\":\"completed\",\"summary\":\"created\","
                        + "\"files_changed\":[\"src/result.txt\"]},"
                        + "\"total_cost_usd\":0.012,\"num_turns\":3,"
                        + "\"modelUsage\":{\"stub-impl-model\":{}}}");
            }
            case "review" -> {
                // Answer buried in a text envelope, the shape Grok produces when its
                // structured-output channel does not engage.
                boolean passes = succeedsNow(args);
                String findings = passes ? "[]"
                        : "[{\\\"severity\\\":\\\"P1\\\",\\\"path\\\":\\\"src/result.txt\\\","
                        + "\\\"message\\\":\\\"not good enough\\\","
                        + "\\\"expected\\\":\\\"the final content\\\","
                        + "\\\"actual\\\":\\\"an intermediate one\\\","
                        + "\\\"scenario\\\":\\\"read src/result.txt after the run\\\","
                        + "\\\"confidence\\\":\\\"confirmed\\\"}]";
                System.out.println("chatter the vendor prints first");
                System.out.println("{\"text\":\"{\\\"role\\\":\\\"reviewer\\\",\\\"task_id\\\":\\\"hello\\\","
                        + "\\\"status\\\":\\\"completed\\\",\\\"verdict\\\":\\\""
                        + (passes ? "pass" : "fail") + "\\\","
                        + "\\\"summary\\\":\\\"review\\\",\\\"findings\\\":" + findings + "}\","
                        + "\"total_cost_usd\":0.004,\"num_turns\":2,\"stopReason\":\"end_turn\"}");
            }
            case "sneaky" -> {
                Path target = Path.of("src", "result.txt");
                Files.createDirectories(target.getParent());
                Files.writeString(target, "tampered", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println("{\"structuredOutput\":{\"role\":\"reviewer\",\"task_id\":\"hello\","
                        + "\"status\":\"completed\",\"verdict\":\"pass\",\"summary\":\"lgtm\","
                        + "\"findings\":[]}}");
            }
            case "thin" -> System.out.println(
                    "{\"structuredOutput\":{\"role\":\"reviewer\",\"status\":\"completed\"}}");
            case "bad-schema" -> System.out.println(
                    "{\"structuredOutput\":{\"role\":\"reviewer\",\"task_id\":\"hello\","
                    + "\"status\":\"completed\",\"verdict\":\"lgtm\",\"summary\":\"looks fine\","
                    + "\"findings\":[]}}");
            case "quota", "quota-after-edit" -> {
                if (mode.equals("quota-after-edit")) {
                    Path target = Path.of("src", "result.txt");
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, "half of a change", StandardCharsets.UTF_8);
                }
                String message = "You've hit your usage limit. Upgrade to Pro "
                        + "(https://example.invalid/pro), visit https://example.invalid/usage to "
                        + "purchase more credits or try again at 9:21 PM.";
                System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"stub-thread\"}");
                System.out.println("{\"type\":\"turn.started\"}");
                System.out.println("{\"type\":\"error\",\"message\":\"" + message + "\"}");
                System.out.println("{\"type\":\"turn.failed\",\"error\":{\"message\":\"" + message + "\"}}");
                System.err.println("ERROR transport: worker quit with fatal: Transport channel closed");
                System.exit(1);
            }
            case "stdin" -> {
                // Answers only if the prompt reached it through standard input, so a profile
                // that claims stdin delivery and does not get it fails loudly.
                String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                if (!prompt.contains("Create src/result.txt")) {
                    System.err.println("prompt did not arrive on stdin: " + prompt.length() + " chars");
                    System.exit(3);
                }
                Path target = Path.of("src", "result.txt");
                Files.createDirectories(target.getParent());
                Files.writeString(target, "ok", StandardCharsets.UTF_8);
                System.out.println("{\"structuredOutput\":{\"role\":\"implementer\",\"task_id\":\"hello\","
                        + "\"status\":\"completed\",\"summary\":\"read the prompt from stdin\","
                        + "\"files_changed\":[\"src/result.txt\"]}}");
            }
            case "eyes" -> {
                // A visual reviewer that refuses to pretend. It answers only if real image
                // files were attached, because "the model was told where the screenshots are"
                // and "the model saw the screenshots" are different claims, and only the
                // second one is worth paying for.
                List<String> images = new ArrayList<>();
                for (int index = 0; index + 1 < args.length; index++) {
                    if (args[index].equals("-i") && Files.isRegularFile(Path.of(args[index + 1]))) {
                        images.add(args[index + 1]);
                    }
                }
                if (images.isEmpty()) {
                    System.out.println("{\"structuredOutput\":{\"role\":\"visual_qa\",\"task_id\":\"hello\","
                            + "\"status\":\"aborted\",\"verdict\":\"fail\","
                            + "\"summary\":\"no image reached me\",\"findings\":[]}}");
                    return;
                }
                boolean passes = succeedsNow(args);
                String findings = passes ? "[]"
                        : "[{\"severity\":\"P1\",\"path\":\"src/result.txt\","
                        + "\"viewport\":\"1280x720\",\"message\":\"the label is clipped\","
                        + "\"expected\":\"the whole word is readable\","
                        + "\"actual\":\"it is cut off at the panel edge\","
                        + "\"scenario\":\"open the screenshot at 1280x720\","
                        + "\"confidence\":\"confirmed\"}]";
                System.out.println("{\"structuredOutput\":{\"role\":\"visual_qa\",\"task_id\":\"hello\","
                        + "\"status\":\"completed\",\"verdict\":\"" + (passes ? "pass" : "fail") + "\","
                        + "\"summary\":\"looked at " + images.size() + " image(s)\","
                        + "\"images_seen\":[\"" + String.join("\",\"", images).replace("\\", "/") + "\"],"
                        + "\"findings\":" + findings + "},"
                        + "\"total_cost_usd\":0.003}");
            }
            case "check" -> System.exit(Files.isRegularFile(Path.of("src", "result.txt")) ? 0 : 1);
            default -> {
                System.err.println("unknown stub vendor mode: " + mode);
                System.exit(2);
            }
        }
    }
}
