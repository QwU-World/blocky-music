package ru.gora.musicbattle.client.workshop;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.gora.musicbattle.client.workshop.layout.Tr;
import ru.gora.musicbattle.client.workshop.layout.WorkshopStrings;
import ru.gora.musicbattle.workshop.BeatmapManifest;
import ru.gora.musicbattle.workshop.WorkshopCatalog;
import ru.gora.musicbattle.workshop.api.WorkshopDtos;

/**
 * One card's data, from the API ({@code SetCard}) or from an installed set (its {@code workshop-card.json} sidecar,
 * spec 08 A5.5, falling back to the manifest). The listing, the Installed tab and the set page header all read this,
 * so an offline card and an online card are drawn by the same code.
 */
public final class CardModel {
    public final long setId;
    public final int revision;
    public final String contentSha256;
    public final String title;
    public final String artist;
    public final String mapperName;
    public final String source;
    public final String status;
    public final String availability;
    public final String audioMode;
    public final boolean explicit;
    public final boolean ai;
    public final boolean library;
    public final boolean hasFx;
    public final List<Double> stars;
    public final List<String> chartNames;
    public final List<String> chartKeys;
    public final long downloads;
    /** The favourite count and the viewer's own flag change while the screen is open. */
    public long favourites;
    public final long comments;
    public final Double rating;
    public final String updatedAt;
    public final String coverUrl;
    public final String bodyCoverUrl;
    public final String previewUrl;
    public final long previewDurationMs;
    public final long durationMs;
    public final double bpm;
    public boolean favourited;
    /** Local files of an installed set (null for an online-only card). */
    public final Path localCover;
    public final Path localPreview;
    public final Path localAudio;
    public final boolean installedLocally;
    public final boolean audioPresent;
    public final long installedAtMillis;

    private CardModel(Builder builder) {
        setId = builder.setId;
        revision = builder.revision;
        contentSha256 = builder.contentSha256;
        title = builder.title;
        artist = builder.artist;
        mapperName = builder.mapperName;
        source = builder.source;
        status = builder.status;
        availability = builder.availability;
        audioMode = builder.audioMode;
        explicit = builder.explicit;
        ai = builder.ai;
        library = builder.library;
        hasFx = builder.hasFx;
        stars = List.copyOf(builder.stars);
        chartNames = List.copyOf(builder.chartNames);
        chartKeys = List.copyOf(builder.chartKeys);
        downloads = builder.downloads;
        favourites = builder.favourites;
        comments = builder.comments;
        rating = builder.rating;
        updatedAt = builder.updatedAt;
        coverUrl = builder.coverUrl;
        bodyCoverUrl = builder.bodyCoverUrl;
        previewUrl = builder.previewUrl;
        previewDurationMs = builder.previewDurationMs;
        durationMs = builder.durationMs;
        bpm = builder.bpm;
        favourited = builder.favourited;
        localCover = builder.localCover;
        localPreview = builder.localPreview;
        localAudio = builder.localAudio;
        installedLocally = builder.installedLocally;
        audioPresent = builder.audioPresent;
        installedAtMillis = builder.installedAtMillis;
    }

    public boolean externalAudio() {
        return "external".equals(audioMode);
    }

    public double topStars() {
        return stars.isEmpty() ? 0 : stars.get(stars.size() - 1);
    }

    /** "by Artist" and "mapped by name" style lines are built by the card layout; this is the hover stats row. */
    public String statsLine(Tr tr, long nowMillis) {
        StringBuilder out = new StringBuilder();
        out.append(tr.get(WorkshopStrings.KEY + "card.downloads", WorkshopStrings.count(downloads)));
        out.append("  ").append(tr.get(WorkshopStrings.KEY + "card.favourites", WorkshopStrings.count(favourites)));
        if (updatedAt != null && !updatedAt.isEmpty()) {
            out.append("  ").append(WorkshopStrings.relative(tr, updatedAt, nowMillis));
        }
        return out.toString();
    }

    public List<String> badges(Tr tr) {
        List<String> badges = new ArrayList<>();
        if (explicit) {
            badges.add(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.explicit")));
        }
        if (ai) {
            badges.add(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.ai")));
        }
        if (library) {
            badges.add(WorkshopStrings.upper(tr.get(WorkshopStrings.KEY + "badge.library")));
        }
        return badges;
    }

    // ---------------------------------------------------------------- building

    public static CardModel of(WorkshopDtos.SetCard card) {
        Builder builder = new Builder();
        builder.fromCard(card);
        return new CardModel(builder);
    }

    /** An installed set: the offline card sidecar when it exists, the manifest otherwise. */
    public static CardModel ofInstalled(WorkshopCatalog.InstalledSet set) {
        Builder builder = new Builder();
        BeatmapManifest manifest = set.manifest();
        builder.setId = manifest.setId();
        builder.revision = manifest.revision();
        builder.contentSha256 = set.contentSha256();
        builder.title = manifest.title();
        builder.artist = manifest.artist();
        builder.mapperName = manifest.mapper().name();
        builder.audioMode = manifest.audio().policy().id();
        builder.durationMs = manifest.audio().durationMs();
        for (BeatmapManifest.Chart chart : manifest.charts()) {
            builder.chartKeys.add(chart.key());
            builder.chartNames.add(chart.difficulty());
        }
        set.cardJson().ifPresent(json -> {
            try {
                builder.fromCard(WorkshopDtos.read(json, WorkshopDtos.SetCard.class));
            } catch (RuntimeException | ru.gora.musicbattle.workshop.api.WorkshopApiException ignored) {
                // A broken sidecar simply leaves the manifest values in place.
            }
        });
        builder.installedLocally = true;
        builder.installedAtMillis = set.installedAt() == null ? 0L : set.installedAt().toEpochMilli();
        builder.localCover = set.coverPresent() ? set.coverFile() : null;
        builder.localPreview = set.previewPresent() ? set.previewFile() : null;
        builder.localAudio = set.audioPresent() ? set.audioFile() : null;
        builder.audioPresent = set.audioPresent();
        if (builder.stars.isEmpty()) {
            builder.chartNames.forEach(name -> builder.stars.add(0.0));
        }
        return new CardModel(builder);
    }

    /**
     * A set installed on the Minecraft server (spec 06 section 9.1 with 08 A5.5): the page carries the charts, the
     * explicit flag and whether a cover exists, so the tab draws the same card as Browse without asking the site.
     * A withdrawn or long-unverified set keeps its row but says so through its availability.
     */
    public static CardModel ofServerBeatmap(ru.gora.musicbattle.network.ServerBeatmapsPagePayload.Item item) {
        Builder builder = new Builder();
        builder.setId = item.setId();
        builder.revision = item.revision();
        builder.contentSha256 = item.contentSha256();
        builder.title = item.title();
        builder.artist = item.artist();
        builder.mapperName = item.mapper();
        builder.explicit = item.explicit();
        builder.availability = item.withdrawn() ? "withdrawn" : "ok";
        builder.status = item.unverified() ? "unverified" : "community";
        builder.installedAtMillis = item.addedAt();
        List<ru.gora.musicbattle.network.ServerBeatmapsPagePayload.Chart> charts = new ArrayList<>(item.charts());
        charts.sort(Comparator.comparingDouble(ru.gora.musicbattle.network.ServerBeatmapsPagePayload.Chart::stars));
        for (var chart : charts) {
            builder.chartKeys.add(chart.key());
            builder.chartNames.add(chart.name());
            builder.stars.add(chart.stars());
        }
        return new CardModel(builder);
    }

    /** An installed set whose audio the player supplied outside the package. */
    public boolean needsAudio() {
        return externalAudio() && installedLocally && !audioPresent;
    }

    public static boolean fileExists(Path path) {
        return path != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static final class Builder {
        long setId;
        int revision;
        String contentSha256 = "";
        String title = "";
        String artist = "";
        String mapperName = "";
        String source = "";
        String status = "community";
        String availability = "ok";
        String audioMode = "included";
        boolean explicit;
        boolean ai;
        boolean library;
        boolean hasFx;
        final List<Double> stars = new ArrayList<>();
        final List<String> chartNames = new ArrayList<>();
        final List<String> chartKeys = new ArrayList<>();
        long downloads;
        long favourites;
        long comments;
        Double rating;
        String updatedAt = "";
        String coverUrl;
        String bodyCoverUrl;
        String previewUrl;
        long previewDurationMs;
        long durationMs;
        double bpm;
        boolean favourited;
        Path localCover;
        Path localPreview;
        Path localAudio;
        boolean installedLocally;
        boolean audioPresent;
        long installedAtMillis;

        void fromCard(WorkshopDtos.SetCard card) {
            if (card == null) {
                return;
            }
            setId = card.id();
            revision = card.revision();
            contentSha256 = card.contentSha256() == null ? contentSha256 : card.contentSha256();
            title = text(card.title(), title);
            artist = text(card.artist(), artist);
            source = text(card.source(), source);
            status = text(card.status(), status);
            availability = text(card.availability(), availability);
            audioMode = text(card.audioMode(), audioMode);
            if (card.mapper() != null) {
                mapperName = text(card.mapper().username(), mapperName);
            }
            if (card.flags() != null) {
                explicit = card.flags().explicit();
                ai = card.flags().aiAudio() || card.flags().aiCover() || card.flags().aiChart();
            }
            library = card.library() != null;
            hasFx = card.hasFx();
            if (card.charts() != null && !card.charts().isEmpty()) {
                stars.clear();
                chartNames.clear();
                chartKeys.clear();
                List<WorkshopDtos.ChartSummary> sorted = new ArrayList<>(card.charts());
                sorted.sort(Comparator.comparingDouble(WorkshopDtos.ChartSummary::stars));
                for (WorkshopDtos.ChartSummary chart : sorted) {
                    stars.add(chart.stars());
                    chartNames.add(chart.name() == null ? chart.key() : chart.name());
                    chartKeys.add(chart.key());
                }
            }
            if (card.counts() != null) {
                downloads = card.counts().downloads();
                favourites = card.counts().favourites();
                comments = card.counts().comments();
            }
            rating = card.rating();
            updatedAt = text(card.updatedAt(), updatedAt);
            if (card.covers() != null) {
                coverUrl = first(card.covers().list(), card.covers().list1x(), card.covers().card());
                bodyCoverUrl = first(card.covers().card(), card.covers().card2x(), card.covers().cover());
            }
            if (card.preview() != null) {
                previewUrl = card.preview().ogg();
                previewDurationMs = card.preview().durationMs();
            }
            durationMs = card.durationMs() > 0 ? card.durationMs() : durationMs;
            bpm = card.bpm();
            favourited = card.viewer() != null && card.viewer().favourited();
        }

        private static String text(String value, String fallback) {
            return value == null || value.isEmpty() ? fallback : value;
        }

        private static String first(String... values) {
            for (String value : values) {
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
            return null;
        }
    }
}
