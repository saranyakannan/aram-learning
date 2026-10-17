package com.aram.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single YouTube video surfaced for a curated channel.
 *
 * <p>Deliberately a mutable POJO (not a record) so the Firestore client library can
 * read and write it directly when caching video lists.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Video {
	private String videoId;
	private String title;
	private String description;
	private String thumbnailUrl;
	private String publishedAt;
	private String channelId;
	private String channelTitle;
	private String videoUrl;
	/** Public view count from YouTube, when known; null for older cached entries. */
	private Long viewCount;
	/** Length in seconds, when known (used to keep Shorts out of the top picks). */
	private Integer durationSeconds;
}
