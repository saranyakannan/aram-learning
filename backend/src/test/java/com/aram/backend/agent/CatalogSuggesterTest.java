package com.aram.backend.agent;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.model.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class CatalogSuggesterTest {

	private static final String PARENT = TestWorld.PARENT;

	private TestWorld world;
	private CatalogSuggester suggester;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		world.onboard(PARENT, 48);
		when(world.taxonomy.getTags()).thenReturn(List.of(
				new Tag("math", "Math & Numbers", "calculate", "math-counting", null, 24, 96),
				new Tag("phonics-reading", "Phonics & Reading", "spellcheck", "language-literacy", null, 24, 96),
				new Tag("bedtime-stories", "Bedtime Stories", "nightlight", "stories-imagination", null, 12, 96),
				new Tag("podcasts", "Curiosity Podcasts", "podcasts", "stories-imagination", null, 36, 144)));
		suggester = new CatalogSuggester(world.recommendations, world.taxonomy);
	}

	private List<String> names(String topic) {
		return suggester.suggest(PARENT, topic).picks().stream().map(p -> p.card().title()).toList();
	}

	@Test
	void aParentsOwnWordsAreMappedToOneReviewedTopic() {
		assertThat(suggester.match("suggest a phonics channel").map(Tag::id)).contains("phonics-reading");
		assertThat(suggester.match("Letters and the alphabet").map(Tag::id)).contains("phonics-reading");
		assertThat(suggester.match("counting").map(Tag::id)).contains("math");
		assertThat(suggester.match("bedtime-stories").map(Tag::id)).contains("bedtime-stories");
		assertThat(suggester.match("Math & Numbers").map(Tag::id)).contains("math");
		assertThat(suggester.match("something about dinosaurs")).isEmpty();
		assertThat(suggester.match("   ")).isEmpty();
	}

	@Test
	void onlyReviewedSourcesThatSuitTheChildsAgeAndOnTheTopicComeBack() {
		world.addSource(source(youtubeId('a'), "Alphablocks", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "phonics-reading"));
		world.addSource(source(youtubeId('b'), "Teen Grammar", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 120, 200, "phonics-reading"));
		world.addSource(source(youtubeId('c'), "Unreviewed Reader", Statuses.Assessment.ASSESSED, 36, 71, "phonics-reading"));
		world.addSource(source(youtubeId('d'), "Counting Fun", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));

		CatalogSuggester.Suggestions result = suggester.suggest(PARENT, "suggest a phonics channel");

		assertThat(result.tag().id()).isEqualTo("phonics-reading");
		assertThat(names("suggest a phonics channel")).containsExactly("Alphablocks");
		CatalogSuggester.Pick pick = result.picks().get(0);
		assertThat(pick.card().channelId()).isEqualTo(youtubeId('a'));
		assertThat(pick.card().kind()).isNull();
		assertThat(pick.matchedTags()).containsExactly("phonics-reading");
	}

	@Test
	void whatTheChildAlreadyHasIsNotSuggestedAgain() {
		var have = world.addSource(source(youtubeId('a'), "Have It", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));
		world.addSource(source(youtubeId('b'), "New One", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));
		world.childCatalog.approve(PARENT, world.profiles.requireChild(PARENT).childId(), 48, List.of(have.id()));

		assertThat(names("math")).containsExactly("New One");
	}

	@Test
	void podcastsAreCardsOfKindPodcastAndTheWordsPodcastOrChannelNarrowTheList() {
		ContentSource show = world.addSource(new ContentSource("podcast_123456", "Story Show", "story show", ContentSource.PODCAST_FEED,
				ContentSource.APPLE_PODCASTS, "https://podcasts.apple.com/podcast/id123456", "123456", "Stories to fall asleep to.",
				List.of("bedtime-stories"), List.of(ContentSource.PREPOPULATED), Statuses.Catalog.ACTIVE,
				Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, 36, 71, "Story Co", "https://t/p.jpg", null,
				java.time.Instant.parse("2026-10-14T12:00:00Z"), java.time.Instant.parse("2026-10-14T12:00:00Z"),
				java.time.Instant.parse("2026-10-14T12:00:00Z"), "https://feeds.example/story.xml"));
		world.addSource(source(youtubeId('a'), "Story Channel", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "bedtime-stories"));

		assertThat(names("bedtime stories")).containsExactlyInAnyOrder("Story Show", "Story Channel");
		assertThat(names("bedtime story podcast")).containsExactly("Story Show");
		assertThat(names("a bedtime story channel")).containsExactly("Story Channel");
		CatalogSuggester.Pick pick = suggester.suggest(PARENT, "bedtime story podcast").picks().get(0);
		assertThat(pick.card().kind()).isEqualTo("PODCAST");
		assertThat(pick.card().channelId()).isEqualTo(show.platformSourceId());
	}

	@Test
	void aPodcastRequestWithNoSubjectUsesTheGeneralPodcastTopic() {
		world.addSource(source(youtubeId('a'), "Curious Ears", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "podcasts"));

		assertThat(suggester.suggest(PARENT, "any good podcasts?").tag().id()).isEqualTo("podcasts");
	}

	@Test
	void anUnknownTopicHasNoTagAndNothingIsInventedForIt() {
		CatalogSuggester.Suggestions result = suggester.suggest(PARENT, "dinosaur documentaries");

		assertThat(result.tag()).isNull();
		assertThat(result.picks()).isEmpty();
		assertThat(suggester.topicLabels()).contains("Phonics & Reading");
	}

	@Test
	void atMostFiveAreReturned() {
		for (char letter = 'a'; letter <= 'h'; letter++) {
			world.addSource(source(youtubeId(letter), "Maths " + letter, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));
		}

		assertThat(names("maths")).hasSize(CatalogSuggester.MAX_PICKS);
	}

	@Test
	void aParentWithNoChildProfileGetsNothing() {
		world.addSource(source(youtubeId('a'), "Counting", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "math"));

		assertThat(suggester.suggest(TestWorld.OTHER_PARENT, "math").picks()).isEmpty();
	}
}
