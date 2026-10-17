package com.aram.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.model.Channel;
import com.aram.backend.model.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The hand-curated starter files are seed data for the whole catalog, so mistakes in them are caught here, not in Firestore. */
class CuratedDataTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private <T> T[] read(String file, Class<T[]> type) throws Exception {
		try (InputStream in = getClass().getResourceAsStream("/" + file)) {
			return mapper.readValue(in, type);
		}
	}

	@Test
	void everyCuratedChannelIsWellFormedAndUsesOnlyKnownTags() throws Exception {
		Set<String> knownTags = List.of(read("tags.json", Tag[].class)).stream().map(Tag::id).collect(Collectors.toSet());
		Channel[] channels = read("channels.json", Channel[].class);
		Set<String> ids = new HashSet<>();
		Set<String> handles = new HashSet<>();

		for (Channel channel : channels) {
			assertThat(ids.add(channel.id())).as("unique id " + channel.id()).isTrue();
			String identity = (channel.youtubeHandle() != null ? channel.youtubeHandle() : channel.youtubeChannelId()).toLowerCase(Locale.ROOT);
			assertThat(handles.add(identity)).as("unique channel " + identity).isTrue();
			assertThat(channel.name()).as(channel.id()).isNotBlank();
			assertThat(channel.description()).as(channel.id()).isNotBlank().hasSizeLessThan(400);
			assertThat(channel.tags()).as(channel.id() + " has tags").isNotEmpty().isSubsetOf(knownTags);
			assertThat(channel.minAge()).as(channel.id()).isNotNull().isBetween(0, 18);
			assertThat(channel.maxAge()).as(channel.id()).isNotNull().isBetween(channel.minAge(), 18);
		}
		assertThat(channels.length).isGreaterThanOrEqualTo(22);
	}

	@Test
	void everyCuratedPodcastIsWellFormedAndUsesOnlyKnownTags() throws Exception {
		Set<String> knownTags = List.of(read("tags.json", Tag[].class)).stream().map(Tag::id).collect(Collectors.toSet());
		CuratedPodcastCatalog.CuratedPodcast[] podcasts = read("podcasts.json", CuratedPodcastCatalog.CuratedPodcast[].class);
		Set<Long> appleIds = new HashSet<>();

		for (CuratedPodcastCatalog.CuratedPodcast podcast : podcasts) {
			assertThat(appleIds.add(podcast.appleId())).as("unique Apple id " + podcast.appleId()).isTrue();
			assertThat(podcast.name()).isNotBlank();
			assertThat(podcast.description()).isNotBlank().hasSizeLessThan(400);
			assertThat(podcast.tags()).as(podcast.name() + " has tags").isNotEmpty().isSubsetOf(knownTags);
			assertThat(podcast.minAge()).as(podcast.name()).isNotNull().isBetween(0, 18);
			assertThat(podcast.maxAge()).as(podcast.name()).isNotNull().isBetween(podcast.minAge(), 18);
		}
		assertThat(podcasts.length).isGreaterThanOrEqualTo(8);
	}
}
