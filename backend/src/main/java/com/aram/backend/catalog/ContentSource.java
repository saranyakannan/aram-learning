package com.aram.backend.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.aram.backend.catalog.FirestoreOps.instant;
import static com.aram.backend.catalog.FirestoreOps.integer;
import static com.aram.backend.catalog.FirestoreOps.longValue;
import static com.aram.backend.catalog.FirestoreOps.string;
import static com.aram.backend.catalog.FirestoreOps.strings;
import static com.aram.backend.catalog.FirestoreOps.timestamp;

/**
 * The canonical record of a content source (spec 3.6), stored once in {@code content_sources} no matter how many
 * parents save it. The id is {@code youtube_<platform channel id>}, so the same channel can never exist twice.
 * {@code minAgeMonths}/{@code maxAgeMonths} copy the latest assessment's range so reads need no second lookup;
 * {@code handle}, {@code thumbnailUrl} and {@code subscriberCount} are display extras from the platform.
 */
public record ContentSource(
		String id,
		String name,
		String normalizedName,
		String sourceType,
		String platform,
		String canonicalUrl,
		String platformSourceId,
		String description,
		List<String> tagIds,
		List<String> originTypes,
		Statuses.Catalog catalogStatus,
		Statuses.Assessment assessmentStatus,
		String latestAssessmentId,
		Integer minAgeMonths,
		Integer maxAgeMonths,
		String handle,
		String thumbnailUrl,
		Long subscriberCount,
		Instant createdAt,
		Instant updatedAt,
		Instant lastVerifiedAt,
		String feedUrl
) {

	public static final String YOUTUBE_CHANNEL = "YOUTUBE_CHANNEL";
	public static final String PODCAST_FEED = "PODCAST_FEED";
	public static final String YOUTUBE = "YOUTUBE";
	public static final String APPLE_PODCASTS = "APPLE_PODCASTS";
	public static final String PREPOPULATED = "PREPOPULATED";
	public static final String PARENT_SUBMITTED = "PARENT_SUBMITTED";
	/** Found by the catalog builder (a search), assessed by the model, and only recommendable after a person reviews it. */
	public static final String CATALOG_BUILDER = "CATALOG_BUILDER";

	private static final Pattern SOURCE_ID = Pattern.compile("^(youtube_UC[A-Za-z0-9_-]{22}|podcast_[0-9]{1,15})$");

	public ContentSource {
		tagIds = tagIds == null ? List.of() : List.copyOf(tagIds);
		originTypes = originTypes == null ? List.of() : List.copyOf(originTypes);
	}

	/** The shape before podcasts existed (no feed url). */
	public ContentSource(String id, String name, String normalizedName, String sourceType, String platform,
			String canonicalUrl, String platformSourceId, String description, List<String> tagIds, List<String> originTypes,
			Statuses.Catalog catalogStatus, Statuses.Assessment assessmentStatus, String latestAssessmentId,
			Integer minAgeMonths, Integer maxAgeMonths, String handle, String thumbnailUrl, Long subscriberCount,
			Instant createdAt, Instant updatedAt, Instant lastVerifiedAt) {
		this(id, name, normalizedName, sourceType, platform, canonicalUrl, platformSourceId, description, tagIds,
				originTypes, catalogStatus, assessmentStatus, latestAssessmentId, minAgeMonths, maxAgeMonths, handle,
				thumbnailUrl, subscriberCount, createdAt, updatedAt, lastVerifiedAt, null);
	}

	public static String idForYoutubeChannel(String youtubeChannelId) {
		return "youtube_" + youtubeChannelId;
	}

	/** Podcasts are keyed by the Apple Podcasts collection id, so one show can never be stored twice. */
	public static String idForPodcast(long collectionId) {
		return "podcast_" + collectionId;
	}

	/** Is a YouTube source's stored platform data older than YouTube's 30-day limit (decision record 0011)? */
	public boolean isStaleYoutubeData(Instant now) {
		return YOUTUBE.equals(platform) && (lastVerifiedAt == null || lastVerifiedAt.isBefore(now.minus(java.time.Duration.ofDays(30))));
	}

	/**
	 * A copy with the YouTube-derived fields refreshed. Curated sources keep their own name and description (our text, not
	 * API data); everything else takes the title and description YouTube now reports.
	 */
	public ContentSource withYoutubeData(com.aram.backend.model.ChannelFacts facts, Instant now) {
		boolean curatedText = originTypes.contains(PREPOPULATED);
		String newName = curatedText || facts.title() == null || facts.title().isBlank() ? name : facts.title();
		return new ContentSource(id, newName, normalize(newName), sourceType, platform, SourceDescriptor.youtube(facts).canonicalUrl(),
				platformSourceId, curatedText ? description : facts.description(), tagIds, originTypes, catalogStatus, assessmentStatus,
				latestAssessmentId, minAgeMonths, maxAgeMonths, facts.handle(), facts.thumbnailUrl(), facts.subscriberCount(),
				createdAt, now, now, feedUrl);
	}

	/**
	 * A copy for a channel YouTube no longer returns: taken out of the live catalog (never playable or offered) and stripped of
	 * the platform data we may no longer keep. The source id and our curated text stay.
	 */
	public ContentSource withoutYoutubeData(Instant now) {
		boolean curatedText = originTypes.contains(PREPOPULATED);
		String newName = curatedText ? name : "Unavailable channel";
		return new ContentSource(id, newName, normalize(newName), sourceType, platform,
				"https://www.youtube.com/channel/" + platformSourceId, platformSourceId, curatedText ? description : "", tagIds, originTypes,
				Statuses.Catalog.UNDER_REVIEW, assessmentStatus, latestAssessmentId, minAgeMonths, maxAgeMonths, null, null, null,
				createdAt, now, lastVerifiedAt, feedUrl);
	}

	public boolean isPodcast() {
		return PODCAST_FEED.equals(sourceType);
	}

	public static boolean isValidId(String id) {
		return id != null && SOURCE_ID.matcher(id).matches();
	}

	public static String normalize(String name) {
		return name == null ? "" : name.trim().toLowerCase().replaceAll("\\s+", " ");
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", name);
		map.put("normalizedName", normalizedName);
		map.put("sourceType", sourceType);
		map.put("platform", platform);
		map.put("canonicalUrl", canonicalUrl);
		map.put("platformSourceId", platformSourceId);
		map.put("description", description);
		map.put("tagIds", tagIds);
		map.put("originTypes", originTypes);
		map.put("catalogStatus", catalogStatus.name());
		map.put("assessmentStatus", assessmentStatus.name());
		map.put("latestAssessmentId", latestAssessmentId);
		map.put("minAgeMonths", minAgeMonths);
		map.put("maxAgeMonths", maxAgeMonths);
		map.put("handle", handle);
		map.put("thumbnailUrl", thumbnailUrl);
		map.put("subscriberCount", subscriberCount);
		map.put("createdAt", timestamp(createdAt));
		map.put("updatedAt", timestamp(updatedAt));
		map.put("lastVerifiedAt", timestamp(lastVerifiedAt));
		map.put("feedUrl", feedUrl);
		return map;
	}

	public static ContentSource fromMap(String id, Map<String, Object> data) {
		return new ContentSource(
				id,
				string(data, "name"),
				string(data, "normalizedName"),
				string(data, "sourceType"),
				string(data, "platform"),
				string(data, "canonicalUrl"),
				string(data, "platformSourceId"),
				string(data, "description"),
				strings(data, "tagIds"),
				strings(data, "originTypes"),
				Statuses.parse(Statuses.Catalog.class, string(data, "catalogStatus"), Statuses.Catalog.UNDER_REVIEW),
				Statuses.parse(Statuses.Assessment.class, string(data, "assessmentStatus"), Statuses.Assessment.PENDING),
				string(data, "latestAssessmentId"),
				integer(data, "minAgeMonths"),
				integer(data, "maxAgeMonths"),
				string(data, "handle"),
				string(data, "thumbnailUrl"),
				longValue(data, "subscriberCount"),
				instant(data.get("createdAt")),
				instant(data.get("updatedAt")),
				instant(data.get("lastVerifiedAt")),
				string(data, "feedUrl"));
	}
}
