package dev.warden.execution.orca;

import dev.warden.process.ProcessRunner;
import dev.warden.run.Progress;
import dev.warden.run.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Warden's run, written onto the Orca workspace card that holds it.
 *
 * Orca already shows every worktree as a card with a short comment and a status column, and
 * mirrors both to its mobile app. Warden was silent towards all of it, so an operator with
 * seventeen worktrees saw seventeen identical cards — including the four that were waiting on
 * a human decision. Two fields per checkpoint fix that, and they are fields Orca already had.
 *
 * <p><b>Never fails a run.</b> Every call swallows everything. The board is a convenience, and
 * a convenience that can abort a paid twenty-minute loop is a liability. The one thing this
 * class will not do is retry: a checkpoint that missed is superseded by the next one within a
 * stage, and the final one is written twice — once by the loop, once by {@code warden approve}.
 */
public final class OrcaWorkspace implements Workspace {

    /**
     * Short. An Orca card gives a comment one line, and a note that overflows it is a note
     * whose ending — which is where the cost and the verdict live — is the part that is cut.
     */
    private static final int MAX_NOTE = 140;

    /** Bounded hard: a board update is never worth making the operator wait for the loop. */
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final OrcaClient orca;
    private final Path worktree;
    private final String selector;

    /** The live view this run opened, if it opened one. Null until {@link #watch} succeeds. */
    private String terminal;
    private String runId;

    /**
     * The last line written to the card, so a beat can re-say it with the clock attached.
     *
     * Volatile because {@link #note} is called from the loop and {@link #working} from the
     * heartbeat, a minute apart and never in step.
     */
    private volatile String lastNote = "";

    /**
     * Set once the run has told the board it stopped, after which no beat may say otherwise.
     *
     * A heartbeat is closed before its stage returns, but closing it only stops the next beat;
     * one already inside a fifteen-second Orca call can still land afterwards. Without this
     * flag the last thing an operator would read on a run that ended waiting for them is
     * `reviewer 5m00s`, written a moment after the tab had correctly said NEEDS YOU. The
     * ordering is the board's to defend, not the caller's to get right every time.
     */
    private volatile boolean stopped;

    private OrcaWorkspace(OrcaClient orca, Path worktree, String selector) {
        this.orca = orca;
        this.worktree = worktree;
        this.selector = selector;
    }

    /**
     * A board for this directory, or {@link Workspace#NONE}.
     *
     * The probe is the point: Warden runs in plain checkouts, in `git worktree` directories and
     * under `--in-place` just as often as it runs under Orca, and a tool that printed a warning
     * about a missing desktop app on every one of those would be worse than one that says
     * nothing. If Orca does not claim this directory, there is no board, and that is not an
     * error state.
     */
    public static Workspace attach(ProcessRunner processes, Path worktree) {
        OrcaClient orca = new OrcaClient(processes);
        try {
            if (!Boolean.TRUE.equals(orca.status(worktree).get("available"))) return Workspace.NONE;
            OrcaClient.Rpc current = orca.invoke(worktree, Duration.ofSeconds(15),
                    List.of("worktree", "current"));
            if (!current.ok()) return Workspace.NONE;
            String selector = OrcaSettlement.worktreeSelector(current.envelope());
            if (selector == null) return Workspace.NONE;
            return new OrcaWorkspace(orca, worktree, selector);
        } catch (Exception notOurProblem) {
            return Workspace.NONE;
        }
    }

    @Override
    public void note(String text) {
        String flat = clip(text);
        lastNote = flat;
        set(List.of("--comment", flat));
    }

    @Override
    public void state(State state) {
        if (state != State.RUNNING) stopped = true;
        set(List.of("--workspace-status", column(state)));
        retitle(state);
    }

    /**
     * Once a minute, on both surfaces Orca gives a worktree: the tab and the card.
     *
     * The tab is what an operator with three runs going reads without clicking anything, and
     * `warden lh-eyes-2` on all three of them says nothing at all; `warden lh-eyes-2 ·
     * implementer 12m00s` says which one to leave alone. The card is the same sentence for
     * the phone, which cannot see a tab strip — the note the stage already wrote, with the
     * clock appended, so the position and the stage name survive.
     *
     * Both are best-effort and both are skipped when their surface is not there: a run without
     * `--watch` has no tab to name, and one that never wrote a card has nothing to extend.
     */
    @Override
    public void working(String who, long millis) {
        if (stopped) return;
        if (runId != null && terminal != null) {
            rename("warden " + runId + " · " + who + " " + Progress.elapsed(millis));
        }
        String note = lastNote;
        if (!note.isEmpty()) {
            set(List.of("--comment", clip(note + " · " + Progress.elapsed(millis))));
        }
    }

    /**
     * Say on the tab what the run now wants.
     *
     * A tab is the one surface an operator cannot fail to notice: it is in front of them, it
     * has a name, and it survives the run that made it. The card is a better summary and it is
     * only better if you can find it — which on this Orca build the operator could not, while
     * they could list their tabs from memory. So the tab says it too, and at the one moment it
     * matters most it says it in words that are hard to read past.
     */
    private void retitle(State state) {
        if (terminal == null || runId == null) return;
        String title = switch (state) {
            case RUNNING -> "warden " + runId;
            case WAITING_FOR_HUMAN -> "warden " + runId + " - NEEDS YOU";
            case SETTLED -> "warden " + runId + " - done";
        };
        rename(title);
    }

    /** One name onto one tab. Never fails a run; a stale tab name is not a stale run. */
    private void rename(String title) {
        if (terminal == null) return;
        try {
            orca.invoke(worktree, TIMEOUT, List.of("terminal", "rename",
                    "--terminal", terminal, "--title", title));
        } catch (Exception notOurProblem) {
            // The window is still there with the narration in it; only its name is stale.
        }
    }

    /**
     * Orca's own status ids. Warden's three states are named for what they mean to a person;
     * these are what Orca's board calls the columns, and the mapping is the only place the two
     * vocabularies have to meet.
     */
    private static String column(State state) {
        return switch (state) {
            case RUNNING -> "in-progress";
            case WAITING_FOR_HUMAN -> "in-review";
            case SETTLED -> "completed";
        };
    }

    /**
     * A terminal in this worktree that follows the run's narration.
     *
     * Warden calls Orca, the way it does for isolation and for the card. The alternative —
     * starting `warden run` inside an Orca terminal — reads the same from the outside and is
     * a different thing: it makes Orca the host of the run, so the loop dies with the tab and
     * a run launched from anywhere else is invisible.
     *
     * The file is created before the terminal opens. `Get-Content -Wait` on a path that does
     * not exist yet is an error, not a wait, and the first thing the operator would see in the
     * window Warden just opened for them would be a red line about a missing file.
     */
    @Override
    public void watch(Path narration, String runId) {
        try {
            if (!Files.isRegularFile(narration)) {
                Files.createDirectories(narration.getParent());
                Files.writeString(narration, "");
            }
            OrcaClient.Rpc opened = orca.invoke(worktree, TIMEOUT, List.of(
                    "terminal", "create",
                    "--worktree", selector,
                    "--title", "warden " + runId,
                    "--command", follow(narration, runId)));
            if (opened.ok()) {
                this.runId = runId;
                this.terminal = handleOf(opened.result());
            }
        } catch (Exception notOurProblem) {
            // A window that did not open is not a run that failed.
        }
    }

    /**
     * A command line with no quotes in it, and a script beside the narration that has them.
     *
     * The first version put the whole thing on the command line —
     * {@code powershell -NoExit -Command "Get-Content -LiteralPath '...' -Wait"} — and Orca
     * answered {@code Unknown command: terminal create -LiteralPath '...' -Wait -Tail 200}.
     * The argument had been torn apart at the first double quote, which is precisely the
     * Windows argv defect this codebase already documents and guards against for vendor
     * prompts: the JVM wraps an argument containing a space but does not escape a quote inside
     * it. Writing the guard and then walking into it one class over is worth recording.
     *
     * So nothing quoted goes on the command line. The path lives inside a script file, where
     * quoting is somebody else's problem, and the terminal is pointed at that script by a
     * repository-relative path — no absolute path, no spaces, nothing to escape.
     *
     * `-NoExit` and the trailing `exec` are the other half. A tab that dies the moment the
     * tail is interrupted leaves the operator reading the approve command in a window they
     * then have to close and replace with another one, in the right directory, typed from
     * memory. The command they need is on the screen; the prompt should be underneath it.
     */
    private String follow(Path narration, String runId) throws java.io.IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String name = windows ? "follow.ps1" : "follow.sh";
        Path script = narration.getParent().resolve(name);
        String path = narration.toAbsolutePath().toString();
        if (windows) {
            Files.writeString(script, "Get-Content -LiteralPath '"
                    + path.replace("'", "''") + "' -Wait -Tail 200\r\n");
        } else {
            Files.writeString(script, "#!/bin/sh\ntail -n 200 -f '"
                    + path.replace("'", "'\\''") + "'\nexec ${SHELL:-sh}\n");
        }
        String relative = ".warden/runs/" + runId + "/" + name;
        // `-ExecutionPolicy Bypass` because the default policy refuses to load a .ps1 at all,
        // and the first window this opened said so instead of showing the run. It applies to
        // this one process, and the script is three lines Warden wrote a moment ago into the
        // run's own directory.
        return windows
                ? "powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -NoExit -File " + relative
                : "sh " + relative;
    }

    private void set(List<String> fields) {
        try {
            java.util.List<String> args = new java.util.ArrayList<>(
                    List.of("worktree", "set", "--worktree", selector));
            args.addAll(fields);
            orca.invoke(worktree, TIMEOUT, args);
        } catch (Exception notOurProblem) {
            // Deliberately silent. See the class comment: this channel may not cost a run.
        }
    }

    /** `result.terminal.handle`, or `result.handle` — Orca has answered both shapes. */
    private static String handleOf(Map<String, Object> result) {
        Object nested = result.get("terminal");
        if (nested instanceof Map<?, ?> map && map.get("handle") != null) {
            return String.valueOf(map.get("handle"));
        }
        Object handle = result.get("handle");
        return handle == null ? null : String.valueOf(handle);
    }

    private static String clip(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= MAX_NOTE) return flat;
        return flat.substring(0, MAX_NOTE - 1) + "…";
    }
}
