package ru.gora.musicbattle.server;

import net.minecraft.world.entity.Entity;

/** Identifies disposable helper entities so crash-saved leftovers are never restored. */
public final class TemporaryBattleEntities {
    private static final String TAG = "music_battle.temporary_helper";

    private TemporaryBattleEntities() { }

    public static <T extends Entity> T mark(T entity) {
        entity.addTag(TAG);
        return entity;
    }

    public static boolean isMarked(Entity entity) {
        return entity.getTags().contains(TAG);
    }
}
