import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.gora.musicbattle.client.TrackEditorScreen;
import ru.gora.musicbattle.client.TrackEditorScreen.ChartEdits;
import ru.gora.musicbattle.data.BattleNote;
import ru.gora.musicbattle.data.ChartBeat;
import ru.gora.musicbattle.data.TrackEffect;

/**
 * The chart editor's editing rules, on the real code and without a game: notes placed on neighbouring lines, Ctrl+A
 * and the Ctrl+drag rectangle on both pages, where Ctrl+V puts a group and that it lands on the grid, Ctrl+Z bringing a
 * selection back and turning to what it changed, the length of a long note against the battle's, a note's box between
 * the lines on show, the beat-1 offset and the shaded lead-in before the song, and the preview's background setting.
 *
 * <p>The pure rules ({@code TrackEditorScreen.ChartEdits}, {@code ChartBeat}) are called directly. The rest runs the
 * screen's own private methods on an editor allocated without Minecraft, the way editor-layout does, with mouse
 * positions taken from the screen's own grid geometry. Where a rule replaces an older one, the older arithmetic is
 * reproduced here once to show the case it got wrong. How the editor looks in the game, none of this proves.
 */
public final class EditorEditingChecks {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static final int[] BPMS = {60, 96, 100, 110, 120, 128, 140, 150, 170, 200, 240, 333, 400};
    private static final int[] OFFSETS = {0, 437, 1500};

    public static void main(String[] args) throws Exception {
        notesInARow();
        pageSelection();
        clipboardPlacement();
        holdLength();
        offsetRules();
        editorClicks();
        rectangleSelection();
        selectionUndo();
        pasteInEditor();
        beatOneButton();
        offGridBox();
        leadIn();
        undoShowsStep();
        fxRectangle();
        previewBackground();
        shortcutRouting();
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(failure -> System.out.println("FAIL " + failure));
            System.out.println("editor-editing: " + failures.size() + " of " + checks + " checks failed");
            System.exit(1);
        }
        System.out.println("editor-editing: PASS (" + checks + ")");
        System.out.println("NOT COVERED: how the grid, the selection rectangle and the preview background look in the game,"
                + " and the keys GLFW really reports (keyPressed and mouseClicked here get a stubbed keyboard; MouseHandler's"
                + " Mac Ctrl+left-to-right conversion is reproduced, not run)");
    }

    // ---- notes in a row ----------------------------------------------------------------------------------------

    /**
     * Taps on every line of either grid fit one after another at every BPM, except where two lines round to the same
     * tick (the 1/8 grid above 150 BPM), which is one note per tick. The old rule measured a tap by its rounded cell
     * and refused the next line at 96 BPM on 1/8 and at 110 BPM on 1/4, the owner's "the cell is occupied".
     */
    private static void notesInARow() {
        boolean oldRefused96 = false;
        boolean oldRefused110 = false;
        for (int bpm : BPMS) {
            for (int cellsPerBeat : new int[] {4, 8}) {
                for (int offset : OFFSETS) {
                    List<BattleNote> chart = new ArrayList<>();
                    Set<Integer> ticks = new HashSet<>();
                    int previous = Integer.MIN_VALUE;
                    for (long cell = 0; cell < 72; cell++) {
                        int tick = ChartBeat.cellTick(offset, bpm, cellsPerBeat, cell);
                        BattleNote tap = new BattleNote(0, 1, tick, 0);
                        boolean fits = ChartEdits.fits(chart, List.of(tap));
                        String at = bpm + " BPM 1/" + cellsPerBeat + " offset " + offset + " line " + cell + ": ";
                        check(fits == (tick != previous), at + "a tap on the next line fits unless the line rounds onto the last tick");
                        if (fits) {
                            chart.add(tap);
                            ticks.add(tick);
                        }
                        int oldStep = Math.max(1, (int) Math.round(1200.0D / (bpm * cellsPerBeat)));
                        if (previous != Integer.MIN_VALUE && tick != previous && tick - previous < oldStep) {
                            oldRefused96 |= bpm == 96 && cellsPerBeat == 8;
                            oldRefused110 |= bpm == 110 && cellsPerBeat == 4;
                        }
                        previous = tick;
                    }
                    check(chart.size() == ticks.size(), bpm + " BPM 1/" + cellsPerBeat + ": every distinct line tick holds a note");
                }
            }
        }
        check(oldRefused96, "the old rule refused neighbouring eighths at 96 BPM (reproduced)");
        check(oldRefused110, "the old rule refused neighbouring quarters at 110 BPM (reproduced)");

        BattleNote hold = new BattleNote(0, 2, 100, 10);
        check(!ChartEdits.fits(List.of(hold), List.of(new BattleNote(0, 2, 110, 0))), "a tap on a hold's release line collides: the release is drawn as a box");
        check(ChartEdits.fits(List.of(hold), List.of(new BattleNote(0, 2, 111, 0))), "a tap one tick after the release fits");
        check(!ChartEdits.fits(List.of(hold), List.of(new BattleNote(0, 2, 105, 0))), "a tap inside a hold collides");
        check(ChartEdits.fits(List.of(hold), List.of(new BattleNote(1, 2, 105, 0))), "the other player's notes never collide");
        check(ChartEdits.fits(List.of(hold), List.of(new BattleNote(0, 3, 105, 0))), "another lane never collides");
        check(!ChartEdits.fits(List.of(), List.of(new BattleNote(0, 0, 5, 0), new BattleNote(0, 0, 5, 0))), "two new notes on one tick collide");

        // A pair an older editor left touching (a tap on a hold's release) moves together; a move never makes a new collision.
        List<BattleNote> legacy = List.of(new BattleNote(0, 1, 40, 8), new BattleNote(0, 1, 48, 0));
        List<BattleNote> chart = new ArrayList<>(legacy);
        chart.add(new BattleNote(0, 1, 70, 0));
        List<BattleNote> moved = List.of(new BattleNote(0, 1, 50, 8), new BattleNote(0, 1, 58, 0));
        check(ChartEdits.fits(chart, legacy, moved), "a touching legacy pair can move together");
        List<BattleNote> ontoStaying = List.of(new BattleNote(0, 1, 62, 8), new BattleNote(0, 1, 70, 0));
        check(!ChartEdits.fits(chart, legacy, ontoStaying), "a moved note may not land on a note that stays");
        List<BattleNote> apart = List.of(new BattleNote(0, 1, 10, 0), new BattleNote(0, 1, 20, 0));
        check(!ChartEdits.fits(List.of(), apart, List.of(new BattleNote(0, 1, 30, 0), new BattleNote(0, 1, 30, 0))),
                "two moved notes that were apart may not end up on one tick");
    }

    // ---- Ctrl+A --------------------------------------------------------------------------------------------------

    private static void pageSelection() {
        List<BattleNote> chart = List.of(new BattleNote(0, 0, 70, 30), new BattleNote(0, 1, 80, 0), new BattleNote(1, 1, 90, 0),
                new BattleNote(0, 2, 150, 20), new BattleNote(0, 3, 159, 0), new BattleNote(0, 3, 160, 0));
        List<BattleNote> page = ChartEdits.pageSelection(chart, 0, 80, 160);
        check(page.equals(List.of(chart.get(1), chart.get(3), chart.get(4))),
                "Ctrl+A takes the edited player's notes pressed on the page, holds running on past it included: " + page);
        check(ChartEdits.pageSelection(chart, 1, 80, 160).equals(List.of(chart.get(2))), "Ctrl+A for P2 takes only P2");
    }

    // ---- Ctrl+C / Ctrl+V ----------------------------------------------------------------------------------------

    private static void clipboardPlacement() {
        boolean oldOffGrid = false;
        for (int bpm : BPMS) {
            for (int offset : OFFSETS) {
                String at = bpm + " BPM offset " + offset + ": ";
                // A bar from bar 2: taps on quarter lines 0, 1, 2 and 5 of it and a hold from quarter 6 to 8.
                long first = 16;
                List<BattleNote> group = new ArrayList<>();
                int[] quarters = {0, 1, 2, 5};
                for (int i = 0; i < quarters.length; i++) {
                    group.add(new BattleNote(0, i, ChartBeat.cellTick(offset, bpm, 4, first + quarters[i]), 0));
                }
                int press = ChartBeat.cellTick(offset, bpm, 4, first + 6);
                group.add(new BattleNote(0, 1, press, ChartBeat.cellTick(offset, bpm, 4, first + 8) - press));
                List<BattleNote> chart = new ArrayList<>(group);
                ChartEdits.Clipboard clipboard = ChartEdits.copy(group, List.of(), offset, bpm);
                check(clipboard.size() == 5 && clipboard.phase() == 0, at + "the copy holds the group, on the beat");
                long anchor = clipboard.firstCell();
                int previousEnd = group.stream().mapToInt(ChartEdits::occupiedUntil).max().orElse(0);
                long previousCell = anchor;
                for (int paste = 1; paste <= 4; paste++) {
                    long cell = ChartEdits.pasteCell(chart, List.of(), clipboard, anchor, 0, offset, bpm);
                    List<BattleNote> placed = ChartEdits.placeNotes(clipboard, cell, offset, bpm);
                    String where = at + "paste " + paste + ": ";
                    check(ChartEdits.fits(chart, placed), where + "lands in free space");
                    check(placed.stream().mapToInt(BattleNote::tick).min().orElse(0) >= previousEnd, where + "starts after the last copy ends");
                    check((cell - previousCell) % ChartEdits.FINE_CELLS_PER_BEAT == 0, where + "moves by whole beats");
                    for (int i = 0; i < placed.size(); i++) {
                        BattleNote note = placed.get(i);
                        check(onLine(offset, bpm, 4, note.tick()), where + "note " + i + " is on a quarter line, tick " + note.tick());
                        if (note.durationTicks() > 0) {
                            check(onLine(offset, bpm, 4, note.tick() + note.durationTicks()), where + "the hold is released on a line");
                        }
                    }
                    // Each note keeps its line, moved by the paste's whole beats; below 300 BPM that is its own quarter.
                    long shift = cell - clipboard.firstCell();
                    for (int i = 0; i < quarters.length; i++) {
                        BattleNote source = group.get(i);
                        int expected = ChartEdits.fineTick(ChartEdits.fineCell(source.tick(), offset, bpm) + shift, offset, bpm);
                        check(placed.get(i).tick() == expected, where + "quarter " + quarters[i] + " keeps its line");
                        if (bpm <= 300) {
                            check(expected == ChartBeat.cellTick(offset, bpm, 4, first + quarters[i] + shift / 2), where + "which is its own quarter, moved");
                        }
                    }
                    chart.addAll(placed);
                    previousEnd = placed.stream().mapToInt(ChartEdits::occupiedUntil).max().orElse(0);
                    previousCell = cell;
                    anchor = cell;
                }
                // The old clipboard kept tick distances and pasted from the page's first tick; somewhere that leaves the grid.
                int pageStart = ChartBeat.pageStart(offset, bpm, 8, 1);
                for (BattleNote note : group) {
                    int old = pageStart + note.tick() - group.get(0).tick();
                    oldOffGrid |= !onLine(offset, bpm, 4, old);
                }
            }
        }
        check(oldOffGrid, "the old paste put some note between lines (reproduced)");

        // From the off-beat: a group whose first note is on an eighth between beats stays there.
        int bpm = 128;
        List<BattleNote> offBeat = List.of(new BattleNote(0, 0, ChartBeat.cellTick(0, bpm, 8, 4), 0),
                new BattleNote(0, 1, ChartBeat.cellTick(0, bpm, 8, 6), 0));
        ChartEdits.Clipboard offClip = ChartEdits.copy(offBeat, List.of(), 0, bpm);
        check(offClip.phase() == 4, "a group copied from the off-beat knows it");
        long offCell = ChartEdits.pasteCell(offBeat, List.of(), offClip, offClip.firstCell(), 0, 0, bpm);
        check(Math.floorMod(offCell, ChartEdits.FINE_CELLS_PER_BEAT) == 4, "and is pasted on the off-beat");

        // A staircase is never interleaved with itself, which a note-by-note test would allow one beat on.
        List<BattleNote> stairs = new ArrayList<>();
        for (int lane = 0; lane < 4; lane++) {
            stairs.add(new BattleNote(0, lane, ChartBeat.cellTick(0, bpm, 1, lane), 0));
        }
        List<BattleNote> oneBeatOn = new ArrayList<>();
        for (BattleNote note : stairs) {
            oneBeatOn.add(new BattleNote(0, note.lane(), ChartBeat.cellTick(0, bpm, 1, note.lane() + 1), 0));
        }
        check(ChartEdits.fits(stairs, oneBeatOn), "a staircase one beat on collides with no note of itself");
        ChartEdits.Clipboard stairClip = ChartEdits.copy(stairs, List.of(), 0, bpm);
        long stairCell = ChartEdits.pasteCell(stairs, List.of(), stairClip, stairClip.firstCell(), 0, 0, bpm);
        check(stairCell - stairClip.firstCell() == 4L * ChartEdits.FINE_CELLS_PER_BEAT, "yet the paste waits for the whole staircase to pass");

        // The lead-in: searching from the lead-in page never puts a note before the song.
        int offset = 1500;
        List<BattleNote> early = List.of(new BattleNote(0, 0, 0, 0), new BattleNote(0, 1, 5, 0));
        ChartEdits.Clipboard earlyClip = ChartEdits.copy(early, List.of(), offset, 120);
        long leadIn = ChartBeat.pageFromBeatOne(offset, 120, 8, 0) * 8L * ChartEdits.FINE_CELLS_PER_BEAT + earlyClip.phase();
        long earlyCell = ChartEdits.pasteCell(List.of(), List.of(), earlyClip, leadIn, 0, offset, 120);
        check(ChartEdits.placeNotes(earlyClip, earlyCell, offset, 120).stream().allMatch(note -> note.tick() >= 0)
                && ChartEdits.fineTick(earlyCell, offset, 120) >= 0, "a paste from the lead-in page lands in the song");

        // A note between lines (an import) keeps its distance from its line; a tap made on the grid has none. At 60 BPM
        // an eighth is 2.5 ticks, so tick 26 lies between the lines on 25 and 28.
        BattleNote between = new BattleNote(0, 2, ChartBeat.cellTick(0, 60, 8, 10) + 1, 0);
        check(!onLine(0, 60, 8, between.tick()), "tick 26 at 60 BPM is between eighth lines");
        ChartEdits.Clipboard betweenClip = ChartEdits.copy(List.of(between), List.of(), 0, 60);
        BattleNote placed = ChartEdits.placeNotes(betweenClip, betweenClip.firstCell() + 24, 0, 60).get(0);
        check(placed.tick() == ChartBeat.cellTick(0, 60, 8, 34) + 1, "a note between lines keeps its offset from its line");

        // Effects: rows follow the visible top row, and only effects on the target rows stand in the way.
        TrackEffect flash = new TrackEffect(TrackEffect.Type.FLASH, 2, ChartBeat.cellTick(0, 120, 4, 0), 0, 1.0F, 0.5F);
        TrackEffect bars = new TrackEffect(TrackEffect.Type.CINEMATIC_BARS, 3, ChartBeat.cellTick(0, 120, 4, 2),
                ChartBeat.cellTick(0, 120, 4, 6) - ChartBeat.cellTick(0, 120, 4, 2), 1.0F, 0.5F);
        ChartEdits.Clipboard fx = ChartEdits.copy(List.of(), List.of(flash, bars), 0, 120);
        List<TrackEffect> fxPlaced = ChartEdits.placeEffects(fx, fx.firstCell() + 64, 6, 0, 120);
        check(fxPlaced.get(0).layer() * 6 + fxPlaced.get(0).lane() == 6 && fxPlaced.get(1).layer() * 6 + fxPlaced.get(1).lane() == 7,
                "pasted effects keep their rows under the visible top row");
        // Row 6 is lane 0 of layer 1, one of the rows the paste goes to; row 9 is lane 3 of layer 1, which it does not use.
        TrackEffect blocker = new TrackEffect(TrackEffect.Type.FLASH, 0, ChartBeat.cellTick(0, 120, 4, 1), 40, 1.0F, 0.5F).withLayer(1);
        TrackEffect elsewhere = new TrackEffect(TrackEffect.Type.FLASH, 3, ChartBeat.cellTick(0, 120, 4, 1), 400, 1.0F, 0.5F).withLayer(1);
        long fxCell = ChartEdits.pasteCell(List.of(), List.of(blocker, elsewhere), fx, fx.firstCell(), 6, 0, 120);
        int fxStart = ChartEdits.placeEffects(fx, fxCell, 6, 0, 120).get(0).tick();
        check(fxStart >= blocker.tick() + blocker.durationTicks() && fxStart < elsewhere.tick() + elsewhere.durationTicks(),
                "an effect on a target row is waited for, one on another row is not");

        // Moves by lines keep a group's rhythm where tick distances did not.
        List<BattleNote> row = new ArrayList<>();
        for (int cell = 0; cell < 8; cell++) {
            row.add(new BattleNote(0, 0, ChartBeat.cellTick(0, 96, 4, cell), 0));
        }
        List<BattleNote> right = ChartEdits.shiftNotes(row, 2, 0, 0, 96);
        for (int cell = 0; cell < 8; cell++) {
            check(right.get(cell).tick() == ChartBeat.cellTick(0, 96, 4, cell + 1), "96 BPM: a move one quarter right keeps line " + cell + " on a line");
        }
        check(ChartEdits.shiftNotes(row, -2, 0, 0, 96) == null, "a move before the song is refused, not squeezed onto tick 0");
    }

    private static boolean onLine(int offset, int bpm, int cellsPerBeat, int tick) {
        return ChartBeat.cellTick(offset, bpm, cellsPerBeat, ChartBeat.nearestCell(offset, bpm, cellsPerBeat, tick)) == tick;
    }

    // ---- long notes ----------------------------------------------------------------------------------------------

    /**
     * A hold made with the right button is released on the next line, and the battle releases it there: its tail
     * reaches the judgement line at tick + duration. The editor now draws that release line as a box of its own.
     */
    private static void holdLength() throws Exception {
        for (int bpm : BPMS) {
            for (int cellsPerBeat : new int[] {4, 8}) {
                for (int offset : OFFSETS) {
                    for (long cell = 0; cell < 40; cell++) {
                        int tick = ChartBeat.cellTick(offset, bpm, cellsPerBeat, cell);
                        for (int lines : new int[] {1, 4}) {
                            int release = ChartBeat.lineAfter(offset, bpm, cellsPerBeat, tick, lines);
                            String at = bpm + " BPM 1/" + cellsPerBeat + " line " + cell + " +" + lines + ": ";
                            check(release > tick, at + "the release is after the press");
                            check(onLine(offset, bpm, cellsPerBeat, release), at + "the release is on a line");
                            // Where two lines share the tick, the count starts from the coarser one, as copying does.
                            long line = ChartBeat.lineOf(offset, bpm, cellsPerBeat, tick);
                            check(ChartBeat.cellTick(offset, bpm, cellsPerBeat, line) == tick, at + "the tick stands on a line of its own");
                            int plain = ChartBeat.cellTick(offset, bpm, cellsPerBeat, line + lines);
                            check(plain > tick ? release == plain : release > plain, at + "it is that many lines on");
                            if (1200.0D / (bpm * cellsPerBeat) >= 1.0D) {
                                check(release == ChartBeat.cellTick(offset, bpm, cellsPerBeat, cell + lines), at + "counted from the line clicked");
                            }
                        }
                    }
                }
            }
        }
        Class<?> panel = Class.forName("ru.gora.musicbattle.client.BattlePanelRenderer");
        Method travel = panel.getDeclaredMethod("noteTravelY", float.class, int.class, int.class, float.class, float.class, float.class);
        travel.setAccessible(true);
        int tick = 200;
        int duration = 13;
        float hitY = 300.0F;
        float atRelease = (float) travel.invoke(null, (float) (tick + duration), tick + duration, 20, 40.0F, hitY, 380.0F);
        float before = (float) travel.invoke(null, (float) (tick + duration - 1), tick + duration, 20, 40.0F, hitY, 380.0F);
        check(atRelease == hitY && before < hitY, "the battle's tail end reaches the judgement line at tick + duration, no later");
    }

    // ---- the beat-1 offset ---------------------------------------------------------------------------------------

    private static void offsetRules() {
        check(ChartBeat.storableOffsetMs(437, 120) == 437, "a storable beat 1 is kept to the millisecond");
        check(ChartBeat.storableOffsetMs(5000, 120) == 5000, "beat 1 at 5 s stays at 5 s, so bar 1 is where it was put");
        check(ChartBeat.storableOffsetMs(15_000, 120) == 11_000, "15 s at 120 BPM is stored two bars earlier, at 11 s");
        check(ChartBeat.storableOffsetMs(-500, 120) == 1500, "-500 ms is the same grid at 1.5 s");
        check(ChartBeat.storableOffsetMs(Double.NaN, 120) == 0, "not a number is no offset");
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            int bpm = 20 + random.nextInt(381);
            double wanted = random.nextDouble() * 60_000 - 10_000;
            int stored = ChartBeat.storableOffsetMs(wanted, bpm);
            double bars = (wanted - stored) / ChartBeat.barMs(bpm);
            check(stored >= 0 && stored <= ChartBeat.MAX_OFFSET_MS, "stored beat 1 is in range: " + wanted + " at " + bpm);
            check(Math.abs(bars - Math.rint(bars)) * ChartBeat.barMs(bpm) <= 0.5001D, "stored beat 1 draws the same grid: " + wanted + " at " + bpm);
        }
        check(ChartBeat.barOfCell(0, 4) == 1 && ChartBeat.barOfCell(15, 4) == 1 && ChartBeat.barOfCell(16, 4) == 2
                && ChartBeat.barOfCell(-1, 4) == 0 && ChartBeat.barOfCell(32, 8) == 2, "bars count from beat 1 as bar 1");
        double line = ChartBeat.cellTick(500, 128, 4, 7) + 0.0D;
        double exactLine = (500 + 7 * 15_000.0D / 128) / 50.0D;
        check(ChartBeat.cellAtOrBefore(500, 128, 4, exactLine) == 7 && ChartBeat.cellAtOrBefore(500, 128, 4, exactLine - 0.01) == 6,
                "the box under the mouse starts at its own line: " + line);
    }

    // ---- the real editor -----------------------------------------------------------------------------------------

    /**
     * Placing taps line after line through the editor's own grid click, at the tempo of the report (96 BPM on 1/8)
     * and others; a click anywhere on a note's box, edge or corner, is the note's and never removes it; and the right
     * button toggles a long note, on its tail too.
     */
    private static void editorClicks() throws Exception {
        for (String bpm : new String[] {"96", "110", "128", "170"}) {
            for (int division = 0; division < 2; division++) {
                Editor editor = new Editor(bpm, division);
                int columns = (int) editor.call("gridColumns");
                Set<Integer> ticks = new HashSet<>();
                for (int column = 0; column < columns; column++) {
                    ticks.add((int) editor.call("gridTickAtColumn", column));
                    editor.click(column, 1, 0);
                }
                String at = bpm + " BPM 1/" + (4 * (division + 1)) + ": ";
                check(editor.notes().size() == ticks.size(), at + "a tap on every line of the page, " + editor.notes().size() + " of " + ticks.size());
                // A line that rounds onto the tick of the note before it selects that note: still one step each.
                check(editor.undoSteps() == columns, at + "every click that changed something is one Ctrl+Z step");
            }
        }

        Editor editor = new Editor("128", 0);
        editor.put(20, 3, 0, 4);
        int steps = editor.undoSteps();
        editor.click(22, 3, 0);
        check(editor.notes().size() == 1 && editor.undoSteps() == steps, "a click inside a hold is refused and leaves no Ctrl+Z step");
        editor.notes().clear();
        editor.call("invalidateTimelineCache");
        editor.click(5, 2, 0);
        BattleNote tap = editor.notes().get(0);
        Object grid = editor.call("grid");
        int laneTop = editor.laneTop(2);
        int laneHeight = editor.laneHeight();
        int line = editor.lineX(5);
        int next = editor.lineX(6);
        double[][] edges = {{line, laneTop + 1}, {line + 0.5, laneTop + laneHeight - 0.5}, {next - 0.5, laneTop + 1}, {next - 0.5, laneTop + laneHeight - 1}};
        for (double[] point : edges) {
            Object hovered = editor.call("hoveredNoteInfo", point[0], point[1]);
            check(hovered != null && tap.equals(record(hovered, "note")), "a press at " + point[0] + "," + point[1] + " on the note's box is the note's");
            // mouseClicked hands such a press to the note, not to the grid; even the grid's own click never removes it.
            editor.call("toggleGridNote", point[0], point[1], 0);
            check(editor.notes().contains(tap), "a grid click on the edge of a note never removes it");
            editor.notes().retainAll(List.of(tap));
            editor.call("invalidateTimelineCache");
        }

        // The right button: a tap becomes a hold released on the next line, the hold on its release box becomes a tap.
        editor.call("toggleHold", next - 1.0D, laneTop + laneHeight / 2.0D, tap.tick(), 2, 1, false);
        BattleNote hold = editor.notes().get(0);
        int nextLineTick = (int) editor.call("gridTickAtColumn", 6);
        check(hold.tick() == tap.tick() && hold.tick() + hold.durationTicks() == nextLineTick, "right button on a tap: a hold released on the next line");
        Object bounds = editor.call("noteBounds", hold, grid, laneHeight, (int) editor.call("stepTicks"));
        int releaseX = (int) editor.call("timelineTickX", grid, (float) (hold.tick() + hold.durationTicks()));
        check((int) record(bounds, "releaseLeft") == releaseX + 3, "the release box starts on the line the battle releases the hold on");
        check((int) record(bounds, "hitRight") == editor.lineX(7), "the hold takes clicks up to the end of its release box: one box more than a tap");
        Object tapBounds = editor.call("noteBounds", tap, grid, laneHeight, (int) editor.call("stepTicks"));
        check((int) record(tapBounds, "hitRight") == next, "a tap takes clicks up to the next line");
        double tailX = (editor.lineX(6) + editor.lineX(7)) / 2.0D;
        editor.call("toggleHold", tailX, laneTop + 2.0D, (int) editor.call("gridTickAtColumn", 6), 2, 1, false);
        check(editor.notes().equals(List.of(tap)), "right button on a long note's release box turns it back into the tap it was");
        editor.call("toggleHold", editor.lineX(12) + 2.0D, laneTop + 2.0D, (int) editor.call("gridTickAtColumn", 12), 2, 4, false);
        BattleNote fresh = editor.notes().get(1);
        check(fresh.tick() + fresh.durationTicks() == (int) editor.call("gridTickAtColumn", 16), "right button on an empty line: a hold four lines long with Shift");
    }

    /** Ctrl+drag selects the edited player's notes whose boxes meet the rectangle; with Shift it adds to the selection. */
    private static void rectangleSelection() throws Exception {
        Editor editor = new Editor("120", 0);
        BattleNote a = editor.put(2, 1, 0, 0);
        BattleNote b = editor.put(3, 1, 0, 0);
        BattleNote c = editor.put(4, 1, 0, 0);
        BattleNote far = editor.put(10, 2, 0, 0);
        BattleNote otherPlayer = editor.put(3, 1, 1, 0);
        BattleNote hold = editor.put(6, 3, 0, 3);
        editor.drag(2.5, 1, 7.5, 3, false);
        check(editor.selectedNotes().equals(Set.of(a, b, c, hold)), "the rectangle takes the notes it meets, of the edited player: " + editor.selectedNotes());
        editor.drag(9.5, 3, 9.8, 3, false);
        check(editor.selectedNotes().equals(Set.of(hold)), "a rectangle over a hold's release box alone takes the hold");
        editor.drag(10.2, 2, 10.6, 2, true);
        check(editor.selectedNotes().equals(Set.of(hold, far)), "Ctrl+Shift adds the rectangle to the selection");
        check(!editor.selectedNotes().contains(otherPlayer), "the other player's note is never taken");
        editor.drag(20.2, 0, 22.6, 0, false);
        check(editor.selectedNotes().isEmpty(), "an empty rectangle selects nothing");
    }

    /** Selection steps are undone and redone like edits, with the notes as they were. */
    private static void selectionUndo() throws Exception {
        Editor editor = new Editor("120", 0);
        BattleNote a = editor.put(1, 0, 0, 0);
        BattleNote b = editor.put(2, 1, 0, 0);
        BattleNote c = editor.put(5, 2, 0, 0);
        editor.put(40, 2, 0, 0); // on the next page
        editor.call("selectPage");
        check(editor.selectedNotes().equals(Set.of(a, b, c)), "Ctrl+A takes this page's notes");
        editor.drag(1.5, 0, 2.5, 1, false);
        check(editor.selectedNotes().equals(Set.of(a, b)), "a rectangle then takes two of them");
        editor.call("undo");
        check(editor.selectedNotes().equals(Set.of(a, b, c)), "Ctrl+Z brings back the selection before the rectangle");
        editor.call("undo");
        check(editor.selectedNotes().isEmpty(), "Ctrl+Z again brings back the empty selection");
        editor.call("redo");
        check(editor.selectedNotes().equals(Set.of(a, b, c)), "Shift+Ctrl+Z redoes the selection");
        editor.call("clearSelection");
        editor.call("undo");
        check(editor.selectedNotes().equals(Set.of(a, b, c)), "clearing the selection is undone too");
        editor.call("moveSelection", 1, 0);
        check(!editor.notes().contains(a), "the arrow moves the selected notes");
        editor.call("undo");
        check(editor.notes().containsAll(List.of(a, b, c)) && editor.selectedNotes().equals(Set.of(a, b, c)),
                "Ctrl+Z after a move brings back the notes and their selection");
        int steps = editor.undoSteps();
        editor.call("selectPage");
        check(editor.undoSteps() == steps, "a selection that changes nothing is no step");
        // A step taken while one part was edited must not bring its notes back selected while the other part is
        // edited: they would be faint there and still answer Delete and the arrow keys.
        editor.call("clearSelection");
        editor.drag(1.5, 0, 2.5, 1, false);
        check(editor.selectedNotes().equals(Set.of(a, b)), "the first part's rectangle takes its two notes");
        editor.call("undo");
        editor.set("owner", 1);
        editor.call("redo");
        check(editor.selectedNotes().isEmpty(),
                "Shift+Ctrl+Z while the other part is edited selects none of the first part's notes: " + editor.selectedNotes());
        check(editor.notes().containsAll(List.of(a, b, c)), "the notes themselves come back whoever is edited");
        editor.set("owner", 0);
    }

    /** Ctrl+V again and again lays copies one after another on the grid; Ctrl+Z and Ctrl+V lands in the same place. */
    private static void pasteInEditor() throws Exception {
        Editor editor = new Editor("128", 0);
        List<BattleNote> group = List.of(editor.put(0, 0, 0, 0), editor.put(1, 1, 0, 0), editor.put(3, 2, 0, 2));
        editor.selectedNotes().addAll(group);
        editor.call("copySelection");
        List<List<BattleNote>> pastes = new ArrayList<>();
        int previousEnd = group.stream().mapToInt(ChartEdits::occupiedUntil).max().orElse(0);
        for (int paste = 0; paste < 6; paste++) {
            List<BattleNote> before = new ArrayList<>(editor.notes());
            editor.call("pasteSelection");
            List<BattleNote> added = new ArrayList<>(editor.notes());
            added.removeAll(before);
            check(added.size() == 3, "paste " + paste + " adds the group");
            check(added.stream().mapToInt(BattleNote::tick).min().orElse(0) >= previousEnd, "paste " + paste + " follows the one before");
            check(added.stream().allMatch(note -> onLine(0, 128, 4, note.tick())), "paste " + paste + " is on the grid");
            check(editor.selectedNotes().equals(new HashSet<>(added)), "paste " + paste + " is what is selected");
            int pageStart = (int) editor.field("pageStartTick");
            int first = added.stream().mapToInt(BattleNote::tick).min().orElse(0);
            check(first >= pageStart && first < pageStart + (int) editor.call("pageTicks"), "paste " + paste + " is on the page shown");
            previousEnd = added.stream().mapToInt(ChartEdits::occupiedUntil).max().orElse(0);
            pastes.add(added);
        }
        editor.call("undo");
        check(!editor.notes().containsAll(pastes.get(5)) && editor.selectedNotes().equals(new HashSet<>(pastes.get(4))),
                "Ctrl+Z removes the last paste and selects the one before again");
        editor.call("pasteSelection");
        check(editor.notes().containsAll(pastes.get(5)), "Ctrl+V after Ctrl+Z lands where the undone paste was, leaving no gap");
    }

    /**
     * "Beat 1 here" puts beat 1 on the playhead, to the millisecond when a chart can store it, turns to the page that
     * starts on it, and moves no note; what is typed into the field is read the same way.
     */
    private static void beatOneButton() throws Exception {
        Editor editor = new Editor("120", 0);
        editor.put(3, 0, 0, 0);
        editor.put(9, 1, 0, 4);
        List<BattleNote> notes = List.copyOf(editor.notes());
        editor.set("previewTick", 30);
        editor.call("setBeatOneAtPlayhead");
        check((int) editor.call("offsetMs") == 1500, "beat 1 is put on the playhead at 1.5 s");
        check(editor.notes().equals(notes), "and no note moved");
        check((int) editor.field("pageStartTick") == 30 && (long) editor.call("pageFromBeatOne") == 0L, "the page shown starts on beat 1");
        editor.set("previewTick", 300);
        editor.call("setBeatOneAtPlayhead");
        check((int) editor.call("offsetMs") == 11_000, "a playhead at 15 s is stored two bars earlier, at 11 s");
        check((int) editor.field("pageStartTick") == 300, "and the page shown still starts at the playhead, a bar line");
        for (Object[] typed : new Object[][] {{"2500", 2500}, {"15000", 11_000}, {"-500", 1500}, {"", 0}, {"abc", 0}, {"12.4", 12}}) {
            editor.set("draftOffset", typed[0]);
            check((int) editor.call("offsetMs") == (int) typed[1], "the field reads '" + typed[0] + "' as " + typed[1]);
        }
    }

    /**
     * A note put on the 1/8 grid and seen on 1/4 sits between the lines on show. Its box ends at the next line, so a
     * click just right of that line places a note there; the box used to run a whole cell on and grab that click.
     */
    private static void offGridBox() throws Exception {
        Editor editor = new Editor("96", 1);
        BattleNote eighth = editor.put(1, 2, 0, 0);
        check(eighth.tick() == 2, "96 BPM: the first eighth line is tick 2");
        editor.set("gridDivisionIndex", 0);
        editor.call("invalidateTimelineCache");
        double x = editor.lineX(1) + 1.0D;
        double y = editor.laneTop(2) + editor.laneHeight() / 2.0D;
        check(editor.call("hoveredNoteInfo", x, y) == null, "96 BPM on 1/4: the eighth's box ends at the quarter line after it");
        Object grid = editor.call("grid");
        int oldEnd = eighth.tick() + (int) editor.call("stepTicks");
        check((int) editor.call("timelineTickX", grid, (float) oldEnd) > x, "the old box ran a whole cell on, past that line (reproduced)");
        editor.click(1, 2, 0);
        check(editor.notes().size() == 2 && editor.notes().get(1).tick() == 3, "a click just right of the quarter line places a note on it, tick 3");
    }

    /**
     * With beat 1 at 1.5 s, page 0 runs from 2.5 s before the song to beat 1, so its first 20 lines come before the
     * song. That shaded part takes no notes: a click there used to be clamped onto tick 0, whichever line it was on.
     */
    private static void leadIn() throws Exception {
        Editor editor = new Editor("120", 0);
        editor.set("draftOffset", "1500");
        check((long) editor.call("pageFromBeatOne") == -1L, "with beat 1 at 1.5 s, page 0 leads in to it");
        for (int column : new int[] {0, 7, 19}) {
            editor.click(column, 1, 0);
            // The right button's path asks for Shift, which needs a running game, so its handler is called directly with
            // what the grid click works out for this line.
            editor.call("toggleHold", editor.lineX(column) + 1.0D, editor.laneTop(2) + 2.0D,
                    (int) editor.call("gridTickAtColumn", column), 2, 1, true);
            check(!editor.columnTickInSong(column), "line " + column + " of the lead-in is before the song");
        }
        check(editor.notes().isEmpty() && editor.undoSteps() == 0, "a click on the shade before the song places nothing and leaves no Ctrl+Z step");
        check(String.valueOf(editor.field("status")).contains("before_song_click"), "and the status says why: " + editor.field("status"));
        check(Math.max(0, ChartBeat.cellTick(1500, 120, 4, -32 + 7)) == 0, "the old click on line 7 of the shade put its note on tick 0 (reproduced)");
        editor.click(20, 1, 0);
        check(editor.notes().equals(List.of(new BattleNote(0, 1, 0, 0))), "the first line in the song takes a note, on tick 0");
        editor.call("toggleHold", editor.lineX(20) + 2.0D, editor.laneTop(1) + 2.0D, 0, 1, 1, false);
        check(editor.notes().get(0).durationTicks() > 0, "the right button still makes that note long");
    }

    /**
     * Ctrl+Z and Shift+Ctrl+Z show what they change. Undoing a note placed on a later page stays on that page, though
     * the selection it brings back lies on page 0; a step that only changed the selection turns to that selection.
     */
    private static void undoShowsStep() throws Exception {
        Editor editor = new Editor("120", 0);
        BattleNote first = editor.put(3, 0, 0, 0);
        editor.call("selectPage");
        int pageTwo = (int) editor.call("pageStart", 2L);
        editor.set("pageStartTick", pageTwo);
        editor.call("invalidateTimelineCache");
        editor.click(5, 1, 0);
        check(editor.notes().size() == 2 && editor.selectedNotes().equals(Set.of(first)), "a note placed on page 2 keeps page 0's selection");
        editor.call("undo");
        check(editor.notes().equals(List.of(first)) && (int) editor.field("pageStartTick") == pageTwo,
                "Ctrl+Z removes the note and stays on page 2, where it was");
        editor.call("undo");
        check(editor.selectedNotes().isEmpty() && (int) editor.field("pageStartTick") == pageTwo,
                "undoing the selection leaves nothing to show, so the page stays");
        editor.call("redo");
        check(editor.selectedNotes().equals(Set.of(first)) && (int) editor.field("pageStartTick") == 0,
                "redoing a selection turns to it, on page 0");
        editor.call("redo");
        check(editor.notes().size() == 2 && (int) editor.field("pageStartTick") == pageTwo, "redoing the note turns back to page 2");
    }

    /** On the FX page Ctrl+drag takes the effects whose bars it touches, in the rows it covers, and no others. */
    private static void fxRectangle() throws Exception {
        Editor editor = new Editor("120", 0);
        editor.set("editorPage", Class.forName(TrackEditorScreen.class.getName() + "$EditorPage").getEnumConstants()[1]);
        int tick = (int) editor.call("gridTickAtColumn", 4);
        TrackEffect top = new TrackEffect(TrackEffect.Type.FLASH, 0, tick, 0, 1.0F, 0.5F);
        TrackEffect second = new TrackEffect(TrackEffect.Type.SHAKE, 1, tick, 0, 1.0F, 0.5F);
        TrackEffect later = new TrackEffect(TrackEffect.Type.PULSE, 0, (int) editor.call("gridTickAtColumn", 20), 0, 1.0F, 0.5F);
        editor.effects().addAll(List.of(top, second, later));
        editor.call("invalidateTimelineCache");
        Object grid = editor.call("grid");
        int gridY = (int) record(grid, "y");
        double row = (int) record(grid, "height") / 6;
        editor.dragAt(editor.columnX(3.5), gridY + row * 0.5, editor.columnX(5.0), gridY + row * 0.6, false);
        check(editor.selectedEffects().equals(Set.of(top)), "a rectangle in row 1 takes that row's effect: " + editor.selectedEffects());
        editor.dragAt(editor.columnX(3.5), gridY + row * 0.5, editor.columnX(5.0), gridY + row * 1.5, false);
        check(editor.selectedEffects().equals(Set.of(top, second)), "one over rows 1 and 2 takes both, not the later one");
        editor.call("selectPage");
        check(editor.selectedEffects().equals(Set.of(top, second, later)), "Ctrl+A on the FX page takes the page's effects in the visible rows");
    }

    /** The preview's background colour survives a restart, and a damaged settings file is the default colour. */
    private static void previewBackground() throws Exception {
        Path game = Files.createTempDirectory("blocky-editor-preview-");
        try {
            net.neoforged.fml.loading.FMLPaths.loadAbsolutePaths(game);
            Class<?> preview = Class.forName("ru.gora.musicbattle.client.TrackGameplayPreviewScreen");
            Field cached = preview.getDeclaredField("backgroundColor");
            cached.setAccessible(true);
            Method background = preview.getDeclaredMethod("background");
            background.setAccessible(true);
            Method save = preview.getDeclaredMethod("saveBackground", int.class);
            save.setAccessible(true);
            int fallback = ru.gora.musicbattle.client.ui.Palette.BG_0;
            cached.set(null, null);
            check((int) background.invoke(null) == fallback, "with no settings file the background is the default");
            save.invoke(null, 0x123456);
            cached.set(null, null);
            check((int) background.invoke(null) == 0xFF123456, "a chosen colour is read back after a restart");
            Files.writeString(game.resolve("config").resolve("music_battle-editor-preview.json"), "{\"background\": ");
            cached.set(null, null);
            check((int) background.invoke(null) == fallback, "a damaged settings file is the default colour, not an error");
            cached.set(null, null);
        } finally {
            try (var walk = Files.walk(game)) {
                for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ---- an editor allocated without Minecraft ----------------------------------------------------------------

    /** The real TrackEditorScreen with the fields its constructor would set, at 640x360 GUI pixels. */
    // ---- the shortcut keys, through keyPressed and the mouse handlers --------------------------------------------

    /**
     * The owner works on a Mac and asked for "Ctrl+A". There Screen.hasControlDown() is Cmd only, so physical Ctrl+A,
     * C, V and Z did nothing while a physical Ctrl+click did select. Now Ctrl works on every system and Cmd too on a
     * Mac. This drives the screen's real keyPressed, mouseClicked, mouseDragged and mouseReleased with the keyboard
     * stubbed per system. On a Mac a physical Ctrl+left press reaches a screen as the right button (MouseHandler's fake
     * right click), so that is what the Mac Ctrl drag sends here.
     */
    private static void shortcutRouting() throws Exception {
        int ctrl = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
        int rightCtrl = org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL;
        int cmd = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
        int shift = org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
        int left = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
        int right = org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        try {
            for (boolean mac : new boolean[] {false, true}) {
                String system = mac ? "Mac" : "Windows/Linux";
                int[][] selecting = mac ? new int[][] {{ctrl}, {rightCtrl}, {cmd}} : new int[][] {{ctrl}, {rightCtrl}};
                for (int[] held : selecting) {
                    String keys = system + " " + keyName(held[0]);
                    Editor editor = new Editor("120", 0);
                    BattleNote a = editor.put(1, 0, 0, 0);
                    BattleNote b = editor.put(2, 1, 0, 0);
                    BattleNote c = editor.put(5, 2, 0, 2);
                    editor.put(3, 1, 1, 0);
                    hold(mac, held);
                    check(editor.key(org.lwjgl.glfw.GLFW.GLFW_KEY_A), keys + "+A is taken by the editor");
                    check(editor.selectedNotes().equals(Set.of(a, b, c)), keys + "+A selects the page: " + editor.selectedNotes());
                    check(editor.key(org.lwjgl.glfw.GLFW.GLFW_KEY_C), keys + "+C is taken");
                    int before = editor.notes().size();
                    check(editor.key(org.lwjgl.glfw.GLFW.GLFW_KEY_V), keys + "+V is taken");
                    check(editor.notes().size() == before + 3, keys + "+V pastes the copied three: " + editor.notes().size());
                    check(editor.key(org.lwjgl.glfw.GLFW.GLFW_KEY_Z), keys + "+Z is taken");
                    check(editor.notes().size() == before, keys + "+Z undoes the paste");
                    hold(mac, held[0], shift);
                    check(editor.key(org.lwjgl.glfw.GLFW.GLFW_KEY_Z), keys + "+Shift+Z is taken");
                    check(editor.notes().size() == before + 3, keys + "+Shift+Z redoes the paste");

                    // The Ctrl drag through the mouse handlers: Ctrl (on a Mac also Cmd) with the left button, and on a
                    // Mac physical Ctrl arriving as the right button, as MouseHandler hands it over.
                    Editor grid = new Editor("120", 0);
                    BattleNote p = grid.put(2, 1, 0, 0);
                    BattleNote q = grid.put(3, 2, 0, 0);
                    BattleNote far = grid.put(10, 2, 0, 0);
                    hold(mac, held);
                    int button = mac && held[0] != cmd ? right : left;
                    grid.mouseDrag(1.5, 1, 4.5, 2, button);
                    check(grid.selectedNotes().equals(Set.of(p, q)), keys + " drag (button " + button + ") selects the rectangle: "
                            + grid.selectedNotes());
                    check(grid.notes().size() == 3, keys + " drag adds no note");
                    hold(mac, held[0], shift);
                    grid.mouseDrag(9.5, 2, 10.5, 2, button);
                    check(grid.selectedNotes().equals(Set.of(p, q, far)), keys + "+Shift drag adds to the selection");
                    hold(mac, held);
                    grid.mouseClick(2, 1, button);
                    check(grid.selectedNotes().equals(Set.of(q, far)), keys + " click toggles the note under it");
                    hold(mac, held);
                    check(grid.key(org.lwjgl.glfw.GLFW.GLFW_KEY_Z), keys + "+Z after a selection is taken");
                    check(grid.selectedNotes().equals(Set.of(p, q, far)), keys + "+Z brings the selection back");
                }

                // What is not a shortcut stays what it was.
                Editor plain = new Editor("120", 0);
                BattleNote n = plain.put(1, 0, 0, 0);
                hold(mac);
                check(!plain.key(org.lwjgl.glfw.GLFW.GLFW_KEY_A) && plain.selectedNotes().isEmpty(), system + " A alone selects nothing");
                if (!mac) {
                    hold(false, cmd);
                    check(!plain.key(org.lwjgl.glfw.GLFW.GLFW_KEY_A) && plain.selectedNotes().isEmpty(),
                            "the Windows key is not Ctrl, as in vanilla");
                }
                hold(mac);
                plain.mouseClick(1, 0, left);
                check(plain.selectedNotes().equals(Set.of(n)) && plain.notes().equals(List.of(n)),
                        system + " a plain click on a note selects that note, as before");
                plain.mouseDrag(6.5, 1, 8.5, 2, left);
                check(plain.selectedNotes().equals(Set.of(n)) && plain.notes().equals(List.of(n)),
                        system + " a plain drag over empty grid neither selects nor places");
                hold(mac, shift);
                check(!plain.key(org.lwjgl.glfw.GLFW.GLFW_KEY_Z) && plain.notes().equals(List.of(n)), system + " Shift+Z is no undo");
            }
        } finally {
            ru.gora.musicbattle.client.EditorShortcuts.stubForChecks(false, null);
        }
        // Code only: the comments explain what Screen.hasControlDown() means on a Mac.
        String comments = "(?s)/\\*.*?\\*/|//[^\\n]*";
        String editorSource = Files.readString(Path.of("src/main/java/ru/gora/musicbattle/client/TrackEditorScreen.java"))
                .replaceAll(comments, "");
        String fxSource = Files.readString(Path.of("src/main/java/ru/gora/musicbattle/client/TrackEffectEditScreen.java"))
                .replaceAll(comments, "");
        check(!editorSource.contains("hasControlDown()") && !fxSource.contains("hasControlDown()"),
                "the chart editor and the FX editor take their Ctrl through EditorShortcuts, not Screen.hasControlDown()");
        check(fxSource.contains("EditorShortcuts.shortcutDown() && keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_Z"),
                "the FX editor's Ctrl+Z takes Ctrl and Cmd as the chart editor does");
    }

    private static void hold(boolean mac, int... keys) {
        Set<Integer> down = new HashSet<>();
        for (int key : keys) {
            down.add(key);
        }
        ru.gora.musicbattle.client.EditorShortcuts.stubForChecks(mac, down::contains);
    }

    private static String keyName(int key) {
        return switch (key) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL -> "Ctrl";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL -> "right Ctrl";
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER -> "Cmd";
            default -> "key " + key;
        };
    }

    private static final class Editor {
        private static final Class<?> TYPE = TrackEditorScreen.class;
        private final Object screen;

        Editor(String bpm, int gridDivision) throws Exception {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            screen = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(TYPE);
            Field width = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("width");
            width.setAccessible(true);
            width.set(screen, 640);
            Field height = net.minecraft.client.gui.screens.Screen.class.getDeclaredField("height");
            height.setAccessible(true);
            height.set(screen, 360);
            for (String list : new String[] {"children", "narratables", "renderables"}) {
                Field children = net.minecraft.client.gui.screens.Screen.class.getDeclaredField(list);
                children.setAccessible(true);
                children.set(screen, new ArrayList<>());
            }
            set("notes", new ArrayList<BattleNote>());
            set("effects", new ArrayList<TrackEffect>());
            set("selectedNotes", new HashSet<BattleNote>());
            set("selectedEffects", new HashSet<TrackEffect>());
            set("undoHistory", new ArrayDeque<>());
            set("redoHistory", new ArrayDeque<>());
            set("cachedPageNotes", List.of());
            set("cachedPageEffects", List.of());
            set("cachedTimelineRevision", -1);
            set("cachedPageStartTick", -1);
            set("cachedPageTicks", -1);
            set("cachedFirstVisibleFxRow", -1);
            set("previewNoteQueue", List.of());
            set("clipboard", ChartEdits.Clipboard.EMPTY);
            set("status", "");
            set("draggedEffectIndex", -1);
            set("pendingEffectStartTick", -1);
            set("pendingEffectLane", -1);
            set("shownOffsetMs", -1);
            Class<?> page = Class.forName(TYPE.getName() + "$EditorPage");
            set("editorPage", page.getEnumConstants()[0]);
            Class<?> mode = Class.forName(TYPE.getName() + "$DragMode");
            set("noteDragMode", mode.getEnumConstants()[0]);
            set("effectDragMode", mode.getEnumConstants()[0]);
            set("draftBpm", bpm);
            set("gridDivisionIndex", gridDivision);
            set("pageStartTick", 0);
        }

        @SuppressWarnings("unchecked")
        List<BattleNote> notes() throws Exception {
            return (List<BattleNote>) field("notes");
        }

        @SuppressWarnings("unchecked")
        Set<BattleNote> selectedNotes() throws Exception {
            return (Set<BattleNote>) field("selectedNotes");
        }

        @SuppressWarnings("unchecked")
        List<TrackEffect> effects() throws Exception {
            return (List<TrackEffect>) field("effects");
        }

        @SuppressWarnings("unchecked")
        Set<TrackEffect> selectedEffects() throws Exception {
            return (Set<TrackEffect>) field("selectedEffects");
        }

        /** Whether a line of the page shown lies in the song, by the editor's own test for the lead-in. */
        boolean columnTickInSong(int column) throws Exception {
            return !(boolean) call("columnBeforeSong", column);
        }

        int undoSteps() throws Exception {
            return ((Collection<?>) field("undoHistory")).size();
        }

        /** A note on a line of the first page, straight into the chart. */
        BattleNote put(int column, int lane, int owner, int lines) throws Exception {
            int tick = (int) call("gridTickAtColumn", column);
            int duration = lines == 0 ? 0 : (int) call("gridTickAtColumn", column + lines) - tick;
            BattleNote note = new BattleNote(owner, lane, tick, duration);
            notes().add(note);
            call("invalidateTimelineCache");
            return note;
        }

        int lineX(int column) throws Exception {
            Object grid = call("grid");
            return (int) call("gridLineX", grid, column / (double) (int) call("gridColumns"));
        }

        double columnX(double column) throws Exception {
            Object grid = call("grid");
            return (int) record(grid, "x") + column / (int) call("gridColumns") * (int) record(grid, "width");
        }

        int laneHeight() throws Exception {
            return Math.max(1, (int) record(call("grid"), "height") / 4);
        }

        int laneTop(int lane) throws Exception {
            return (int) record(call("grid"), "y") + lane * laneHeight();
        }

        /** A left click a pixel right of a line, in the middle of a lane, through the grid's own click. */
        void click(int column, int lane, int button) throws Exception {
            call("toggleGridNote", lineX(column) + 1.0D, laneTop(lane) + laneHeight() / 2.0D, button);
        }

        /** A Ctrl drag from the middle of one lane to the middle of another, at fractional columns. */
        void drag(double fromColumn, int fromLane, double toColumn, int toLane, boolean shift) throws Exception {
            dragAt(columnX(fromColumn), laneTop(fromLane) + laneHeight() / 2.0D,
                    columnX(toColumn), laneTop(toLane) + laneHeight() / 2.0D, shift);
        }

        /** A Ctrl drag between two points, released through the screen's own end of a selection press. */
        void dragAt(double fromX, double fromY, double toX, double toY, boolean shift) throws Exception {
            set("selectPressPending", true);
            set("selectDragging", true);
            set("selectAdds", shift);
            set("selectPressX", fromX);
            set("selectPressY", fromY);
            set("selectionBefore", call("snapshotTimeline"));
            call("finishSelectionPress", toX, toY);
        }

        /** A key press through the screen's own keyPressed, with the modifiers GLFW would report for the stubbed keys. */
        boolean key(int keyCode) {
            int mods = (ru.gora.musicbattle.client.EditorShortcuts.controlDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL : 0)
                    | (ru.gora.musicbattle.client.EditorShortcuts.shiftDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT : 0);
            return ((TrackEditorScreen) screen).keyPressed(keyCode, 0, mods);
        }

        /** Press, drag in steps and release through mouseClicked, mouseDragged and mouseReleased. */
        void mouseDrag(double fromColumn, int fromLane, double toColumn, int toLane, int button) throws Exception {
            TrackEditorScreen editor = (TrackEditorScreen) screen;
            double fromX = columnX(fromColumn);
            double fromY = laneTop(fromLane) + laneHeight() / 2.0D;
            double toX = columnX(toColumn);
            double toY = laneTop(toLane) + laneHeight() / 2.0D;
            try {
                editor.mouseClicked(fromX, fromY, button);
                double lastX = fromX;
                double lastY = fromY;
                for (int step = 1; step <= 4; step++) {
                    double x = fromX + (toX - fromX) * step / 4.0D;
                    double y = fromY + (toY - fromY) * step / 4.0D;
                    editor.mouseDragged(x, y, button, x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                }
                editor.mouseReleased(toX, toY, button);
            } catch (RuntimeException outsideTheSelection) {
                // Only the paths a selection takes run without a game; anything else reaching for Minecraft is a miss.
                check(false, "a drag with button " + button + " left the selection path: " + outsideTheSelection);
            }
        }

        /** A still click a pixel right of a line, through mouseClicked and mouseReleased. */
        void mouseClick(int column, int lane, int button) throws Exception {
            TrackEditorScreen editor = (TrackEditorScreen) screen;
            double x = lineX(column) + 1.0D;
            double y = laneTop(lane) + laneHeight() / 2.0D;
            try {
                editor.mouseClicked(x, y, button);
                editor.mouseReleased(x, y, button);
            } catch (RuntimeException outsideTheSelection) {
                check(false, "a click with button " + button + " left the selection path: " + outsideTheSelection);
            }
        }

        Object field(String name) throws Exception {
            Field field = TYPE.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(screen);
        }

        void set(String name, Object value) throws Exception {
            Field field = TYPE.getDeclaredField(name);
            field.setAccessible(true);
            field.set(screen, value);
        }

        Object call(String name, Object... args) throws Exception {
            for (Method method : TYPE.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    return method.invoke(screen, args);
                } catch (IllegalArgumentException wrongOverload) {
                    continue;
                } catch (InvocationTargetException failure) {
                    throw new AssertionError(name + " threw " + failure.getCause(), failure.getCause());
                }
            }
            throw new AssertionError("no method " + name + " taking " + args.length + " arguments");
        }
    }

    private static Object record(Object record, String component) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(component);
        accessor.setAccessible(true);
        return accessor.invoke(record);
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures.add(message);
        }
    }
}
