package com.aram.backend.model;

/**
 * One tile of the Kid Space "top picks" strip: either a video (opens the watch page) or a podcast episode (plays in place).
 * {@code sourceId} is the approved channel or show it came from; the strip only ever contains approved sources.
 */
public record TopPick(
		String type,
		String id,
		String sourceId,
		String sourceName,
		String title,
		String thumbnailUrl,
		String publishedAt,
		Long viewCount,
		String audioUrl,
		Integer durationSeconds
) {

	public static final String VIDEO = "VIDEO";
	public static final String EPISODE = "EPISODE";
}
