package com.aram.backend.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A channel as the UI and the video service see it: a content source from the Firestore catalog (or, for the
 * seed job, an entry of the local {@code channels.json}).
 * Exactly one of {@code youtubeChannelId} or {@code youtubeHandle} should be set;
 * the handle (e.g. {@code @somechannel}, as shown in the channel's URL) is the
 * easiest for a parent to look up and paste in.
 *
 * <p>{@code tags} are the interest topics parents pick during onboarding; the set of
 * tags offered in the UI is derived from the tags present on the catalog's channels.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Channel(
		String id,
		String name,
		String category,
		String description,
		String youtubeChannelId,
		String youtubeHandle,
		Integer minAge,
		Integer maxAge,
		List<String> tags,
		String thumbnailUrl,
		String sourceType
) {
	public static final String YOUTUBE_CHANNEL = "YOUTUBE_CHANNEL";
	public static final String PODCAST_FEED = "PODCAST_FEED";

	public Channel {
		tags = tags == null ? List.of() : List.copyOf(tags);
		sourceType = sourceType == null ? YOUTUBE_CHANNEL : sourceType;
	}

	public Channel(String id, String name, String category, String description, String youtubeChannelId,
			String youtubeHandle, Integer minAge, Integer maxAge, List<String> tags, String thumbnailUrl) {
		this(id, name, category, description, youtubeChannelId, youtubeHandle, minAge, maxAge, tags, thumbnailUrl, null);
	}

	public Channel(String id, String name, String category, String description, String youtubeChannelId,
			String youtubeHandle, Integer minAge, Integer maxAge, List<String> tags) {
		this(id, name, category, description, youtubeChannelId, youtubeHandle, minAge, maxAge, tags, null, null);
	}
}
