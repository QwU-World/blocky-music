package ru.gora.musicbattle.data;

import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** One stable, source-time-addressed entry in a persistent DJ playlist. */
public record DjPlaylistEntry(UUID entryId, String trackId, int startSourceTicks, FxMode fxMode, List<TrackEffect> effects,
                              DjPlaylistMixer mixer, List<TrackEffect> mixerChartEffects, boolean mixerChartCaptured) {
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_TRACK_ID_LENGTH = 255;

    public DjPlaylistEntry {
        if (entryId == null) {
            throw new IllegalArgumentException("DJ playlist entry id is required");
        }
        trackId = trackId == null ? "" : trackId.strip();
        if (trackId.isEmpty() || trackId.length() > MAX_TRACK_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid DJ playlist track id");
        }
        startSourceTicks = Math.max(0, startSourceTicks);
        fxMode = fxMode == null ? FxMode.INHERIT : fxMode;
        effects = effects == null ? List.of() : List.copyOf(effects);
        if (effects.size() > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Too many playlist effects");
        mixerChartEffects = mixerChartEffects == null ? List.of() : List.copyOf(mixerChartEffects);
        if (mixerChartEffects.size() > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Too many mixer chart effects");
        if (fxMode != FxMode.DJ) { mixer = null; mixerChartEffects = List.of(); mixerChartCaptured = false; }
        else if (mixer == null) {
            mixer = new DjPlaylistMixer(false, true, effects.stream().limit(DjVisualEffectConfig.MAX_CONFIGS)
                    .map(effect -> new DjVisualEffectConfig("entry", true, effect)).toList());
        }
    }

    public DjPlaylistEntry(UUID id, String track, int cue, FxMode mode, List<TrackEffect> effects) {
        this(id, track, cue, mode, effects, null, List.of(), false);
    }

    public DjPlaylistEntry(UUID id, String track, int cue) { this(id, track, cue, FxMode.INHERIT, List.of()); }

    public enum FxMode { INHERIT, TRACK, DJ, NONE;
        public static FxMode read(int ordinal) {
            if (ordinal < 0 || ordinal >= values().length) throw new IllegalArgumentException("Invalid playlist FX mode");
            return values()[ordinal];
        }
    }

    public DjPlaylistEntry withEffects(FxMode mode, List<TrackEffect> value) {
        return new DjPlaylistEntry(entryId, trackId, startSourceTicks, mode, mode == FxMode.NONE ? List.of() : value);
    }

    public DjPlaylistEntry withMixer(DjPlaylistMixer value, List<TrackEffect> chart) {
        return new DjPlaylistEntry(entryId, trackId, startSourceTicks, FxMode.DJ,
                value.effects().stream().filter(DjVisualEffectConfig::enabled).map(DjVisualEffectConfig::effect).toList(),
                value, chart, true);
    }

    /** Oversized legacy DJ lists stay intact and use the timeline list editor until reduced. */
    public boolean usesLegacyDjEditor() { return fxMode == FxMode.DJ && effects.size() > DjVisualEffectConfig.MAX_CONFIGS; }

    /** Whole mixer CAS keeps reorder/add/remove/cancel atomic without sending chart data. */
    public DjPlaylistEntry editMixer(DjPlaylistMixer expected, DjPlaylistMixer replacement) {
        return fxMode == FxMode.DJ && !usesLegacyDjEditor() && java.util.Objects.equals(mixer, expected)
                ? withMixer(replacement, mixerChartEffects) : null;
    }

    /** Compare-and-set: a stale inspector cannot overwrite a shifted/replaced FX row. */
    public DjPlaylistEntry editEffect(int index, FxMode expectedMode, int expectedCount,
                                     TrackEffect expected, TrackEffect replacement) {
        if (fxMode == FxMode.INHERIT || fxMode != expectedMode || effects.size() != expectedCount
                || index < 0 || index > effects.size() || index >= TrackEffect.MAX_EFFECTS
                || !java.util.Objects.equals(index < effects.size() ? effects.get(index) : null, expected)) return null;
        List<TrackEffect> next = new ArrayList<>(effects);
        if (replacement == null) {
            if (index >= next.size()) return null;
            next.remove(index);
        } else if (index == next.size()) next.add(replacement);
        else next.set(index, replacement);
        if (fxMode == FxMode.DJ) {
            List<DjVisualEffectConfig> configs;
            if (usesLegacyDjEditor()) {
                configs = next.stream().limit(DjVisualEffectConfig.MAX_CONFIGS)
                        .map(effect -> new DjVisualEffectConfig("entry", true, effect)).toList();
            } else {
                // A still-open legacy/NONE inspector can coexist with the normal mixer.
                // Merge only its CAS-checked enabled row, retaining newer toggles and disabled cards.
                configs = new ArrayList<>(mixer.effects());
                int configIndex = configs.size();
                for (int i=0, enabledRow=0; i<configs.size(); i++) {
                    if (configs.get(i).enabled() && enabledRow++ == index) { configIndex=i; break; }
                }
                if (replacement == null) configs.remove(configIndex);
                else if (configIndex == configs.size()) {
                    if (configs.size() >= DjVisualEffectConfig.MAX_CONFIGS) return null;
                    configs.add(new DjVisualEffectConfig("entry",true,replacement));
                } else configs.set(configIndex,configs.get(configIndex).withEffect(replacement));
            }
            DjPlaylistMixer updated = new DjPlaylistMixer(mixer.trackFxEnabled(),mixer.djFxEnabled(),configs);
            return new DjPlaylistEntry(entryId,trackId,startSourceTicks,fxMode,next,updated,mixerChartEffects,mixerChartCaptured);
        }
        return withEffects(fxMode == FxMode.NONE ? FxMode.DJ : fxMode, next);
    }

    public DjPlaylistEntry withStartSourceTicks(int value) {
        return new DjPlaylistEntry(entryId, trackId, value, fxMode, effects, mixer, mixerChartEffects, mixerChartCaptured);
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(entryId);
        buffer.writeUtf(trackId, MAX_TRACK_ID_LENGTH);
        buffer.writeVarInt(startSourceTicks);
        buffer.writeVarInt(fxMode.ordinal());
        buffer.writeVarInt(effects.size());
        for (TrackEffect effect : effects) effect.write(buffer);
        buffer.writeBoolean(mixer != null);
        if (mixer != null) mixer.write(buffer);
        buffer.writeVarInt(mixerChartEffects.size());
        for (TrackEffect effect : mixerChartEffects) effect.write(buffer);
        buffer.writeBoolean(mixerChartCaptured);
    }

    public static DjPlaylistEntry read(RegistryFriendlyByteBuf buffer) {
        UUID entryId = buffer.readUUID();
        String trackId = buffer.readUtf(MAX_TRACK_ID_LENGTH);
        int startSourceTicks = buffer.readVarInt();
        if (startSourceTicks < 0) {
            throw new IllegalArgumentException("Invalid DJ playlist start tick");
        }
        FxMode mode = FxMode.read(buffer.readVarInt());
        int count = buffer.readVarInt();
        if (count < 0 || count > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Invalid playlist FX count");
        List<TrackEffect> effects = new ArrayList<>(count);
        for (int i=0; i<count; i++) effects.add(TrackEffect.read(buffer));
        DjPlaylistMixer mixer = buffer.readBoolean() ? DjPlaylistMixer.read(buffer) : null;
        int chartCount = buffer.readVarInt();
        if (chartCount < 0 || chartCount > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Invalid mixer chart count");
        List<TrackEffect> chart = new ArrayList<>(chartCount);
        for (int i=0; i<chartCount; i++) chart.add(TrackEffect.read(buffer));
        return new DjPlaylistEntry(entryId, trackId, startSourceTicks, mode, effects, mixer, chart, buffer.readBoolean());
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("EntryId", entryId); tag.putString("TrackId", trackId); tag.putInt("StartSourceTicks", startSourceTicks);
        tag.putInt("FxMode", fxMode.ordinal());
        tag.put("EntryEffects", effectsToTag(effects));
        if (mixer != null) {
            tag.put("Mixer", mixer.toTag());
            if (mixerChartCaptured) tag.put("MixerChart", effectsToTag(mixerChartEffects));
        }
        return tag;
    }

    private static ListTag effectsToTag(List<TrackEffect> effects) {
        ListTag array = new ListTag();
        for (TrackEffect effect : effects) {
            JsonObject json = new DjVisualEffectConfig("entry", true, effect).toJson();
            json.addProperty("tick", effect.tick()); json.addProperty("duration", effect.durationTicks());
            json.addProperty("lane", effect.lane()); json.addProperty("layer", effect.layer());
            array.add(StringTag.valueOf(json.toString()));
        }
        return array;
    }

    public static DjPlaylistEntry fromTag(CompoundTag tag) {
        return new DjPlaylistEntry(tag.getUUID("EntryId"), tag.getString("TrackId"), tag.getInt("StartSourceTicks"),
                FxMode.read(tag.getInt("FxMode")), effectsFromTag(tag, "EntryEffects"),
                tag.contains("Mixer", Tag.TAG_COMPOUND) ? DjPlaylistMixer.fromTag(tag.getCompound("Mixer")) : null,
                effectsFromTag(tag, "MixerChart"), tag.contains("MixerChart"));
    }

    private static List<TrackEffect> effectsFromTag(CompoundTag tag, String key) {
        List<TrackEffect> effects = new ArrayList<>();
        if (tag.contains(key)) {
            JsonArray array;
            if (tag.contains(key, Tag.TAG_LIST)) {
                ListTag records = tag.getList(key, Tag.TAG_STRING);
                if (records.size() > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Too many saved playlist effects");
                array = new JsonArray();
                for (int i=0;i<records.size();i++) array.add(JsonParser.parseString(records.getString(i)));
            } else array = JsonParser.parseString(tag.getString(key)).getAsJsonArray();
            if (array.size() > TrackEffect.MAX_EFFECTS) throw new IllegalArgumentException("Too many saved playlist effects");
            for (var element : array) {
                JsonObject json = element.getAsJsonObject();
                effects.add(DjVisualEffectConfig.fromJson(json).effect()
                        .withTimeline(json.get("lane").getAsInt(), json.get("tick").getAsInt(), json.get("duration").getAsInt())
                        .withLayer(json.get("layer").getAsInt()));
            }
        }
        return effects;
    }
}
