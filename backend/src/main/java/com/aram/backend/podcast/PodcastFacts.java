package com.aram.backend.podcast;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.SourceDescriptor;
import com.aram.backend.model.ChannelFacts;

/**
 * A podcast show as found in Apple's directory (and, once read, its feed). {@code explicit} is true when either the
 * directory or the feed marks the show explicit; such shows are always refused.
 */
public record PodcastFacts(
		long collectionId,
		String title,
		String author,
		String description,
		String feedUrl,
		String artworkUrl,
		boolean explicit,
		String genre
) {

	public String sourceId() {
		return ContentSource.idForPodcast(collectionId);
	}

	public String canonicalUrl() {
		return "https://podcasts.apple.com/podcast/id" + collectionId;
	}

	public SourceDescriptor toDescriptor() {
		return new SourceDescriptor(sourceId(), ContentSource.PODCAST_FEED, ContentSource.APPLE_PODCASTS,
				Long.toString(collectionId), canonicalUrl(), title, description, author, artworkUrl, null, feedUrl);
	}

	/** How a show is offered to the parent as a candidate card. */
	public ChannelFacts toCandidate() {
		return new ChannelFacts(Long.toString(collectionId), title, author, description, artworkUrl, null, null,
				ChannelFacts.PODCAST);
	}

	public PodcastFacts withDescription(String newDescription, boolean explicitFromFeed) {
		return new PodcastFacts(collectionId, title, author, newDescription, feedUrl, artworkUrl,
				explicit || explicitFromFeed, genre);
	}
}
