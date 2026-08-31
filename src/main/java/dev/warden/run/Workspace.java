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

            @Override public void watch(java.nio.file.Path narration, String runId) {
                try { board.watch(narration, runId); } catch (RuntimeException | Error notOurProblem) { }
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
