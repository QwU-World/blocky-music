package ru.gora.musicbattle.block;

import net.minecraft.core.BlockPos;

/**
 * The DJ booth this client last right-clicked, so a panel answer the player never asked for cannot open the panel.
 *
 * <p>The server answers every panel action and the panel's own once-a-second refresh with the whole panel
 * ({@code OpenDjPanelPayload}), and the client used to open the panel for any such answer when none was open. An
 * answer still on its way when the player pressed Esc - the refresh, a Play click, audio that finished preparing -
 * put the panel back on screen a moment after it closed; on a server with some ping that happened every few closes,
 * and a hidden dedicated-server run showed it on the first try. Now only a right-click opens the panel: the click is
 * noted here on the client ({@link DjPanelBlock}), and the answer that opens the panel takes the note. Plain Java, so
 * the shared block class may call it on either side.
 */
public final class DjPanelOpenRequests {
    /** Long enough for the answer to a click on a slow server; a stale note can only open the booth it names. */
    static final long WINDOW_NANOS = 30_000_000_000L;
    private static BlockPos requested;
    private static long requestedAtNanos;

    private DjPanelOpenRequests() {
    }

    /** The player right-clicked this booth. */
    public static synchronized void requested(BlockPos pos) {
        requested = pos == null ? null : pos.immutable();
        requestedAtNanos = System.nanoTime();
    }

    /** Whether a panel answer for this booth may open the panel; a yes uses the note up. */
    public static synchronized boolean consume(BlockPos pos) {
        return consume(pos, System.nanoTime());
    }

    static synchronized boolean consume(BlockPos pos, long nowNanos) {
        boolean asked = requested != null && requested.equals(pos) && nowNanos - requestedAtNanos <= WINDOW_NANOS;
        if (asked) {
            requested = null;
        }
        return asked;
    }
}
