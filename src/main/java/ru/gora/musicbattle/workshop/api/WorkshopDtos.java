package ru.gora.musicbattle.workshop.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * Records mirroring the workshop HTTP API v1 as the mod reads it. Gson maps
 * them by component name; unknown fields are ignored and missing ones are null (or 0/false for primitives), so an
 * additive API change never breaks an older mod. Strings stay strings (ISO timestamps, enum-like values), and the
 * screens map them. Records that can hold a token redact it in {@code toString()}.
 */
public final class WorkshopDtos {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private WorkshopDtos() {
    }

    /** Maps a JSON object (as returned by {@link WorkshopApi}) into a record. */
    public static <T> T read(JsonElement json, Class<T> type) throws WorkshopApiException {
        try {
            T value = GSON.fromJson(withoutNullObjects(json, type), type);
            if (value == null) {
                throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "empty " + type.getSimpleName());
            }
            return value;
        } catch (RuntimeException exception) {
            throw new WorkshopApiException(WorkshopApiException.BAD_RESPONSE, "malformed " + type.getSimpleName(), exception);
        }
    }

    /**
     * The answer with every JSON {@code null} left out where a record has a {@code JsonObject} component, at any depth
     * of records and lists of records. Gson reads a missing member as null, but refuses a JSON null for a
     * {@code JsonObject} ("Expected a JsonObject but was JsonNull"), and its own adapter for JSON trees cannot be
     * replaced. The server sends exactly that for an upload of a new set ({@code "current": null}), so every first
     * upload from the game failed with "unexpected answer" before this. Only those members change; the input is not
     * modified.
     */
    static JsonElement withoutNullObjects(JsonElement json, java.lang.reflect.Type type) {
        if (json == null || !json.isJsonObject() || !(type instanceof Class<?> record) || !record.isRecord()) {
            if (json != null && json.isJsonArray() && type instanceof java.lang.reflect.ParameterizedType list
                    && List.class.isAssignableFrom((Class<?>) list.getRawType())) {
                com.google.gson.JsonArray copy = new com.google.gson.JsonArray();
                for (JsonElement element : json.getAsJsonArray()) {
                    copy.add(withoutNullObjects(element, list.getActualTypeArguments()[0]));
                }
                return copy;
            }
            return json;
        }
        JsonObject copy = json.getAsJsonObject().deepCopy();
        for (java.lang.reflect.RecordComponent component : record.getRecordComponents()) {
            JsonElement value = copy.get(component.getName());
            if (value == null) {
                continue;
            }
            if (value.isJsonNull() && component.getType() == JsonObject.class) {
                copy.remove(component.getName());
            } else {
                copy.add(component.getName(), withoutNullObjects(value, component.getGenericType()));
            }
        }
        return copy;
    }

    // ---------------------------------------------------------------- catalogue

    public record UserRef(long id, String username, String displayName, String avatarUrl) {
    }

    public record Covers(String list, String list1x, String list4x, String card, String card2x, String cover, String cover2x,
                         String slim) {
    }

    public record Preview(String ogg, String mp3, long durationMs) {
    }

    public record ChartSummary(String key, String name, double stars, int sides) {
    }

    public record Counts(long downloads, long favourites, long comments, long ratings) {
    }

    public record Flags(boolean explicit, boolean flashing, boolean aiAudio, boolean aiCover, boolean aiChart, boolean spoiler) {
    }

    public record ViewerState(boolean favourited, Integer rating) {
    }

    public record LibraryRef(long id, String artist, String title) {
    }

    /** {@code SetCard}. {@code viewer} is null for guests; {@code covers}/{@code preview} may be null. */
    public record SetCard(
            long id, int revision, String contentSha256,
            String title, String titleUnicode, String artist, String artistUnicode, String source,
            String status, String availability, String audioMode, LibraryRef library, UserRef mapper,
            Covers covers, Preview preview, double bpm, long durationMs, List<ChartSummary> charts, Counts counts,
            Double rating, Flags flags, boolean hasFx, int genre, int language, List<String> tags,
            String submittedAt, String updatedAt, String featuredAt, ViewerState viewer
    ) {
    }

    /** {@code GET /api/v1/beatmapsets}: {@code cursor} null at the end. */
    public record SearchPage(List<SetCard> items, long total, boolean totalCapped, String cursor) {
    }

    public record ChartDetail(String key, String name, double stars, Double starsLeft, Double starsRight, int sides,
                              int noteCount, int holdCount, int effectCount, long durationMs, double bpm, Double peakNps,
                              String fileSha256) {
    }

    public record Featured(String at, UserRef by, String note) {
    }

    public record AvailabilityInfo(String state, String reason, String caseRef, String messageKey) {
    }

    public record DownloadInfo(long size, String packageSha256, String fileName) {
    }

    public record AudioInfo(String mode, String sha256, long size, long durationMs) {
    }

    public record LibraryAttribution(long trackId, String artist, String title, String artistUrl, String licenceName,
                                     String licenceUrl, String attribution) {
    }

    public record MapperProfile(int sets, int featured) {
    }

    /** {@code GET /api/v1/beatmapsets/{id}}: the card fields plus the detail. */
    public record SetDetail(
            long id, int revision, String contentSha256,
            String title, String titleUnicode, String artist, String artistUnicode, String source,
            String status, String availability, String audioMode, LibraryRef library, UserRef mapper,
            Covers covers, Preview preview, double bpm, long durationMs, List<ChartDetail> charts, Counts counts,
            Double rating, Flags flags, boolean hasFx, int genre, int language, List<String> tags,
            String submittedAt, String updatedAt, String featuredAt, ViewerState viewer,
            String descriptionHtml, String descriptionMd, String creditsHtml, Featured featured,
            AvailabilityInfo availabilityInfo, List<Integer> ratingHist, long previewStartMs,
            LibraryAttribution libraryAttribution, DownloadInfo download, AudioInfo audio, boolean commentsLocked,
            Map<String, Boolean> can, Map<String, String> cannot, MapperProfile mapperProfile
    ) {
    }

    public record RevisionInfo(int revision, String createdAt, String changelog, String contentSha256, long packageSize,
                               List<String> charts) {
    }

    public record SetHistory(List<RevisionInfo> revisions, List<JsonObject> events) {
    }

    /** {@code POST /api/v1/beatmapsets/{id}/download}. {@code url} is relative to the workshop origin. */
    public record DownloadTicket(String url, long size, String packageSha256, String contentSha256, int revision,
                                 String fileName, String expiresAt) {
    }

    public record InstalledEntry(long setId, int revision, String contentSha256) {
    }

    /** {@code state}: current, update_available, withdrawn, audio_removed, unknown. */
    public record InstalledResult(long setId, String state, Integer latestRevision, String availability, String reason) {
    }

    public record LookupMatch(String hash, long setId, int revision, boolean current) {
    }

    public record FavouriteState(boolean favourited, long count) {
    }

    public record RatingSummary(Double average, int count, List<Integer> hist, Integer mine) {
    }

    /** {@code GET /api/v1/meta}. {@code reportReasons} maps a target type to its reason codes. */
    public record Meta(List<JsonObject> genres, List<JsonObject> languages, List<String> statuses, List<String> availability,
                       JsonObject limits, String termsVersion, String rulesVersion, String communityRulesVersion,
                       String uploadRulesVersion, JsonObject reportReasons) {
    }

    // ---------------------------------------------------------------- comments and notifications (04 sections 2, 5)

    /** What the viewer may do with a comment (server-decided; the UI never re-implements the rules). */
    public record CommentCan(boolean reply, boolean edit, boolean delete, boolean restore, boolean pin, boolean unpin, boolean vote,
                             boolean report) {
    }

    /**
     * One comment as {@code src/social/bundle.ts} serialises it. Placeholders (deleted or hidden) have a null
     * {@code userId} and an empty {@code bodyHtml}; {@code hidden} is null, {@code pending_review} or {@code moderator}.
     */
    public record Comment(long id, Long parentId, String commentableType, Long commentableId, Long userId, String bodyHtml,
                          String bodyMd, boolean pinned, int votesCount, int repliesCount, String createdAt, String editedAt,
                          Long editedById, boolean deleted, String deletedAt, String hidden, CommentCan can) {
    }

    /** {@code badges}: {@code admin}, {@code mod}, {@code curator}. */
    public record CommentUser(long id, String username, String displayName, String avatarUrl, List<String> badges, boolean deleted) {
    }

    public record CommentsMeta(boolean locked, boolean canPost, String cannotPostReason, Long ownerId, boolean staff, boolean showDeleted,
                               String sort) {
    }

    /**
     * {@code GET /api/v1/comments}: a page at one level, {@code included} descendants two levels deep, {@code pinned}
     * comments (first page only), {@code parent} for a replies page, the viewer's votes and next-page cursors.
     */
    public record CommentsPage(List<Comment> comments, List<Comment> included, List<Comment> pinned, Comment parent,
                               List<CommentUser> users, List<Long> userVotes, long total, long topLevelCount, Long deletedCount,
                               String cursor, boolean hasMore, long remaining, Map<String, String> replyCursors, CommentsMeta meta) {
    }

    /** Answer of a comment mutation (post, edit, delete). */
    public record CommentMutation(Comment comment, List<CommentUser> users, long total) {
    }

    public record VoteResult(boolean voted, int votesCount) {
    }

    public record NotificationSet(long id, String title) {
    }

    /** {@code GET /api/v1/notifications} item; {@code message} is plain text in the request language. */
    public record Notification(long id, String kind, String createdAt, String readAt, boolean unread, AccountSummary actor,
                               NotificationSet set, Long setId, Long commentId, int count, String message, String url) {
    }

    public record NotificationsPage(List<Notification> items, String cursor, int unread) {
    }

    public record NotificationCount(int unread) {
    }

    // ---------------------------------------------------------------- account

    public record Account(long id, String username, String displayName, String avatarUrl, String role, boolean curator,
                          String lang) {
    }

    public record Prefs(boolean showExplicit, String cardSize, double previewVolume, String commentSort, boolean showDeleted) {
    }

    public record Standing(String silencedUntil, boolean restricted, String uploadSuspendedUntil, boolean uploadTerminated,
                           int activeStrikes) {
    }

    public record MinecraftLink(String uuid, String name, boolean isPrimary, boolean autoSignIn, boolean showOnProfile,
                                String linkedAt, String lastProvenAt, String headUrl) {
    }

    public record TokenInfo(String kind, String expiresAt, List<String> scope, String minecraftUuid) {
    }

    /**
     * The one-time acceptance of the Workshop rules ({@code GET /api/v1/me} fields {@code rules} and
     * {@code participation}). {@code accepted} is true only for the current versions of both documents;
     * {@code needsReaccept} tells a person who accepted an older version that it moved, and {@code changes} says in one
     * line what changed.
     */
    public record Rules(boolean accepted, String acceptedAt, String termsVersion, String rulesVersion,
                        boolean needsReaccept, String changes) {
        public static final Rules UNKNOWN = new Rules(false, null, "1", "1", false, null);

        /** True while this acceptance is still owed: never given, or given for a version that has moved since. */
        public boolean pending() {
            return !accepted || needsReaccept;
        }
    }

    /**
     * {@code GET /api/v1/me}. Two acceptances live side by side: {@code rules} is the uploader's (Workshop Terms and
     * Upload Rules, the wizard's two boxes) and {@code participation} the lighter one every commenter and rater gives
     * (Workshop Terms and Community Rules, two boxes). An older server that answers with neither leaves both null.
     */
    public record Me(Account account, Prefs prefs, Standing standing, Rules rules, Rules participation,
                     List<MinecraftLink> minecraft, int notificationsUnread, TokenInfo token) {
    }

    // ---------------------------------------------------------------- auth (02 sections 4.2, 4.3)

    public record AccountSummary(long id, String username, String displayName, String avatarUrl) {
    }

    // ---------------------------------------------------------------- public profile

    /** {@code GET /api/v1/users/{user}}: what the Workshop shows about one person, for anyone who can see them. */
    public record Profile(ProfileUser user, ProfileCounts counts, Covers headerCovers, List<ProfileMinecraft> minecraft) {
    }

    public record ProfileUser(long id, String username, String displayName, String avatarUrl, String profileUrl,
                              List<String> roles, String joinedAt) {
    }

    public record ProfileCounts(int sets, int featured, int community, int wip, int favouritesGiven,
                                int favouritesReceived, int comments) {
    }

    public record ProfileMinecraft(String name, String uuid, String headUrl) {
    }

    public record MinecraftRef(String uuid, String name) {
    }

    public record ApprovedBy(String username, String displayName, String avatarUrl) {
    }

    /** {@code POST /api/v1/auth/device} and {@code POST /api/v1/mc/link-requests}. */
    public record DeviceCodes(String deviceCode, String userCode, String verificationUri, String verificationUriComplete,
                              int expiresIn, int interval) {
        @Override
        public String toString() {
            return "DeviceCodes[userCode=" + userCode + ", expiresIn=" + expiresIn + ", interval=" + interval + "]";
        }
    }

    /** {@code POST /api/v1/mc/challenges}. */
    public record Challenge(String challengeId, String nonce, String scheme, String expiresAt) {
    }

    /**
     * Every auth answer that may carry tokens: device token, refresh, Minecraft verify ({@code signed_in},
     * {@code not_linked}, {@code auto_sign_in_disabled}, {@code proven}) and link polling ({@code approved},
     * {@code linked}). Absent parts are null.
     */
    public record AuthResult(String status, String accessToken, String tokenType, long expiresIn, String refreshToken,
                             Long refreshExpiresIn, List<String> scope, AccountSummary account, MinecraftRef minecraft,
                             String mcProofId, String linkedTo, ApprovedBy approvedBy) {
        public boolean hasAccessToken() {
            return accessToken != null && !accessToken.isEmpty();
        }

        @Override
        public String toString() {
            return "AuthResult[status=" + status + ", accessToken=" + (hasAccessToken() ? "<redacted>" : "none")
                    + ", refreshToken=" + (refreshToken == null ? "none" : "<redacted>") + ", account="
                    + (account == null ? "none" : account.username()) + "]";
        }
    }

    // ---------------------------------------------------------------- uploads

    public record UploadLimits(long audioBytes, long coverBytes, long chartBytes, int charts, List<Long> durationMs) {
    }

    public record UploadSlot(String slot, long size, String sha256, String contentType, String fileName, JsonObject probe) {
    }

    public record ValidationIssue(String code, String field, String note) {
    }

    public record Validation(boolean ok, List<ValidationIssue> errors, List<ValidationIssue> warnings, boolean reviewRequired) {
    }

    public record UploadResult(long setId, int revision, String url, String status) {
    }

    /**
     * {@code Upload} (02 section 3, {@code src/uploads/store.ts}). {@code status}: open, submitting, submitted, failed,
     * expired, cancelled; {@code stage} while submitting: preparing, audio, package, finishing. {@code current} describes
     * the set being updated (null for new sets).
     */
    public record Upload(String id, Long setId, String status, String stage, String expiresAt, UploadLimits limits,
                         List<UploadSlot> slots, JsonObject metadata, Validation validation, JsonObject current, UploadResult result,
                         String errorCode) {
    }

    /** {@code GET /api/v1/uploads}: the caller's uploads still in progress. */
    public record UploadList(List<Upload> items) {
    }
}
