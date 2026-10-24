package ru.gora.musicbattle.client;

import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import ru.gora.musicbattle.MusicBattleMod;

public final class ClientPlayerAnimatorBridge {
    private static final ResourceLocation LAYER_ID = MusicBattleMod.id("battle_player_animation");
    private static final Map<AbstractClientPlayer, ClientBattleAnimation.Holder> LAYERS = new WeakHashMap<>();
    private static boolean registered;

    private ClientPlayerAnimatorBridge() {
    }

    public static void register(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (registered) {
                return;
            }
            registered = true;
            PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER_ID, 700, player -> {
                ClientBattleAnimation.Holder holder = ClientBattleAnimation.createLayer();
                LAYERS.put(player, holder);
                return holder.layer;
            });
            MusicBattleMod.LOGGER.info("Registered Blocky Music player animation layer");
        });
    }

    public static boolean updateBattleAnimation(AbstractClientPlayer player, int triggerSerial, int triggerLane, int heldLaneMask, int bpm, float battleTick) {
        return updateBattleAnimation(player, triggerSerial, triggerLane, heldLaneMask, bpm, battleTick, Float.NaN);
    }

    public static boolean updateBattleAnimation(AbstractClientPlayer player, int triggerSerial, int triggerLane,
                                                int heldLaneMask, int bpm, float battleTick,
                                                float triggerTick) {
        ClientBattleAnimation.Holder holder = LAYERS.get(player);
        if (holder == null) {
            holder = ClientBattleAnimation.createLayer();
            try {
                PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(700, holder.layer);
                LAYERS.put(player, holder);
                MusicBattleMod.LOGGER.info("Attached Blocky Music animation layer to player {}", player.getName().getString());
            } catch (IllegalArgumentException exception) {
                MusicBattleMod.LOGGER.error("Player Animator did not expose an animation stack for {}", player.getName().getString(), exception);
                return false;
            }
        }
        return ClientBattleAnimation.update(holder, triggerSerial, triggerLane, heldLaneMask, bpm,
                battleTick, triggerTick);
    }

    public static void stopBattleAnimation(AbstractClientPlayer player) {
        ClientBattleAnimation.Holder holder = LAYERS.get(player);
        if (holder == null) {
            return;
        }
        ClientBattleAnimation.stop(holder);
    }

    public static void stopAllBattleAnimations() {
        for (ClientBattleAnimation.Holder holder : LAYERS.values()) {
            ClientBattleAnimation.stop(holder);
        }
    }
}
