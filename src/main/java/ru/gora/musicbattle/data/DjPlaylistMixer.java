package ru.gora.musicbattle.data;

import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;

/** Entry-owned controls; deliberately excludes the server-owned chart snapshot. */
public record DjPlaylistMixer(boolean trackFxEnabled, boolean djFxEnabled, List<DjVisualEffectConfig> effects) {
    public DjPlaylistMixer {
        if (effects != null && effects.size() > DjVisualEffectConfig.MAX_CONFIGS)
            throw new IllegalArgumentException("Too many playlist mixer effects");
        effects = DjVisualEffects.sanitizeConfigs(effects);
    }
    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeBoolean(trackFxEnabled); buffer.writeBoolean(djFxEnabled);
        DjVisualEffectConfig.writeList(buffer, effects);
    }
    public static DjPlaylistMixer read(RegistryFriendlyByteBuf buffer) {
        return new DjPlaylistMixer(buffer.readBoolean(), buffer.readBoolean(), DjVisualEffectConfig.readList(buffer));
    }
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("TrackEnabled", trackFxEnabled); tag.putBoolean("DjEnabled", djFxEnabled);
        tag.putString("Configs", DjVisualEffectConfig.encodeList(effects));
        return tag;
    }
    public static DjPlaylistMixer fromTag(CompoundTag tag) {
        return new DjPlaylistMixer(tag.getBoolean("TrackEnabled"), tag.getBoolean("DjEnabled"),
                DjVisualEffectConfig.decodeList(tag.getString("Configs")));
    }
}
