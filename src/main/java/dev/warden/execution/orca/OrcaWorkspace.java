package dev.warden.execution.orca;

import dev.warden.process.ProcessRunner;
import dev.warden.run.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
        set(List.of("--comment", clip(text)));
    }

    @Override
    public void state(State state) {
        set(List.of("--workspace-status", column(state)));
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
            orca.invoke(worktree, TIMEOUT, List.of(
                    "terminal", "create",
                    "--worktree", selector,
                    "--title", "warden " + runId,
                    "--command", follow(narration)));
        } catch (Exception notOurProblem) {
            // A window that did not open is not a run that failed.
        }
    }

    /** `tail -f` under whichever shell Orca will start this terminal with. */
    private static String follow(Path narration) {
        String path = narration.toAbsolutePath().toString();
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return "powershell -NoLogo -NoProfile -Command \"Get-Content -LiteralPath '"
                    + path.replace("'", "''") + "' -Wait -Tail 200\"";
        }
        return "tail -n 200 -f '" + path.replace("'", "'\\''") + "'";
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

    private static String clip(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() <= MAX_NOTE) return flat;
        return flat.substring(0, MAX_NOTE - 1) + "…";
    }
}
