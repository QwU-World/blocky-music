package ru.gora.musicbattle.data;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic composition rules shared by the DJ server and local client preview. */
public final class DjVisualEffects {
    private DjVisualEffects() {
    }

    /**
     * Combines chart FX with panel FX without rewriting either source.
     *
     * <p>Chart rows are unchanged. DJ templates receive distinct negative render ranks so that the
     * list reads like layers: the topmost entry is applied last and therefore covers every entry
     * below it, and every DJ entry is above all chart rows. Persisted lane/layer positions never
     * encode these runtime-only ranks.</p>
     */
    public static List<TrackEffect> compose(List<TrackEffect> chartEffects,
                                            List<DjVisualEffectConfig> djConfigs,
                                            boolean chartFxEnabled,
                                            boolean djFxEnabled,
                                            int sessionDurationTicks) {
        return compose(chartEffects, djConfigs, chartFxEnabled, djFxEnabled, 0, sessionDurationTicks);
    }

    public static List<TrackEffect> compose(List<TrackEffect> chartEffects,
                                            List<DjVisualEffectConfig> djConfigs,
                                            boolean chartFxEnabled,
                                            boolean djFxEnabled,
                                            int djActivationTick,
                                            int sessionDurationTicks) {
        List<TrackEffect> djEffects = djFxEnabled
                ? materializeDj(djConfigs, djActivationTick, sessionDurationTicks) : List.of();
        EnumSet<TrackEffect.Type> djSingletonOverrides = EnumSet.noneOf(TrackEffect.Type.class);
        for (TrackEffect effect : djEffects) {
            if (effect.type() == TrackEffect.Type.PLAYER_HIGHLIGHT
                    || effect.type() == TrackEffect.Type.OBJECT_OUTLINE) {
                djSingletonOverrides.add(effect.type());
            }
        }
        int chartLimit = Math.max(0, TrackEffect.MAX_EFFECTS - djEffects.size());
        List<TrackEffect> result = new ArrayList<>(Math.min(TrackEffect.MAX_EFFECTS,
                chartLimit + djEffects.size()));
        if (chartFxEnabled && chartEffects != null) {
            for (TrackEffect effect : chartEffects) {
                // These two world/mask passes intentionally select one effect. When the DJ owns
                // that type, suppress the chart candidate so row-zero priority cannot fall back
                // to intensity and unexpectedly choose the chart version.
                if (effect == null || djSingletonOverrides.contains(effect.type())) continue;
                result.add(effect);
                if (result.size() >= chartLimit) break;
            }
        }
        result.addAll(djEffects);
        return List.copyOf(result);
    }

    public static List<TrackEffect> materializeDj(List<DjVisualEffectConfig> configs,
                                                   int sessionDurationTicks) {
        return materializeDj(configs, 0, sessionDurationTicks);
    }

    public static List<TrackEffect> materializeDj(List<DjVisualEffectConfig> configs,
                                                   int activationTick,
                                                   int sessionDurationTicks) {
        List<DjVisualEffectConfig> safeConfigs = sanitizeConfigs(configs);
        if (safeConfigs.isEmpty()) return List.of();
        List<TrackEffect> result = new ArrayList<>(DjVisualEffectConfig.MAX_CONFIGS);
        for (DjVisualEffectConfig config : safeConfigs) {
            if (!config.enabled()) continue;
            result.add(config.materialize(activationTick, sessionDurationTicks));
            if (result.size() >= DjVisualEffectConfig.MAX_CONFIGS) break;
        }
        // Ranks are handed out from the bottom of the list upwards, so the top entry owns the most
        // negative renderOrder and the descending FX sort draws it last, over everything below it.
        for (int index = 0; index < result.size(); index++) {
            result.set(index, result.get(index).withDjOrder(result.size() - index));
        }
        return List.copyOf(result);
    }

    /** Bounded, deterministic server/client validation for persisted and network-provided lists. */
    public static List<DjVisualEffectConfig> sanitizeConfigs(List<DjVisualEffectConfig> configs) {
        if (configs == null || configs.isEmpty()) return List.of();
        List<DjVisualEffectConfig> result = new ArrayList<>(DjVisualEffectConfig.MAX_CONFIGS);
        Set<String> usedPresetIds = new HashSet<>();
        for (DjVisualEffectConfig config : configs) {
            if (config == null) continue;
            // Reconstructing applies TrackEffect/DjVisualEffectConfig's canonical clamps.
            DjVisualEffectConfig canonical = new DjVisualEffectConfig(
                    config.presetId(), config.enabled(), config.effect());
            String uniqueId = uniquePresetId(canonical.presetId(), usedPresetIds);
            result.add(uniqueId.equals(canonical.presetId())
                    ? canonical : canonical.withPresetId(uniqueId));
            if (result.size() >= DjVisualEffectConfig.MAX_CONFIGS) break;
        }
        return List.copyOf(result);
    }

    private static String uniquePresetId(String requestedId, Set<String> usedIds) {
        if (usedIds.add(requestedId)) return requestedId;
        for (int ordinal = 2; ordinal <= DjVisualEffectConfig.MAX_CONFIGS + 1; ordinal++) {
            String suffix = "_" + ordinal;
            int baseLength = Math.min(requestedId.length(), 32 - suffix.length());
            String candidate = requestedId.substring(0, baseLength) + suffix;
            if (usedIds.add(candidate)) return candidate;
        }
        // MAX_CONFIGS bounds the loop above, so this is only a defensive fallback.
        String fallback = Integer.toUnsignedString(requestedId.hashCode(), 36);
        usedIds.add(fallback);
        return fallback;
    }

    public static boolean needsAudienceTargets(List<TrackEffect> effects) {
        return effects != null && effects.stream().anyMatch(effect -> effect != null
                && effect.type() == TrackEffect.Type.PLAYER_HIGHLIGHT);
    }

    public static List<TrackEffect.Type> supportedTypes() {
        // This type controls panels that only exist in a real battle spectator session. Ambient
        // DJ playback has no participant panels, so exposing it there would create a no-op card.
        return java.util.Arrays.stream(TrackEffect.Type.values())
                .filter(type -> type != TrackEffect.Type.HIDE_BATTLE_PANELS)
                .toList();
    }

    public static PreviewCapability previewCapability(TrackEffect.Type type) {
        return switch (type == null ? TrackEffect.Type.FLASH : type) {
            case SHAKE, PLAYER_HIGHLIGHT, OBJECT_OUTLINE, HIDE_BATTLE_PANELS, HIDE_PARTICLES_HAND -> PreviewCapability.REQUIRES_WORLD_HOOKS;
            default -> PreviewCapability.FULLSCREEN_OVERLAY;
        };
    }

    public enum PreviewCapability {
        FULLSCREEN_OVERLAY,
        REQUIRES_WORLD_HOOKS
    }
}
