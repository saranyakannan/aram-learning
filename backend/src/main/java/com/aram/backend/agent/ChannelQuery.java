package com.aram.backend.agent;

/** What a parent typed, classified so only well-formed ids ever reach the YouTube API. */
public sealed interface ChannelQuery {

	record ById(String channelId) implements ChannelQuery {
	}

	/** Includes the leading {@code @}. */
	record ByHandle(String handle) implements ChannelQuery {
	}

	record ByVideoId(String videoId) implements ChannelQuery {
	}

	record ByName(String name) implements ChannelQuery {
	}

	record Unsupported(String reason) implements ChannelQuery {
	}
}
