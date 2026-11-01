package ru.gora.musicbattle.data;

import java.util.HashSet;
import java.util.Set;

/**
 * The modes a battle against the test NPC plays with: shared notes, the deadly party and the speed.
 *
 * <p>The NPC's "random modes" switch makes it roll modes of its own, and they used to count only where the player had
 * picked the very same mode: shared notes needed both, the speed needed both to name the same one. The setup screen
 * treats the NPC as a mob, though - it never shows the NPC's choice and its button says "Start" - so the roll could
 * only ever take away a mode the player had asked for, and never add one. With the switch on, a battle against the
 * NPC therefore looked like a battle against anything else, and with no server tracks the NPC did not even vote, so
 * every mode the player picked was turned down.
 *
 * <p>Now the player's own choices always hold, as they do against any mob, and the NPC's roll adds to them: shared
 * notes if either side wants them, and the NPC's speed when the player left it at normal. The deadly party is never
 * the NPC's to decide: it kills whoever loses, so only the player can ask for it.
 *
 * <p>Pure, so tools/BattleRulesChecks.java checks the rule without a server.
 */
public final class BattleModeVote {
    private BattleModeVote() {
    }

    /** One side's choice of modes. */
    public record Modes(boolean sharedNotes, boolean deadlyParty, float speed) {
        public static final Modes PLAIN = new Modes(false, false, 1.0F);

        public Modes {
            speed = normalizeSpeed(speed);
        }
    }

    /**
     * Everyone who has chosen so far, for closing the vote early: those who voted for a track, and the test NPC once it
     * has rolled its modes, whether or not the server had a track for it to name. Counting track votes alone kept a
     * battle whose maps all come from the Workshop waiting out the whole minute for an NPC with nothing to name.
     */
    public static <T> Set<T> votersSoFar(Set<T> trackVoters, T npc, boolean npcHasRolled) {
        if (npc == null || !npcHasRolled || trackVoters.contains(npc)) {
            return trackVoters;
        }
        Set<T> voters = new HashSet<>(trackVoters);
        voters.add(npc);
        return voters;
    }

    /** The modes of a battle between a player and the test NPC; {@code npc} is null until the NPC has voted. */
    public static Modes againstTestNpc(Modes player, Modes npc, boolean npcRollsModes) {
        Modes own = player == null ? Modes.PLAIN : player;
        if (!npcRollsModes || npc == null) {
            return own;
        }
        boolean shared = own.sharedNotes() || npc.sharedNotes();
        float speed = own.speed() > 1.01F ? own.speed() : npc.speed();
        return new Modes(shared, own.deadlyParty(), speed);
    }

    /**
     * The modes of a mob battle a player organised between two mobs, either or both of them the test NPC. The
     * organiser's choices hold, as they do against any mob, and each NPC whose switch is on adds its roll to them the
     * way it does against a player ({@link #againstTestNpc}): shared notes if anyone wants them, the first NPC's speed
     * when the organiser left it at normal, and never the deadly party. The NPC's settings tip promises exactly this -
     * "In a battle the NPC picks modes itself - shared notes and the speed - and adds them to yours" - and a battle
     * between two NPCs used to read the organiser's votes alone, so their rolls never counted. A null roll is a mob
     * that rolled nothing: an ordinary mob, or an NPC with its switch off.
     */
    public static Modes betweenMobs(Modes organiser, Modes leftRoll, Modes rightRoll) {
        Modes modes = organiser == null ? Modes.PLAIN : organiser;
        if (leftRoll != null) {
            modes = againstTestNpc(modes, leftRoll, true);
        }
        if (rightRoll != null) {
            modes = againstTestNpc(modes, rightRoll, true);
        }
        return modes;
    }

    /**
     * Which side the "rolled extra modes" chat line names its NPC by: 0 for none in a battle against a player, where
     * the NPC is the only one, and Player 1 (left) or Player 2 (right) in a battle between two mobs. Two test NPCs carry
     * the same name, so the organiser could not tell which of them had rolled what.
     */
    public static int announcedSide(boolean battleHasPlayer, boolean npcOnLeft) {
        return battleHasPlayer ? 0 : npcOnLeft ? 1 : 2;
    }

    /**
     * What the NPC rolls when its switch is on: shared notes on a coin toss and one of the three speeds, each a third
     * of the time. Never the deadly party. {@code coin} and {@code speedRoll} are the server's random draws.
     */
    public static Modes npcRoll(boolean npcRollsModes, boolean coin, int speedRoll) {
        if (!npcRollsModes) {
            return Modes.PLAIN;
        }
        float speed = switch (Math.floorMod(speedRoll, 3)) {
            case 1 -> 1.5F;
            case 2 -> 2.0F;
            default -> 1.0F;
        };
        return new Modes(coin, false, speed);
    }

    /**
     * Whether the deadly party kills its loser. It always did, by a kind of damage that passes the invulnerable flag,
     * so a test NPC its owner had switched to "Invulnerable" died like any other loser. That switch now wins: an
     * invulnerable test NPC loses the party and lives. Every other loser, players and ordinary mobs, still dies.
     */
    public static boolean deadlyPartyKills(boolean loserIsTestNpc, boolean loserInvulnerable) {
        return !(loserIsTestNpc && loserInvulnerable);
    }

    /** The three speeds a battle offers, as the vote screen and the server name them. */
    public static float normalizeSpeed(float multiplier) {
        if (multiplier >= 1.9F) {
            return 2.0F;
        }
        if (multiplier >= 1.4F) {
            return 1.5F;
        }
        return 1.0F;
    }
}
