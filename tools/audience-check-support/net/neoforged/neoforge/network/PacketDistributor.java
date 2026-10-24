package net.neoforged.neoforge.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Test-only connection boundary; real gallery, panel, and payload construction stay in use. */
public final class PacketDistributor {
    public static final List<CustomPacketPayload> SENT = new ArrayList<>();
    public static void sendToServer(CustomPacketPayload payload, CustomPacketPayload... others) {
        SENT.add(payload);
        SENT.addAll(List.of(others));
    }
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload, CustomPacketPayload... others) {
        throw new AssertionError("No server player network is part of this headless fixture");
    }
}
