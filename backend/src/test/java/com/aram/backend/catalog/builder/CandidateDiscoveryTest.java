package com.aram.backend.catalog.builder;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.exception.PodcastLookupException;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.podcast.PodcastDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.builder.BuilderFixtures.channel;
import static com.aram.backend.catalog.builder.BuilderFixtures.show;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateDiscoveryTest {

	private final YoutubeChannelLookup youtube = mock(YoutubeChannelLookup.class);
	private final PodcastDirectory podcasts = mock(PodcastDirectory.class);
	private final CatalogQueries queries = mock(CatalogQueries.class);
	private CandidateDiscovery discovery;

	@BeforeEach
	void setUp() {
		when(queries.plan()).thenReturn(new CatalogQueries.Plan(
				List.of(new CatalogQueries.Query("science", "q1"), new CatalogQueries.Query("math", "q2"), new CatalogQueries.Query("art", "q3")),
				List.of(new CatalogQueries.Query("bedtime-stories", "p1"), new CatalogQueries.Query("science", "p2"))));
		when(youtube.searchChannels(anyString())).thenReturn(List.of(channel('a', "A", 5000L, true)));
		when(podcasts.search(anyString())).thenReturn(List.of(show(1L, "Show", false)));
		discovery = new CandidateDiscovery(youtube, podcasts, queries);
	}

	@Test
	void youtubeSearchesAreCappedAndTheQuotaCostIsReported() {
		CandidateDiscovery.Discovery result = discovery.discover(true, false, 2);

		assertThat(result.youtubeSearches()).isEqualTo(2);
		assertThat(result.quotaUnits()).isEqualTo(2 * CandidateDiscovery.QUOTA_UNITS_PER_SEARCH).isEqualTo(202);
		assertThat(result.candidates()).hasSize(2);
		assertThat(result.notes()).anyMatch(n -> n.contains("cap reached"));
		verify(youtube, times(2)).searchChannels(anyString());
		verify(podcasts, never()).search(any());
	}

	@Test
	void everyCandidateRemembersWhichQueryFoundItAndWhichTagTheQueryWasFor() {
		CandidateDiscovery.Discovery result = discovery.discover(true, true, 40);

		assertThat(result.candidates()).extracting(Candidate::foundByQuery).containsExactly("q1", "q2", "q3", "p1", "p2");
		assertThat(result.candidates()).extracting(Candidate::queryTag).containsExactly("science", "math", "art", "bedtime-stories", "science");
		assertThat(result.candidates()).extracting(Candidate::kind).containsExactly(Candidate.Kind.YOUTUBE, Candidate.Kind.YOUTUBE,
				Candidate.Kind.YOUTUBE, Candidate.Kind.PODCAST, Candidate.Kind.PODCAST);
	}

	@Test
	void whenYoutubeStopsAnsweringDiscoveryStopsSpendingQuotaButPodcastsStillRun() {
		when(youtube.searchChannels("q2")).thenThrow(new YoutubeIntegrationException("quota exceeded", null));

		CandidateDiscovery.Discovery result = discovery.discover(true, true, 40);

		assertThat(result.quotaStopped()).isTrue();
		assertThat(result.youtubeSearches()).isEqualTo(2);
		verify(youtube, never()).searchChannels("q3");
		assertThat(result.candidates()).extracting(Candidate::kind).contains(Candidate.Kind.PODCAST);
		assertThat(result.notes()).anyMatch(n -> n.contains("stopped"));
	}

	@Test
	void aFailingPodcastSearchIsNotedAndTheRestContinue() {
		when(podcasts.search("p1")).thenThrow(new PodcastLookupException("down"));

		CandidateDiscovery.Discovery result = discovery.discover(false, true, 40);

		assertThat(result.candidates()).hasSize(1);
		assertThat(result.notes()).anyMatch(n -> n.contains("p1"));
		assertThat(result.youtubeSearches()).isZero();
	}
}
