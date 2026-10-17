package com.aram.backend.model;

/**
 * Public facts about a channel or podcast shown to the parent as a candidate. {@code subscriberCount} and
 * {@code madeForKids} are null when unknown; {@code kind} is null for a YouTube channel and {@code PODCAST} for a show
 * (for a podcast {@code channelId} is the Apple Podcasts id and {@code handle} the publisher).
 */
public record ChannelFacts(
		String channelId,
		String title,
		String handle,
		String description,
		String thumbnailUrl,
		Long subscriberCount,
		Boolean madeForKids,
		String kind,
		String language
) {
	public static final String PODCAST = "PODCAST";

	/** A channel or show with no declared language. */
	public ChannelFacts(String channelId, String title, String handle, String description, String thumbnailUrl, Long subscriberCount,
			Boolean madeForKids, String kind) {
		this(channelId, title, handle, description, thumbnailUrl, subscriberCount, madeForKids, kind, null);
	}

	/** A YouTube channel (the only kind before podcasts existed). */
	public ChannelFacts(String channelId, String title, String handle, String description, String thumbnailUrl,
			Long subscriberCount, Boolean madeForKids) {
		this(channelId, title, handle, description, thumbnailUrl, subscriberCount, madeForKids, null, null);
	}
}
