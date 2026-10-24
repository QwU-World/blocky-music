package ru.gora.musicbattle.client;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import ru.gora.musicbattle.registry.ModItems;

/**
 * Which battle fighters are drawn holding a microphone, for the held-item mixins.
 *
 * <p>Purely a matter of drawing: the fighter's real item stays where it is, so a crash or a lost packet mid-battle
 * cannot lose or duplicate it, and when the battle ends the real item simply reappears. The server repeats the
 * marker every second and each one lasts a few, so a missed "stop" cannot leave a microphone behind for long.
 */
public final class ClientBattleMicrophone {
    /** Three missed repeats in a row, then the real item shows again. */
    private static final long LAPSE_NANOS = 3_000_000_000L;
    private static final Int2LongOpenHashMap HOLDING_UNTIL = new Int2LongOpenHashMap();
    private static Level level;
    private static ItemStack microphone;

    private ClientBattleMicrophone() {
    }

    public static void accept(int entityId, boolean holding) {
        Level current = Minecraft.getInstance().level;
        if (current != level) {
            // Entity ids are only unique within one level, so a new world or dimension starts from nothing.
            HOLDING_UNTIL.clear();
            level = current;
        }
        if (holding) {
            HOLDING_UNTIL.put(entityId, System.nanoTime() + LAPSE_NANOS);
        } else {
            HOLDING_UNTIL.remove(entityId);
        }
    }

    /** The stack to draw in {@code entity}'s main hand in place of {@code held}. */
    public static ItemStack mainHand(Entity entity, ItemStack held) {
        if (entity == null || HOLDING_UNTIL.isEmpty() || entity.level() != level) {
            return held;
        }
        long until = HOLDING_UNTIL.getOrDefault(entity.getId(), 0L);
        if (until == 0L) {
            return held;
        }
        if (until - System.nanoTime() < 0L) {
            HOLDING_UNTIL.remove(entity.getId());
            return held;
        }
        // A fighter who already holds a microphone keeps their own, bound name and all.
        if (held.is(ModItems.MICROPHONE.get())) {
            return held;
        }
        if (microphone == null) {
            microphone = new ItemStack(ModItems.MICROPHONE.get());
        }
        return microphone;
    }

    public static boolean holdsMicrophone(Entity entity) {
        return mainHand(entity, ItemStack.EMPTY) != ItemStack.EMPTY;
    }
}
