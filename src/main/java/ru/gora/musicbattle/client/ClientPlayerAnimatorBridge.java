package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import ru.gora.musicbattle.MusicBattleMod;

/**
 * Puts the battle poses on players through Player Animator.
 *
 * <p>Each player object gets its own battle layer when it is created, and the layer is found again through that same
 * object. It used to be kept in a map keyed by the player, but Minecraft compares entities by their network id
 * alone. A player who respawns or changes dimension is a new object with the old id, and so is a player who walks
 * out of view and back: until the garbage collector cleared the old object, the map handed back the old layer, which
 * belonged to the old object's animation stack, and the battle animated a body no longer on screen while the one on
 * screen stood still. In a hidden two-player test, for the first 11 seconds of the battle after a death, the player
 * who had died stood still on both screens, and on that player's own screen so did the opponent, whom the respawn had
 * also made a new object; how long it lasts depends only on when the collector runs. The same map lost every layer it was given at creation, because the client sets the player's id only after
 * constructing it, which is why each session logged a layer being attached a second time.
 */
public final class ClientPlayerAnimatorBridge {
    private static final ResourceLocation LAYER_ID = MusicBattleMod.id("battle_player_animation");
    private static final int LAYER_PRIORITY = 700;
    /**
     * Every holder made for a player, only to stop them all at once; holders are found through their players, never
     * here. Holders compare by identity, and the set holds them weakly: a player that is gone takes its holder along.
     */
    private static final Set<ClientBattleAnimation.Holder> HOLDERS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean registered;
    private static boolean missingStackLogged;

    private ClientPlayerAnimatorBridge() {
    }

    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (registered) {
                return;
            }
            registered = true;
            // Player Animator calls this while it builds each client player and keeps the layer under LAYER_ID on
            // that player, where holderFor finds it again.
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, LAYER_PRIORITY, player -> {
                ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
                HOLDERS.add(holder);
                return holder.layer;
            });
            MusicBattleMod.LOGGER.info("Registered Blocky Music player animation layer");
        });
    }

    /** The editor preview, where every trigger is a key going down and nothing is judged. */
    public static boolean updateBattleAnimation(AbstractClientPlayer player, int triggerSerial, int triggerLane, int heldLaneMask, int bpm, float battleTick) {
        ClientBattleAnimation.Holder holder = holderFor(player, true);
        return holder != null
                && ClientBattleAnimation.update(holder, triggerSerial, triggerLane, heldLaneMask, bpm, battleTick);
    }

    /** A battle participant's presses and, apart from them, the judgements of its key presses. */
    public static boolean updateBattleAnimation(AbstractClientPlayer player, int pressSerial, int pressLane,
                                                float pressTick, int judgedSerial, int judgedLane, float judgedTick,
                                                int heldLaneMask, int bpm, float battleTick) {
        ClientBattleAnimation.Holder holder = holderFor(player, true);
        return holder != null && ClientBattleAnimation.update(holder, pressSerial, pressLane, pressTick,
                judgedSerial, judgedLane, judgedTick, heldLaneMask, bpm, battleTick);
    }

    public static void stopBattleAnimation(AbstractClientPlayer player) {
        ClientBattleAnimation.Holder holder = holderFor(player, false);
        if (holder != null) {
            ClientBattleAnimation.stop(holder);
        }
    }

    public static void stopAllBattleAnimations() {
        // A copy: nothing here should be able to trip over the weak set dropping a holder midway.
        for (ClientBattleAnimation.Holder holder : new ArrayList<>(HOLDERS)) {
            ClientBattleAnimation.stop(holder);
        }
    }

    /**
     * This player object's own holder. A player built before the factory was registered, or by something that skips
     * Player Animator's set-up, gets a layer attached here instead, kept on the player the same way.
     */
    private static ClientBattleAnimation.Holder holderFor(AbstractClientPlayer player, boolean attach) {
        PlayerAnimationAccess.PlayerAssociatedAnimationData data;
        try {
            data = PlayerAnimationAccess.getPlayerAssociatedData(player);
        } catch (IllegalArgumentException exception) {
            logMissingStack(player, exception);
            return null;
        }
        ClientBattleAnimation.Holder holder = ClientBattleAnimation.holderOf(data.get(LAYER_ID));
        if (holder != null || !attach) {
            return holder;
        }
        holder = ClientBattleAnimation.createLayer();
        try {
            PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(LAYER_PRIORITY, holder.layer);
        } catch (IllegalArgumentException exception) {
            logMissingStack(player, exception);
            return null;
        }
        data.set(LAYER_ID, holder.layer);
        HOLDERS.add(holder);
        MusicBattleMod.LOGGER.info("Attached Blocky Music animation layer to player {}", player.getName().getString());
        return holder;
    }

    private static void logMissingStack(AbstractClientPlayer player, IllegalArgumentException exception) {
        // Asked for on every frame the player is drawn: said once, not sixty times a second.
        if (!missingStackLogged) {
            missingStackLogged = true;
            MusicBattleMod.LOGGER.error("Player Animator did not expose an animation stack for {}",
                    player.getName().getString(), exception);
        }
    }
}
