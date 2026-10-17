package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.Statuses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.aram.backend.catalog.builder.BuilderFixtures.channelId;
import static com.aram.backend.catalog.builder.BuilderFixtures.show;
import static com.aram.backend.catalog.builder.BuilderFixtures.youtube;
import static org.assertj.core.api.Assertions.assertThat;

class CandidateFilterTest {

	@Test
	void knownDuplicateTinyExplicitAndNotMadeForKidsCandidatesAreAllHandledBeforeAnyModelCall() {
		var existing = List.of(InMemoryStores.source(channelId('a'), "Known channel", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "science"));
		List<Candidate> found = List.of(
				youtube('a', "Known channel", 5_000L, true),
				youtube('b', "Good channel", 50_000L, true),
				youtube('b', "Good channel", 50_000L, true),
				youtube('c', "Tiny channel", 12L, true),
				youtube('d', "Teen channel", 900_000L, false),
				youtube('e', "Unknown flag channel", 8_000L, null),
				Candidate.podcast(show(1L, "Grown-up show", true), "q", "science"),
				Candidate.podcast(show(2L, "Kids show", false), "q", "science"));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, existing, 1000, Set.of());

		assertThat(filtered.keep()).extracting(Candidate::name).containsExactly("Good channel", "Kids show", "Unknown flag channel");
		assertThat(filtered.held()).extracting(h -> h.candidate().name()).containsExactly("Teen channel");
		assertThat(filtered.held().get(0).reason()).contains("not made for kids");
		assertThat(filtered.dropped()).containsEntry("already in the catalog", 1).containsEntry("duplicate in this run", 1)
				.containsEntry("too small", 1).containsEntry("marked explicit", 1);
	}

	@Test
	void aHeldBackChannelIsKeptOnlyWhenTheUserNamesItExplicitly() {
		List<Candidate> found = List.of(youtube('d', "Teen channel", 900_000L, false));

		assertThat(CandidateFilter.filter(found, List.of(), 1000, Set.of()).keep()).isEmpty();
		assertThat(CandidateFilter.filter(found, List.of(), 1000, Set.of(channelId('d'))).keep()).hasSize(1);
	}

	@Test
	void aDifferentChannelWithTheSameNameAsAKnownOneIsFlaggedAsAPossibleLookAlike() {
		var existing = List.of(InMemoryStores.source(channelId('a'), "Bluey - Official Channel", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "emotions"));
		List<Candidate> found = List.of(youtube('z', "bluey official channel", 20_000L, true), youtube('y', "Something else", 20_000L, true));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, existing, 1000, Set.of());

		assertThat(filtered.keep()).hasSize(2);
		assertThat(filtered.lookalikes()).containsOnlyKeys("youtube_" + channelId('z')).containsValue("Bluey - Official Channel");
	}

	@Test
	void podcastsOutsideTheChildrensGenresAreNeverAssessed() {
		com.aram.backend.podcast.PodcastFacts adults = new com.aram.backend.podcast.PodcastFacts(10L, "Mindful Parenting", "Pub", "", "https://f/1.xml",
				null, false, "Parenting");
		com.aram.backend.podcast.PodcastFacts radio = new com.aram.backend.podcast.PodcastFacts(11L, "Pet Talk Naturally", "Pub", "", "https://f/2.xml",
				null, false, "Pets & Animals");
		com.aram.backend.podcast.PodcastFacts stories = new com.aram.backend.podcast.PodcastFacts(12L, "Bedtime Tales", "Pub", "", "https://f/3.xml",
				null, false, "Stories for Kids");

		CandidateFilter.Filtered filtered = CandidateFilter.filter(List.of(Candidate.podcast(adults, "q", "x"), Candidate.podcast(radio, "q", "x"),
				Candidate.podcast(stories, "q", "x")), List.of(), 1000, Set.of());

		assertThat(filtered.keep()).extracting(Candidate::name).containsExactly("Bedtime Tales");
		assertThat(filtered.dropped()).containsEntry("not a children's genre", 2);
	}

	@Test
	void theAcceptedGenresAreConfigurable() {
		com.aram.backend.podcast.PodcastFacts science = new com.aram.backend.podcast.PodcastFacts(13L, "Science Hour", "Pub", "", "https://f/4.xml",
				null, false, "Science");

		assertThat(CandidateFilter.filter(List.of(Candidate.podcast(science, "q", "x")), List.of(), 1000, Set.of()).keep()).isEmpty();
		assertThat(CandidateFilter.filter(List.of(Candidate.podcast(science, "q", "x")), List.of(), 1000, Set.of(), List.of("Science")).keep()).hasSize(1);
	}

	@Test
	void namesMostlyInAnotherScriptAreSetAsideButTransliteratedOnesStillGoToReview() {
		List<Candidate> found = List.of(youtube('a', "\u0b9a\u0bbf\u0b9f\u0bcd\u0b9f\u0bbf \u0b95\u0bc1\u0bb4\u0ba8\u0bcd\u0ba4\u0bc8\u0b95\u0bb3\u0bcd", 50_000L, true),
				youtube('b', "Chitti kids rhymes", 50_000L, true));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, List.of(), 1000, Set.of());

		assertThat(filtered.keep()).extracting(Candidate::name).containsExactly("Chitti kids rhymes");
		assertThat(filtered.dropped()).containsKey("not in the Latin script (English first)");
	}

	@Test
	void twoNewCandidatesWithTheSameNameAreBothKeptButTheSecondIsFlaggedAgainstTheFirst() {
		List<Candidate> found = List.of(Candidate.podcast(show(20L, "Conductor Jack's Reading Party", false), "q", "x"),
				Candidate.podcast(show(21L, "Conductor Jack's Reading Party!", false), "q", "x"));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, List.of(), 1000, Set.of());

		assertThat(filtered.keep()).hasSize(2);
		assertThat(filtered.lookalikes()).containsOnlyKeys("podcast_21").containsValue("Conductor Jack's Reading Party");
	}

	@Test
	void regionalLanguageChannelsAreDroppedByNameOrDeclaredLanguageButEnglishOnesThatMerelyMentionALanguageSurvive() {
		var hindiDeclared = new com.aram.backend.model.ChannelFacts(channelId('d'), "Rhymes For Kids", "@r", "d", null, 50_000L, true, null, "hi");
		var englishDeclared = new com.aram.backend.model.ChannelFacts(channelId('e'), "Story Hour", "@s", "d", null, 50_000L, true, null, "en-US");
		List<Candidate> found = List.of(youtube('a', "Mr Shape - Hindi Rhyme And Kids Songs", 50_000L, true),
				youtube('b', "Bedtime Stories in TAMIL", 50_000L, true), youtube('c', "Telugu Kids Rhymes", 50_000L, true),
				Candidate.youtube(hindiDeclared, "q", "x"), Candidate.youtube(englishDeclared, "q", "x"),
				youtube('f', "Miss Sofie's Story Time", 50_000L, true), youtube('g', "Hindsight Science Lab", 50_000L, true));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, List.of(), 1000, Set.of());

		assertThat(filtered.keep()).extracting(Candidate::name).containsExactly("Story Hour", "Miss Sofie's Story Time", "Hindsight Science Lab");
		assertThat(filtered.dropped()).containsEntry("regional-language content (English first)", 4);
	}

	@Test
	void youtubesAutoGeneratedTopicChannelsAreDropped() {
		List<Candidate> found = List.of(youtube('a', "Super Simple Songs - Topic", 895_000L, true), youtube('b', "Real Songs Channel", 50_000L, true),
				youtube('c', "CoComelon - topic", 447_000L, true));

		CandidateFilter.Filtered filtered = CandidateFilter.filter(found, List.of(), 1000, Set.of());

		assertThat(filtered.keep()).extracting(Candidate::name).containsExactly("Real Songs Channel");
		assertThat(filtered.dropped()).containsEntry("auto-generated YouTube \"Topic\" channel", 2);
	}

	@Test
	void keptCandidatesAlternateBetweenChannelsAndPodcastsSoACappedRunAssessesBoth() {
		List<Candidate> found = List.of(youtube('a', "Channel A", 5_000L, true), youtube('b', "Channel B", 5_000L, true), youtube('c', "Channel C", 5_000L, true),
				Candidate.podcast(show(30L, "Show A", false), "q", "x"), Candidate.podcast(show(31L, "Show B", false), "q", "x"));

		List<Candidate> keep = CandidateFilter.filter(found, List.of(), 1000, Set.of()).keep();

		assertThat(keep).extracting(Candidate::name).containsExactly("Channel A", "Show A", "Channel B", "Show B", "Channel C");
		assertThat(keep.stream().limit(4).map(Candidate::kind)).containsExactly(Candidate.Kind.YOUTUBE, Candidate.Kind.PODCAST,
				Candidate.Kind.YOUTUBE, Candidate.Kind.PODCAST);
	}

	@Test
	void aSubscriberCountYouTubeHidesDoesNotDropTheChannel() {
		assertThat(CandidateFilter.filter(List.of(youtube('f', "Hidden count", null, true)), List.of(), 1000, Set.of()).keep()).hasSize(1);
	}
}
