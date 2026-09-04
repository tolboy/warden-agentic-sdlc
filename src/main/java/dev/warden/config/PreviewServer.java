package dev.warden.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The command that serves a project's own UI, and the address it will answer on.
 *
 * One place, because there were two. The browser stage falls back to this when a task
 * declares no `start`, and the task drafter writes a `start`/`url` pair into the contract it
 * generates — and the drafter's copy knew only about `preview`. A project whose package.json
 * has `dev` and no `preview` was handed a contract whose server could never come up, and the
 * failure it produced said nothing about the real cause.
 *
 * `preview` is preferred over `dev` deliberately: it serves a build, which is what a check is
 * about, and it does not hold a file watcher open across a run. The ports differ because the
 * two tools default differently and a contract that pins the wrong one fails on an empty page.
 */
public record PreviewServer(String command, String url) {
    private static final PreviewServer PREVIEW = new PreviewServer(
            "npm run preview -- --host 127.0.0.1 --port 4173", "http://127.0.0.1:4173/");
    private static final PreviewServer DEV = new PreviewServer(
            "npm run dev -- --host 127.0.0.1 --port 5173", "http://127.0.0.1:5173/");

    /**
     * What would serve this project, or null when nothing here says.
     *
     * A crude substring look rather than a JSON parse, kept as it was: package.json is not
     * Warden's format to interpret, and a false positive costs a command that fails loudly
     * while a parser costs a dependency on someone else's schema.
     */
    public static PreviewServer detect(Path projectRoot) {
        Path pkg = projectRoot.resolve("package.json");
        if (!Files.isRegularFile(pkg)) return null;
        try {
            String text = Files.readString(pkg);
            if (text.contains("\"preview\"")) return PREVIEW;
            if (text.contains("\"dev\"")) return DEV;
        } catch (IOException unreadable) {
            // Unreadable is the same as absent here: neither answers what would serve this.
        }
        return null;
    }
}
