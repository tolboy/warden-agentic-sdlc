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
 * of nothing. These checks are about the ways that promise can be broken: beats that never
 * arrive, beats that arrive after the stage they described has finished, and beats that name
 * the role while leaving out which vendor is inside it.
 */
public final class HeartbeatTest implements Suite {

    @Override public String name() { return "heartbeat"; }

    @Override public void run(Check check) throws Exception {
        beatsWhileTheStageRuns(check);
        aRoleNamesTheVendorInsideIt(check);
        aMachineStageGrowsNoBrackets(check);
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
     * The role is the one thing the operator already knew. On a roster where the implementer
     * can be grok or Codex, the question the beat exists to answer is which one is spending
     * the minutes — and that name has to come from the resolution that dispatched, published
     * onto a beat that has already started.
     */
    private void aRoleNamesTheVendorInsideIt(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        try (Heartbeat alive = Heartbeat.over("implementer", narration, board, Duration.ofMillis(40))) {
            alive.filledBy("grok-implement", "grok");
            Thread.sleep(220);
        }
        check.contains("the beat names the profile and the vendor filling the role",
                narration.lines.get(0), "implementer (grok-implement / grok) still working");
        check.contains("indented as a continuation of the stage above it",
                narration.lines.get(0), "      ... ");
        check.eq("the board hears the tab form: profile, not vendor",
                "implementer (grok-implement)", board.beats.get(0).who());
        check.eq("the spoken form is the profile and the vendor",
                "implementer (grok-implement / grok)",
                Heartbeat.spoken("implementer", "grok-implement", "grok"));
        check.eq("and the tab form drops the vendor",
                "implementer (grok-implement)",
                Heartbeat.tab("implementer", "grok-implement"));
    }

    /**
     * `gates` and `browser harness` run no vendor. An empty `( )` would look like a missing
     * profile rather than like a stage that never had one.
     */
    private void aMachineStageGrowsNoBrackets(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        try (Heartbeat alive = Heartbeat.over("gates", narration, board, Duration.ofMillis(40))) {
            Thread.sleep(220);
        }
        String line = narration.lines.get(0);
        check.contains("a stage that runs no vendor is named as itself", line, "gates still working");
        check.that("and grows no empty pair of brackets",
                !line.contains("(") && !line.contains(")"));
        check.eq("the board hears the same bare name", "gates", board.beats.get(0).who());
        check.eq("a blank profile is spoken as the role, even if a vendor string is lying around",
                "gates", Heartbeat.spoken("gates", null, "grok"));
        check.eq("and the tab grows no brackets either",
                "gates", Heartbeat.tab("gates", null));
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
        try (Heartbeat alive = Heartbeat.over("implementer", narration, hostile, Duration.ofMillis(40))) {
            alive.filledBy("grok-implement", "grok");
            Thread.sleep(220);
        }
        check.that("a board that throws on every beat does not silence the terminal",
                narration.lines.size() >= 2);
        check.contains("including after the resolved vendor has been named",
                narration.lines.get(0), "implementer (grok-implement / grok) still working");
    }

    private void noneNeverBeats(Check check) throws Exception {
        Lines narration = new Lines();
        Board board = new Board();
        try (Heartbeat off = Heartbeat.none()) {
            off.filledBy("grok-implement", "grok");
            Thread.sleep(80);
        }
        check.that("a dry run says nothing, even when told a vendor name",
                narration.lines.isEmpty() && board.beats.isEmpty());
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
