package dev.warden.run;

/**
 * The run, as seen from the board rather than the terminal.
 *
 * {@link Progress} answers "what is happening" for a person watching one run in one shell.
 * This answers "which of my worktrees needs me" for a person looking at all of them at once —
 * including from a phone, which is the case that made it worth building. A loop that takes
 * twenty minutes and ends by waiting for a human is exactly the shape of work you want to walk
 * away from, and walking away is only useful if the wait is visible from wherever you went.
 *
 * Like the terminal narration, nothing sent here is evidence: every note restates something the
 * ledger already holds, and a run that reported nothing to the board is exactly as auditable as
 * one that reported everything. That is why every implementation is free to fail silently — a
 * board that cannot be updated must never take a run down with it — and why this is a separate
 * interface rather than another {@link Progress} sink. The two carry different content on
 * purpose: the terminal gets every line, the board gets the few a person would act on.
 */
public interface Workspace {

    /**
     * Replace the one-line summary shown on the workspace card.
     *
     * Replace, not append: a card is a status, not a log. The log is the terminal, and the
     * record is the ledger.
     */
    void note(String text);

    /** Move the card to the column that matches what the run now needs. */
    void state(State state);

    /**
     * The stage that is running now, and how long it has been running.
     *
     * {@link #note} is written at the edges of a stage; this is written while one is in the
     * middle. The difference matters because the middle is where the time goes: a role can
     * hold the loop for twenty minutes, and for those twenty minutes a card that says
     * "implement · running" is telling the truth and answering nothing. The operator's
     * question is which role, and since when.
     *
     * Called about once a minute from a separate thread, so an implementation that touches
     * shared state has to say so. Best-effort like the rest of this interface: a beat that
     * could not be delivered is the beat the next one supersedes.
     *
     * @param who   the live name of whoever is holding the loop: the role, plus the
     *              profile in brackets when one was resolved, or the kind of stage when
     *              no vendor is filling it
     * @param millis how long this stage has been running
     */
    default void working(String who, long millis) { }

    /**
     * Open a live view of the run on this board, following the narration file.
     *
     * A file rather than a stream, and that is not an implementation detail. A board can be
     * asked to *show* something; it cannot be handed a pipe. Orca's terminal API sends input
     * to a shell — there is no way to push a running process's stdout into it — so the only
     * shape that keeps Warden the caller is: Warden writes its account somewhere, and asks
     * the board to open a window that follows it.
     *
     * That constraint is what makes this the right design rather than a workaround. The run
     * is not hosted by the board: closing the window it opened does not touch the loop, and
     * the loop can be started from a shell, a script or a scheduler and still be watched.
     *
     * Best-effort, like everything else here. A view that could not be opened is a view the
     * operator does not get, not a run that fails.
     */
    default void watch(java.nio.file.Path narration, String runId) { }

    /**
     * Show a finished file on this board, once, and leave it on screen.
     *
     * {@link #watch} follows something that is still being written; this is for something that
     * is already true and has to be read before the next thing happens. The compiled contract
     * is the case that asked for it. A planner turns one sentence into the document the whole
     * run is then judged against, and until this existed the only trace of that on the board
     * was a path in a terminal the operator may not have been looking at - which makes the
     * most consequential artifact of a run the least visible one.
     *
     * Best-effort like the rest of this interface: a window that did not open is a window the
     * operator does not get, not a run that fails.
     */
    default void show(java.nio.file.Path file, String runId, String title) { }

    /**
     * Put a file the run produced in front of the operator, in whatever the board views it
     * with.
     *
     * {@link #show} prints a text file into a terminal, which is the right window for a
     * compiled contract and the wrong one for a PNG. Visual QA is the case that asked for
     * this: the whole point of the stage is that somebody looked at the screen, and until
     * this existed the only trace of those pixels on the board was a sha256 in the ledger.
     * A person deciding `accept` or `reject` from a phone should be deciding it while
     * looking at the picture the verdict is about.
     *
     * Best-effort like the rest of this interface, and deliberately unbounded in what a board
     * may do with it: opening an editor, a preview pane or nothing at all are all correct.
     */
    default void reveal(java.nio.file.Path file) { }

    /**
     * Name the stage that is about to spend time, on the board's own task list.
     *
     * Direct runners never become Agent Dashboard rows — that needs {@code runner: orca}.
     * The card and the tab still have to say which Warden stage is working, or a phone
     * sees only "in progress". Best-effort, like the rest of this interface.
     */
    default void stage(String label) { }

    /**
     * The stage named by the last {@link #stage} call has finished, with this outcome.
     *
     * A row that is opened and never closed says "in progress" for as long as anybody looks,
     * which on a board that outlives the run is forever.
     */
    default void stageEnded(boolean ok, String summary) { }

    /**
     * Take over the surfaces an earlier process opened for {@code runId} — its tab, its Run —
     * so this board can change what they say. The decision is usually recorded by a process
     * other than the one that asked it.
     */
    default void adopt(String runId) { }

    /**
     * The run's question has an answer. The surfaces that asked stop asking, and nothing
     * here claims the run that answer leads to has started: that run says so itself when it
     * does.
     */
    default void answered(String decision) { }

    /**
     * The three states a Warden run can put a workspace in.
     *
     * Deliberately not one per stage. The board answers one question — is this waiting for me
     * — and a column per stage would turn that answer back into something you have to read.
     */
    enum State {
        /** Warden is working. Nothing is expected of anyone. */
        RUNNING,
        /** The loop stopped at a human. This is the whole point of the board. */
        WAITING_FOR_HUMAN,
        /** A decision was recorded and the run is closed. */
        SETTLED
    }

    /** No board. The default everywhere, and what every caller gets when Orca is not running. */
    Workspace NONE = new Workspace() {
        @Override public void note(String text) { }
        @Override public void state(State state) { }
    };

    /**
     * The same board, unable to fail the run behind it.
     *
     * Applied at the seam rather than left to each implementation, because "a board update
     * must never abort a paid twenty-minute loop" is an invariant and not a convention every
     * future implementation can be trusted to remember. A failed update is not retried and not
     * reported: the next checkpoint supersedes it, and the ledger — which is the record — never
     * went through here at all.
     *
     * Every method is forwarded, the defaults included. A default left out of this list is not
     * unguarded, it is gone: the wrapper inherits the interface's empty body, so the call
     * succeeds and nothing reaches the board.
     */
    static Workspace guarded(Workspace board) {
        if (board == NONE) return NONE;
        return new Workspace() {
            @Override public void note(String text) {
                try { board.note(text); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void state(State state) {
                try { board.state(state); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void working(String who, long millis) {
                try { board.working(who, millis); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void watch(java.nio.file.Path narration, String runId) {
                try { board.watch(narration, runId); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void show(java.nio.file.Path file, String runId, String title) {
                try { board.show(file, runId, title); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void reveal(java.nio.file.Path file) {
                try { board.reveal(file); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void stage(String label) {
                try { board.stage(label); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void stageEnded(boolean ok, String summary) {
                try { board.stageEnded(ok, summary); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void adopt(String runId) {
                try { board.adopt(runId); } catch (RuntimeException | Error notOurProblem) { }
            }

            @Override public void answered(String decision) {
                try { board.answered(decision); } catch (RuntimeException | Error notOurProblem) { }
            }
        };
    }

    /**
     * A board resolved once the run knows which directory it is in.
     *
     * `warden do` does not know that until it has asked for isolation, and the card it should
     * write to is the new worktree's, not the one the operator typed the command in. So the
     * board is supplied as something to resolve later rather than as an object to hold.
     */
    @FunctionalInterface
    interface Source {
        Workspace at(java.nio.file.Path worktree);

        Source NONE = worktree -> Workspace.NONE;
    }
}
