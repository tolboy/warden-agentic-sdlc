package dev.warden;

import dev.warden.run.Heartbeat;
import dev.warden.run.Progress;
import dev.warden.run.Workspace;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The beat exists so that twenty minutes of a working vendor stop looking like twenty minutes
 * of nothing. These checks are about the two ways that promise can be broken: beats that never
 * arrive, and beats that arrive after the stage they described has finished.
 */
public final class HeartbeatTest implements Suite {

    @Override public String name() { return "heartbeat"; }

    @Override public void run(Check check) throws Exception {
        beatsWhileTheStageRuns(check);
        silenceAfterTheStageEnds(check);
        aBoardThatThrowsDoesNotStopTheBeat(check);
        noneNeverBeats(check);
        theGuardCoversTheBeat(check);
    }

    private void beatsWhileTheStageRuns(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        try (Heartbeat alive = Heartbeat.over("implementer", narration, board, Duration.ofMillis(40))) {
            Thread.sleep(220);
        }
        check.that("a stage that outlives one interval is reported as still working",
                narration.lines.size() >= 2);
        check.contains("and the line names the role, not the stage machinery",
                narration.lines.get(0), "implementer still working");
        check.contains("indented as a continuation of the stage above it",
                narration.lines.get(0), "      ... ");
        check.that("the board hears the same beats", board.beats.size() >= 2);
        check.eq("named the same way", "implementer", board.beats.get(0).who());
        check.that("and the clock moves forward between them",
                board.beats.get(1).millis() > board.beats.get(0).millis());
    }

    /**
     * The reason for the flag rather than the interrupt alone. A beat can wake up in the
     * moment between the stage finishing and close() returning, and a beat printed under a
     * stage's own result contradicts it.
     */
    private void silenceAfterTheStageEnds(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        Heartbeat alive = Heartbeat.over("reviewer", narration, board, Duration.ofMillis(30));
        Thread.sleep(120);
        alive.close();
        int printed = narration.lines.size();
        int told = board.beats.size();
        check.that("the stage did beat while it ran", printed > 0);
        Thread.sleep(150);
        check.eq("nothing is printed after the stage ends", printed, narration.lines.size());
        check.eq("and nothing reaches the board either", told, board.beats.size());
    }

    private void aBoardThatThrowsDoesNotStopTheBeat(Check check) throws Exception {
        Lines narration = new Lines();
        Workspace hostile = new Workspace() {
            @Override public void note(String text) { }
            @Override public void state(State state) { }
            @Override public void working(String who, long millis) {
                throw new IllegalStateException("orca died");
            }
        };
        try (Heartbeat alive = Heartbeat.over("gates", narration, hostile, Duration.ofMillis(40))) {
            Thread.sleep(220);
        }
        check.that("a board that throws on every beat does not silence the terminal",
                narration.lines.size() >= 2);
    }

    private void noneNeverBeats(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        try (Heartbeat off = Heartbeat.none()) {
            Thread.sleep(80);
        }
        check.that("a dry run says nothing", narration.lines.isEmpty() && board.beats.isEmpty());
    }

    private void theGuardCoversTheBeat(Check check) {
        Workspace guarded = Workspace.guarded(new Workspace() {
            @Override public void note(String text) { }
            @Override public void state(State state) { }
            @Override public void working(String who, long millis) {
                throw new IllegalStateException("orca died");
            }
        });
        guarded.working("implementer", 60_000L);
        check.that("the guard at the seam covers the beat as well as the card", true);
    }

    private record Beat(String who, long millis) {}

    private static final class Board implements Workspace {
        private final List<Beat> beats = new CopyOnWriteArrayList<>();
        @Override public void note(String text) { }
        @Override public void state(State state) { }
        @Override public void working(String who, long millis) { beats.add(new Beat(who, millis)); }
    }

    private static final class Lines implements Progress {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        @Override public void line(String text) { lines.add(text); }
    }
}
