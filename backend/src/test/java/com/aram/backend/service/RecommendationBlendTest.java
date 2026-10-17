package com.aram.backend.service;

import com.aram.backend.catalog.SourceSignal;
import com.aram.backend.catalog.SourceSignalRepository;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.ChannelRecommendation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;

class RecommendationBlendTest {

	private static final String PARENT = TestWorld.PARENT;

	static class MemorySignals extends SourceSignalRepository {
		final Map<String, SourceSignal> stored = new HashMap<>();

		MemorySignals() {
			super(null);
		}

		@Override
		public java.util.Map<String, SourceSignal> findByIds(java.util.Collection<String> sourceIds) {
			java.util.Map<String, SourceSignal> found = new java.util.LinkedHashMap<>();
			sourceIds.forEach(id -> {
				if (stored.containsKey(id)) {
					found.put(id, stored.get(id));
				}
			});
			return found;
		}

		@Override
		public Optional<SourceSignal> find(String sourceId) {
			return Optional.ofNullable(stored.get(sourceId));
		}
	}

	private TestWorld world;
	private MemorySignals signals;
	private AramProperties.Ml settings;
	private RecommendationService service;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		world.onboard(PARENT, 48);
		signals = new MemorySignals();
		settings = new AramProperties.Ml();
		settings.setBlendEnabled(true);
		service = new RecommendationService(world.catalog, world.childCatalog, world.profiles, world.policy, signals, settings);
	}

	private static String sid(char letter) {
		return com.aram.backend.catalog.ContentSource.idForYoutubeChannel(youtubeId(letter));
	}

	private void neighbours(String id, String other, double similarity) {
		signals.stored.put(id, new SourceSignal(id, List.of(new SourceSignal.Neighbor(other, similarity)), 0, "x", "m", Instant.EPOCH));
	}

	private void approve(String sourceId) {
		var child = world.profiles.requireChild(PARENT);
		world.childCatalog.approve(PARENT, child.childId(), child.ageMonths(), List.of(sourceId));
	}

	private List<String> names() {
		return service.recommend(PARENT).stream().map(r -> r.channel().name()).toList();
	}

	@Test
	void aNeighbourOfSomethingTheChildHasIsSuggestedEvenWithoutATagMatch() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "Little Bear", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "nature"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		neighbours(have.id(), sid('b'), 0.9);

		List<ChannelRecommendation> result = service.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("Little Bear");
		assertThat(result.get(0).reason()).isEqualTo("Similar to Bluey");
		assertThat(result.get(0).matchedTags()).isEmpty();
	}

	@Test
	void aNeighbourTheEligibilityPolicyRefusesIsNeverSuggested() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "Not reviewed", Statuses.Assessment.ASSESSED, 24, 71, "nature"));
		world.addSource(source(youtubeId('c'), "Too old", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 96, 143, "nature"));
		world.addSource(source(youtubeId('d'), "Rejected", Statuses.Assessment.REJECTED, 24, 71, "nature"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		signals.stored.put(have.id(), new SourceSignal(have.id(), List.of(new SourceSignal.Neighbor(sid('b'), 0.99),
				new SourceSignal.Neighbor(sid('c'), 0.98), new SourceSignal.Neighbor(sid('d'), 0.97)), 0, "x", "m", Instant.EPOCH));

		assertThat(names()).isEmpty();
	}

	@Test
	void weakNeighboursBelowTheThresholdAreIgnored() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "Little Bear", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "nature"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		neighbours(have.id(), sid('b'), 0.5);

		assertThat(names()).isEmpty();
	}

	@Test
	void similarityRaisesTheRankOfATagMatchAndTheReasonIsShown() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "Alpha math", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('c'), "Beta math", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		neighbours(have.id(), sid('c'), 0.9);

		List<ChannelRecommendation> result = service.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("Beta math", "Alpha math");
		assertThat(result.get(0).reason()).isEqualTo("Similar to Bluey");
		assertThat(result.get(1).reason()).isNull();
	}

	@Test
	void sourcesInTheChildsAgeRangeStillComeBeforeThoseAimedOlderWhateverTheSimilarity() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "In range", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));
		world.addSource(source(youtubeId('c'), "A year older", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "math"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		neighbours(have.id(), sid('c'), 0.99);

		List<ChannelRecommendation> result = service.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("In range", "A year older");
		assertThat(result.get(1).ageFit()).isFalse();
	}

	@Test
	void withTheFlagOffSignalsAreIgnoredAndTheTagRankingIsUnchanged() {
		var have = world.addSource(source(youtubeId('a'), "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		world.addSource(source(youtubeId('b'), "Little Bear", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "nature"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(have.id());
		neighbours(have.id(), sid('b'), 0.9);
		settings.setBlendEnabled(false);

		assertThat(names()).isEmpty();
	}

	@Test
	void anApprovedSourceIsNeverSuggestedBack() {
		var one = world.addSource(source(youtubeId('a'), "One", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		var two = world.addSource(source(youtubeId('b'), "Two", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(one.id());
		approve(two.id());
		neighbours(one.id(), two.id(), 0.95);

		assertThat(names()).isEmpty();
	}
	@Test
	void aChildWithNoApprovalsStillGetsNeighboursOfItsBestTagMatches() {
		var counting = world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "Songs", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"));
		world.profiles.saveTags(PARENT, List.of("math"));
		neighbours(counting.id(), sid('b'), 0.9);

		List<ChannelRecommendation> result = service.recommend(PARENT);

		assertThat(result).extracting(r -> r.channel().name()).containsExactly("Counting", "Songs");
		assertThat(result.get(0).reason()).isNull();
		assertThat(result.get(1).reason()).isEqualTo("Similar to Counting");
		assertThat(result.get(1).matchedTags()).isEmpty();
	}

	@Test
	void aSeedsNeighbourThePolicyRefusesIsStillNeverSuggested() {
		var counting = world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "Not reviewed", Statuses.Assessment.ASSESSED, 24, 71, "music"));
		world.addSource(source(youtubeId('c'), "Too old", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 96, 143, "music"));
		world.profiles.saveTags(PARENT, List.of("math"));
		signals.stored.put(counting.id(), new SourceSignal(counting.id(), List.of(new SourceSignal.Neighbor(sid('b'), 0.99),
				new SourceSignal.Neighbor(sid('c'), 0.98)), 0, "x", "m", Instant.EPOCH));

		assertThat(names()).containsExactly("Counting");
	}

	@Test
	void seedCountZeroKeepsTheOldBehaviourOfOnlyUsingApprovedSources() {
		var counting = world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "Songs", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"));
		world.profiles.saveTags(PARENT, List.of("math"));
		neighbours(counting.id(), sid('b'), 0.9);
		settings.setSeedCount(0);

		assertThat(names()).containsExactly("Counting");
	}

	@Test
	void onceTheChildHasEnoughApprovalsTagMatchesStopActingAsSeeds() {
		var one = world.addSource(source(youtubeId('d'), "One", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		var two = world.addSource(source(youtubeId('e'), "Two", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		var three = world.addSource(source(youtubeId('f'), "Three", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "emotions"));
		var counting = world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		world.addSource(source(youtubeId('b'), "Songs", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "music"));
		world.profiles.saveTags(PARENT, List.of("math"));
		approve(one.id());
		approve(two.id());
		approve(three.id());
		neighbours(counting.id(), sid('b'), 0.9);

		assertThat(names()).containsExactly("Counting");
	}
}
