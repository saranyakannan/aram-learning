package com.aram.backend.service;

import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.model.ChannelRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationServiceTest {

	private static final String PARENT = TestWorld.PARENT;

	private TestWorld world;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		world.onboard(PARENT, 48);
	}

	private List<String> names() {
		return world.recommendations.recommend(PARENT).stream().map(r -> r.channel().name()).toList();
	}

	@Test
	void nothingIsSuggestedUntilTheChildAndInterestsExist() {
		assertThat(world.recommendations.recommend(TestWorld.OTHER_PARENT)).isEmpty();
		assertThat(names()).isEmpty();
	}

	@Test
	void candidatesMustOverlapTheInterestsAndPassTheSharedEligibilityFilter() {
		world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "Songs", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"));
		world.addSource(source(youtubeId('c'), "Too old", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 96, 143, "math"));
		world.addSource(source(youtubeId('d'), "Not reviewed", Statuses.Assessment.ASSESSED, 24, 71, "math"));
		world.addSource(source(youtubeId('e'), "Rejected", Statuses.Assessment.REJECTED, 24, 71, "math"));
		world.profiles.saveTags(PARENT, List.of("math"));

		assertThat(names()).containsExactly("Counting");
	}

	@Test
	void moreMatchingTagsRankFirstAndFocusedSourcesWinTies() {
		world.addSource(source(youtubeId('a'), "Two tags", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math", "music"));
		world.addSource(source(youtubeId('b'), "Focused", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('c'), "Broad", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math", "nature", "emotions"));
		world.profiles.saveTags(PARENT, List.of("math", "music"));

		List<ChannelRecommendation> result = world.recommendations.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("Two tags", "Focused", "Broad");
		assertThat(result.get(0).matchedTags()).containsExactly("math", "music");
		assertThat(result).allMatch(ChannelRecommendation::ageFit);
	}

	@Test
	void aSourceAimedAYearOlderIsOfferedButListedAfterThoseInTheChildsRangeAndMarkedAsSuch() {
		world.addSource(source(youtubeId('a'), "Right age, one tag", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));
		world.addSource(source(youtubeId('b'), "A year older, two tags", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "math", "music"));
		world.addSource(source(youtubeId('c'), "Two years older", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 72, 119, "math", "music"));
		world.profiles.saveTags(PARENT, List.of("math", "music"));

		List<ChannelRecommendation> result = world.recommendations.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("Right age, one tag", "A year older, two tags");
		assertThat(result.get(0).ageFit()).isTrue();
		assertThat(result.get(1).ageFit()).as("flagged so the parent sees it is aimed older").isFalse();
	}

	@Test
	void channelsAlreadyApprovedForTheChildAreNotSuggestedAgain() {
		var approved = world.addSource(source(youtubeId('a'), "Have it", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "New", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.profiles.saveTags(PARENT, List.of("math"));
		world.profiles.saveChannels(PARENT, List.of(approved.id()));

		assertThat(names()).containsExactly("New");
	}
}
