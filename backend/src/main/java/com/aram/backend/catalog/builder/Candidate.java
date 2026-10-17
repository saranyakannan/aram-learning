package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.podcast.PodcastFacts;

/** A channel or podcast found by a search, before anyone has judged it. */
public record Candidate(
		Kind kind,
		String sourceId,
		String name,
		String link,
		Long subscribers,
		Boolean madeForKids,
		boolean explicit,
		String foundByQuery,
		String queryTag,
		ChannelFacts youtube,
		PodcastFacts podcast
) {

	public enum Kind { YOUTUBE, PODCAST }

	/** The Apple Podcasts genre of a show; null for a YouTube channel. */
	public String genre() {
		return podcast == null ? null : podcast.genre();
	}

	public static Candidate youtube(ChannelFacts facts, String query, String tag) {
		return new Candidate(Kind.YOUTUBE, ContentSource.idForYoutubeChannel(facts.channelId()), facts.title(),
				com.aram.backend.catalog.SourceDescriptor.youtube(facts).canonicalUrl(), facts.subscriberCount(), facts.madeForKids(),
				false, query, tag, facts, null);
	}

	public static Candidate podcast(PodcastFacts facts, String query, String tag) {
		return new Candidate(Kind.PODCAST, facts.sourceId(), facts.title(), facts.canonicalUrl(), null, null, facts.explicit(), query, tag,
				null, facts);
	}
}
