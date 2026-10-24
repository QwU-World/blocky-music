package ru.gora.musicbattle.client.workshop;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import ru.gora.musicbattle.client.ui.CoverCache;
import ru.gora.musicbattle.client.ui.Palette;
import ru.gora.musicbattle.client.ui.PreviewPlayer;
import ru.gora.musicbattle.client.ui.Ring;
import ru.gora.musicbattle.client.ui.RoundedImage;
import ru.gora.musicbattle.client.ui.RoundedRect;
import ru.gora.musicbattle.client.ui.Spectrum;
import ru.gora.musicbattle.client.ui.StarPill;
import ru.gora.musicbattle.client.ui.TextStyle;
import ru.gora.musicbattle.client.ui.UiIcon;
import ru.gora.musicbattle.client.workshop.layout.CardLayout;
import ru.gora.musicbattle.client.workshop.layout.Layout;
import ru.gora.musicbattle.client.workshop.layout.Metrics;
import ru.gora.musicbattle.client.workshop.layout.Rect;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.api.WorkshopDownloader;

/**
 * Draws one beatmap card from its {@link CardLayout}: thumbnail with the play button and the preview ring, the body
 * with the cover gradient, title, artist and mapper lines, badges, the hover statistics row, the status pill with the
 * difficulty dots and the button strip (favourite, install / progress / installed ✓ / update).
 *
 * <p>Drawing is split into passes so a grid batches: every card's shapes, then every cover, then pills, text and
 * icons (research {@code mc-gui-feasibility.md} section 3).
 */
public final class CardRenderer {
    /** Per-card state the listing keeps. */
    public record Context(boolean extra, boolean hover, boolean expanded, boolean installedTab, long nowMillis,
                          WorkshopDownloader.Status status, boolean playing, boolean loadingPreview, float hoverAmount,
                          String rightBadge) {
    }

    private CardRenderer() {
    }

    public static String previewKey(long setId) {
        return "ws_" + setId;
    }

    /** The layout of one card; the listing keeps it for hit-testing too. */
    public static Layout layout(Rect rect, CardModel model, Context context, Tr tr) {
        CardLayout.State state = new CardLayout.State(context.extra(), context.hover(), context.expanded(),
                context.installedTab(), model.title, model.artist,
                tr.get(WorkshopStrings.KEY + "card.mapped_by", model.mapperName), model.source,
                WorkshopStrings.status(tr, model.status), model.badges(tr), model.stars,
                model.statsLine(tr, context.nowMillis()), context.rightBadge(), model.externalAudio(), model.hasFx,
                context.expanded() ? model.chartNames.size() : 0, downloading(context.status()));
        return CardLayout.compute(rect, ru.gora.musicbattle.client.ui.TextStyles.measurer(), tr, state);
    }

    public static boolean downloading(WorkshopDownloader.Status status) {
        return status != null && (status.state() == WorkshopDownloader.State.DOWNLOADING
                || status.state() == WorkshopDownloader.State.QUEUED || status.state() == WorkshopDownloader.State.INSTALLING);
    }

    /** Pass 1: shadows, body and strip. */
    public static void drawShapes(GuiGraphics g, Layout layout, CardModel model, Context context) {
        Rect card = layout.rect("card");
        Rect body = layout.rect("card.body");
        Rect strip = layout.rect("card.strip");
        float grow = context.hoverAmount();
        if (grow > 0.01F) {
            RoundedRect.shadow(g, card.x(), card.y() + 2, card.w(), card.h(), Palette.RADIUS_MD, 7F,
                    Palette.fade(Palette.SHADOW_LIFTED, grow));
        }
        RoundedRect.fill(g, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD,
                Palette.mix(Palette.BG_2, Spectrum.argb(model.topStars()), 0.22F));
        int bodyColour = Palette.mix(Palette.BG_3, Palette.BG_4, grow);
        RoundedRect.fill(g, body.x(), body.y(), body.w(), body.h(), Palette.RADIUS_MD, bodyColour);
        int stripColour = context.status() != null && context.status().state() == WorkshopDownloader.State.INSTALLED
                ? Palette.mix(Palette.BG_4, Palette.OK, 0.55F) : Palette.mix(Palette.BG_4, Palette.BG_2, 0.4F);
        RoundedRect.fill(g, strip.x(), strip.y(), strip.w(), strip.h(), Palette.RADIUS_MD, stripColour);
        RoundedRect.fill(g, strip.x(), strip.y(), Math.min(strip.w(), Palette.RADIUS_MD), strip.h(), 0, stripColour);
        if (context.expanded()) {
            RoundedRect.stroke(g, card.x(), card.y(), card.w(), card.h(), Palette.RADIUS_MD, 1.4F, Palette.ACCENT);
        }
    }

    /** Pass 2: the thumbnail and the body cover gradient (one immediate draw each). */
    public static void drawImages(GuiGraphics g, Layout layout, CardModel model, Context context, boolean networkAllowed) {
        Rect thumb = layout.rect("card.thumb");
        Rect body = layout.rect("card.body");
        ResourceLocation cover = texture(model.localCover, networkAllowed ? model.coverUrl : null);
        float dim = context.playing() ? 0.6F : context.hover() ? 0.45F : 0F;
        if (cover != null) {
            RoundedImage.draw(g, cover, thumb.x(), thumb.y(), thumb.w(), thumb.h(), 0F, 0F, 1F, 1F,
                    Palette.RADIUS_MD, 0F, 0F, Palette.RADIUS_MD, dim, 0xFFFFFFFF, 0xFFFFFFFF);
        } else if (dim > 0) {
            RoundedRect.fill(g, thumb.x(), thumb.y(), thumb.w(), thumb.h(), Palette.RADIUS_MD, Palette.withAlpha(Palette.BG_0, dim));
        }
        ResourceLocation wide = texture(model.localCover, networkAllowed ? model.bodyCoverUrl : null);
        if (wide != null) {
            RoundedImage.draw(g, wide, body.x(), body.y(), body.w(), body.h(), 0F, 0.1F, 1F, 0.9F,
                    Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, Palette.RADIUS_MD, 0F, 0x00FFFFFF, 0x38FFFFFF);
        }
    }

    /** Pass 3: pills, dots, rings, text and icons. */
    public static void drawOverlay(GuiGraphics g, Layout layout, CardModel model, Context context, double mouseX, double mouseY,
                                   Tr tr) {
        Rect thumb = layout.rect("card.thumb");
        // Play / stop button with the preview ring.
        boolean overThumb = WorkshopDraw.hover(thumb, mouseX, mouseY);
        if (context.hover() || context.playing() || overThumb) {
            float radius = Math.min(11F, thumb.w() * 0.22F);
            RoundedRect.circle(g, thumb.centerX(), thumb.centerY(), radius, Palette.withAlpha(Palette.BG_0, 0.55F));
            if (context.loadingPreview()) {
                WorkshopDraw.spinner(g, thumb.centerX(), thumb.centerY(), radius * 0.8F, Palette.ACCENT);
            } else if (context.playing()) {
                WorkshopDraw.stopSquare(g, thumb.centerX(), thumb.centerY(), radius * 0.7F, Palette.TEXT_1);
                Ring.draw(g, thumb.centerX(), thumb.centerY(), radius, Ring.OSU_PREVIEW_THICKNESS,
                        PreviewPlayer.progress(previewKey(model.setId)), Palette.ACCENT);
            } else {
                WorkshopDraw.icon(g, new Rect(thumb.centerX() - radius * 0.5F + 0.5F, thumb.centerY() - radius * 0.5F,
                        radius, radius), UiIcon.PLAY, overThumb ? Palette.ACCENT : Palette.TEXT_1);
            }
        }
        if (!layout.rect("card.fx").empty()) {
            WorkshopDraw.icon(g, layout.rect("card.fx"), UiIcon.STAR, Palette.withAlpha(Palette.TEXT_1, 0.8F));
        }

        // Badges, status pill, dots.
        for (int i = 0; i < 4; i++) {
            Rect badge = layout.rect("card.badge." + i);
            if (badge.empty()) {
                break;
            }
            WorkshopDraw.pill(g, badge, Palette.BG_1);
            WorkshopDraw.text(g, layout, "card.badge." + i + ".text", badgeColour(model, i, tr));
        }
        Rect statusPill = layout.rect("card.status");
        if (!statusPill.empty()) {
            WorkshopDraw.pill(g, statusPill, WorkshopDraw.statusColour(model.status));
            WorkshopDraw.text(g, layout, "card.status.text", Palette.ON_PILL);
        }
        for (int i = 0; i < model.stars.size(); i++) {
            Rect dot = layout.rect("card.dot." + i);
            if (dot.empty()) {
                break;
            }
            StarPill.dot(g, dot.x(), dot.y(), model.stars.get(i), Palette.BG_3);
        }
        if (!layout.rect("card.audio").empty()) {
            WorkshopDraw.icon(g, layout.rect("card.audio"), UiIcon.ALERT, Palette.WARN);
        }
        Rect rightBadge = layout.rect("card.right_badge");
        if (!rightBadge.empty()) {
            WorkshopDraw.pill(g, rightBadge, Palette.BG_1);
            WorkshopDraw.text(g, layout, "card.right_badge.text", WorkshopDraw.badgeColour(badgeKey(context.rightBadge())));
        }

        WorkshopDraw.text(g, layout, "card.title", Palette.TEXT_1);
        WorkshopDraw.text(g, layout, "card.artist", Palette.TEXT_2);
        WorkshopDraw.text(g, layout, "card.source", Palette.TEXT_MUTE);
        WorkshopDraw.text(g, layout, "card.mapper", Palette.TEXT_MUTE);
        WorkshopDraw.text(g, layout, "card.stats", Palette.TEXT_MUTE);
        WorkshopDraw.text(g, layout, "card.charts", Palette.TEXT_2);

        Rect progress = layout.rect("card.progress");
        if (!progress.empty() && context.status() != null) {
            float fraction = context.status().state() == WorkshopDownloader.State.INSTALLING ? 1F : context.status().progress();
            WorkshopDraw.bar(g, progress, fraction, Palette.BG_0,
                    context.status().state() == WorkshopDownloader.State.INSTALLING ? Palette.WARN : Palette.ACCENT);
        }

        // Strip buttons.
        Rect favourite = layout.rect("card.favourite");
        if (!favourite.empty()) {
            boolean hovered = WorkshopDraw.hot(favourite, mouseX, mouseY);
            WorkshopDraw.icon(g, favourite.inset(favourite.w() * 0.25F, favourite.h() * 0.32F),
                    model.favourited ? UiIcon.HEART_FILLED : UiIcon.HEART,
                    model.favourited ? Palette.DANGER : hovered ? Palette.TEXT_1 : Palette.TEXT_3);
        }
        Rect install = layout.rect("card.install");
        if (!install.empty()) {
            boolean hovered = WorkshopDraw.hot(install, mouseX, mouseY);
            WorkshopDraw.icon(g, install.inset(install.w() * 0.25F, install.h() * 0.32F), installIcon(context),
                    installColour(context, hovered));
        }
    }

    private static UiIcon installIcon(Context context) {
        WorkshopDownloader.State state = context.status() == null ? WorkshopDownloader.State.NOT_INSTALLED : context.status().state();
        return switch (state) {
            case INSTALLED -> UiIcon.CHECK;
            case UPDATE_AVAILABLE -> UiIcon.REFRESH;
            case UNAVAILABLE -> UiIcon.EYE_OFF;
            case FAILED -> UiIcon.ALERT;
            case QUEUED, DOWNLOADING, INSTALLING -> UiIcon.LOADER;
            default -> UiIcon.DOWNLOAD;
        };
    }

    private static int installColour(Context context, boolean hovered) {
        WorkshopDownloader.State state = context.status() == null ? WorkshopDownloader.State.NOT_INSTALLED : context.status().state();
        return switch (state) {
            case INSTALLED -> Palette.ON_PILL;
            case UPDATE_AVAILABLE -> Palette.WARN;
            case UNAVAILABLE, FAILED -> Palette.DANGER;
            default -> hovered ? Palette.TEXT_1 : Palette.TEXT_3;
        };
    }

    private static int badgeColour(CardModel model, int index, Tr tr) {
        List<String> badges = model.badges(tr);
        if (index >= badges.size()) {
            return Palette.TEXT_3;
        }
        String badge = badges.get(index);
        if (badge.equals(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.explicit")))) {
            return Palette.BADGE_EXPLICIT;
        }
        if (badge.equals(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.library")))) {
            return Palette.BADGE_LIBRARY;
        }
        return Palette.BADGE_AI;
    }

    private static String badgeKey(String label) {
        return label == null ? "" : label.toLowerCase(java.util.Locale.ROOT);
    }

    /** A local file wins over the URL (installed sets work offline). */
    public static ResourceLocation texture(java.nio.file.Path local, String url) {
        if (CardModel.fileExists(local)) {
            return CoverCache.getFile(local);
        }
        return url == null || url.isEmpty() ? null : CoverCache.get(url);
    }

    /** Rows of the expanded chart dropdown: star pill plus difficulty name. */
    public static void drawDropdown(GuiGraphics g, Layout layout, CardModel model, double mouseX, double mouseY) {
        Rect dropdown = layout.rect("card.dropdown");
        if (dropdown.empty()) {
            return;
        }
        RoundedRect.shadow(g, dropdown.x(), dropdown.y() + 2, dropdown.w(), dropdown.h(), Palette.RADIUS_MD, 8F, Palette.SHADOW_LIFTED);
        RoundedRect.fill(g, dropdown.x(), dropdown.y(), dropdown.w(), dropdown.h(), Palette.RADIUS_MD, Palette.BG_2);
        int rows = (int) Math.min(model.chartNames.size(), Math.floor((dropdown.h() - 6) / Metrics.CARD_DROPDOWN_ROW));
        for (int i = 0; i < rows; i++) {
            Rect row = CardLayout.dropdownRow(dropdown, i);
            boolean hovered = WorkshopDraw.hot(row, mouseX, mouseY);
            if (hovered) {
                RoundedRect.fill(g, row.x(), row.y(), row.w(), row.h(), Palette.RADIUS_SM, Palette.BG_3);
            }
            double stars = i < model.stars.size() ? model.stars.get(i) : 0;
            float pillWidth = StarPill.width(stars);
            StarPill.shapes(g, row.x() + 2, row.y() + (row.h() - StarPill.HEIGHT) * 0.5F, stars);
            StarPill.text(g, row.x() + 2, row.y() + (row.h() - StarPill.HEIGHT) * 0.5F, stars);
            ru.gora.musicbattle.client.ui.TextStyles.queue(g,
                    ru.gora.musicbattle.client.ui.TextStyles.ellipsize(
                            ru.gora.musicbattle.client.ui.TextStyles.text(model.chartNames.get(i), TextStyle.CAPTION),
                            row.w() - pillWidth - 10),
                    row.x() + pillWidth + 6, ru.gora.musicbattle.client.ui.TextStyles.centeredCapY(TextStyle.CAPTION, row.y(), row.h()),
                    hovered ? Palette.TEXT_1 : Palette.TEXT_2);
        }
    }
}
