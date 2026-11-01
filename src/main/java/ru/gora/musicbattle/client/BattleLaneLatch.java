package ru.gora.musicbattle.client;

import java.util.Arrays;

/**
 * Counts the lane keys that went down between two client ticks, so that every press reaches the battle exactly once.
 *
 * <p>The battle reads its lane keys once per client tick, every 50 ms, but a key changes state the moment it is
 * pressed. A quick tap whose key went down and came back up between two ticks used to go unseen: the tick found the
 * key up, as it had been, so no input went to the server, the note counted as missed and the body did not move. Key
 * events now count each press here as it happens ({@link #keyChanged}), and the next tick sends every press it finds
 * ({@link #drain}): as a key going down and, if it has come back up since, going up again. A key still held at the
 * tick is not sent twice, and a key the tick finds down with no press counted, one that went down before the battle
 * took over the keys, is still a press, as it always was.
 *
 * <p>Pure on purpose: the battle input checks run it without Minecraft.
 */
final class BattleLaneLatch {
    /** The battle's own lanes, fed by ClientInputIsolation and read by ClientBattleController. */
    static final BattleLaneLatch LANES = new BattleLaneLatch();
    /**
     * No one presses one key more than this often in 50 ms; anything beyond it is a broken key or a stuck event, and
     * sending it would only flood the server with lane changes.
     */
    static final int MAX_PRESSES_PER_TICK = 4;
    private final int[] presses = new int[4];

    /** A lane key's event: it was down or not before the event, and is now. Only a key going down is a press. */
    void keyChanged(int lane, boolean wasDown, boolean down) {
        if (lane < 0 || lane >= presses.length || !down || wasDown) {
            return;
        }
        if (presses[lane] < MAX_PRESSES_PER_TICK) {
            presses[lane]++;
        }
    }

    /**
     * How many times the lane changes state this tick, given what was last sent for it and whether its key is down
     * now; the count it had collected starts again from zero. The changes alternate and start with the opposite of
     * {@code sentDown}, so they end at {@code downNow}.
     */
    int drain(int lane, boolean sentDown, boolean downNow) {
        if (lane < 0 || lane >= presses.length) {
            return 0;
        }
        int counted = presses[lane];
        presses[lane] = 0;
        return edges(sentDown, counted, downNow);
    }

    /** Forgets every press counted, for the ticks in which lane keys are not battle input. */
    void clear() {
        Arrays.fill(presses, 0);
    }

    /**
     * Lane changes for one tick: one key-down per press, with a key-up before each press but the first (and before
     * the first, too, if the lane was sent as down) and one after the last if the key is up now.
     */
    static int edges(boolean sentDown, int presses, boolean downNow) {
        int downs = Math.max(0, Math.min(presses, MAX_PRESSES_PER_TICK));
        if (downs == 0 && downNow && !sentDown) {
            // Down with no press counted: it went down while the battle was not reading key events.
            downs = 1;
        }
        int ups = downs + (sentDown ? 1 : 0) - (downNow ? 1 : 0);
        return downs + Math.max(0, ups);
    }
}
