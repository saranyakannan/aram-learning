package com.aram.backend.catalog;

import com.aram.backend.model.ChannelFacts;

/**
 * What the catalog needs to know to record a new source, whatever the platform. Built from a YouTube channel's facts
 * or a podcast's facts; everything after this point (assessment, staging, approval) treats them the same.
 */
public record SourceDescriptor(
		String sourceId,
		String sourceType,
		String platform,
		String platformSourceId,
		String canonicalUrl,
		String name,
		String description,
		String handle,
		String thumbnailUrl,
		Long subscriberCount,
		String feedUrl
) {

	public static final String YOUTUBE_LIMITATION =
			"Assessed from the channel description and recent video titles only; no video, audio or transcript was reviewed.";
	public static final String PODCAST_LIMITATION =
			"Assessed from the show description, recent episode titles and the explicit flag only; no audio was reviewed.";

	public static SourceDescriptor youtube(ChannelFacts facts) {
		String canonical = facts.handle() != null && facts.handle().startsWith("@")
				? "https://www.youtube.com/" + facts.handle()
				: "https://www.youtube.com/channel/" + facts.channelId();
		return new SourceDescriptor(ContentSource.idForYoutubeChannel(facts.channelId()), ContentSource.YOUTUBE_CHANNEL,
				ContentSource.YOUTUBE, facts.channelId(), canonical, facts.title(), facts.description(), facts.handle(),
				facts.thumbnailUrl(), facts.subscriberCount(), null);
	}

	public boolean isPodcast() {
		return ContentSource.PODCAST_FEED.equals(sourceType);
	}

	public String limitation() {
		return isPodcast() ? PODCAST_LIMITATION : YOUTUBE_LIMITATION;
	}

	/** The kind of submission recorded for this source (spec 3.9 {@code submissionType}). */
	public String submissionType() {
		return isPodcast() ? "PODCAST" : "YOUTUBE_CHANNEL";
	}
}
