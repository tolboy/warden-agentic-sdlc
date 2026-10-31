package dev.warden.execution.orca;

import dev.warden.execution.ExecutionActivity;
import dev.warden.run.Workspace;

import java.nio.file.Path;

/**
 * `--watch` on top of the automatic desktop projection, not instead of it.
 *
 * {@link OrcaDesktop} owns the workspace card and the run's {@code desktop.json} activity
 * journal; {@link OrcaWorkspace} owns the narration tab, its title and the stage rows. Before
 * they were composed, `--watch` swapped one for the other, and the live direct-CLI activity
 * the loop forwards went to a board that ignores it: no journal, and a card that only ever
 * said which stage was running.
 *
 * Every call goes to both, each guarded on its own so one board cannot starve the other. The
 * card is written once: the view leaves it to the desktop while the desktop is projecting,
 * and takes it back when the desktop was refused (quiet, headless, SSH) or failed, which is
 * what `--watch` did on its own.
 */
public final class OrcaWatchedDesktop implements Workspace {
    private final Workspace desktop;
    private final Workspace view;

    private OrcaWatchedDesktop(Workspace desktop, Workspace view) {
        this.desktop = Workspace.guarded(desktop);
        this.view = Workspace.guarded(view);
    }

    /** The desktop card and journal, plus the view's narration tab. */
    public static Workspace compose(OrcaDesktop desktop, Workspace view) {
        if (view == null || view == Workspace.NONE) return desktop;
        if (view instanceof OrcaWorkspace legacy) legacy.leaveCardTo(desktop::projecting);
        return new OrcaWatchedDesktop(desktop, view);
    }

    @Override public void note(String text) { desktop.note(text); view.note(text); }

    @Override public void state(State state) { desktop.state(state); view.state(state); }

    @Override public void working(String who, long millis) {
        desktop.working(who, millis);
        view.working(who, millis);
    }

    @Override public void activity(ExecutionActivity event) { desktop.activity(event); view.activity(event); }

    @Override public void watch(Path narration, String runId) {
        desktop.watch(narration, runId);
        view.watch(narration, runId);
    }

    @Override public void show(Path file, String runId, String title) {
        desktop.show(file, runId, title);
        view.show(file, runId, title);
    }

    @Override public void reveal(Path file) { desktop.reveal(file); view.reveal(file); }

    @Override public void stage(String label) { desktop.stage(label); view.stage(label); }

    @Override public void stageEnded(boolean ok, String summary) {
        desktop.stageEnded(ok, summary);
        view.stageEnded(ok, summary);
    }

    @Override public void adopt(String runId) { desktop.adopt(runId); view.adopt(runId); }

    @Override public void answered(String decision) { desktop.answered(decision); view.answered(decision); }
}
