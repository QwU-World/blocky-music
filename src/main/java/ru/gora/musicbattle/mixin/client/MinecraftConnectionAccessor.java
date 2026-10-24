package ru.gora.musicbattle.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of the connection a server login is using, so Workshop proofs never race vanilla's join. */
@Mixin(Minecraft.class)
public interface MinecraftConnectionAccessor {
    @Accessor("pendingConnection")
    Connection musicBattle$pendingConnection();
}
