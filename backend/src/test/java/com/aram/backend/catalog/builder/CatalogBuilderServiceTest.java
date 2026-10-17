package com.aram.backend.catalog.builder;

import com.aram.backend.agent.ChannelSafetyService;
import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.SafetyAssessment;
import com.aram.backend.model.Tag;
import com.aram.backend.podcast.PodcastFeedParser;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.service.TagCatalogService;
import com.aram.backend.service.YoutubeVideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.aram.backend.catalog.builder.BuilderFixtures.channelId;
import static com.aram.backend.catalog.builder.BuilderFixtures.cleared;
import static com.aram.backend.catalog.builder.BuilderFixtures.refused;
import static com.aram.backend.catalog.builder.BuilderFixtures.show;
import static com.aram.backend.catalog.builder.BuilderFixtures.youtube;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogBuilderServiceTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T10:00:00Z"), ZoneOffset.UTC);

	@TempDir
	Path reviewDir;

	private final InMemoryStores.Catalog repo = new InMemoryStores.Catalog();
	private final CandidateDiscovery discovery = mock(CandidateDiscovery.class);
	private final ChannelSafetyService safety = mock(ChannelSafetyService.class);
	private final YoutubeVideoService videos = mock(YoutubeVideoService.class);
	private final PodcastFeedService feeds = mock(PodcastFeedService.class);
	private final TagCatalogService taxonomy = mock(TagCatalogService.class);
	private AramProperties properties;
	private CatalogBuilderService service;

	@BeforeEach
	void setUp() {
		when(taxonomy.getTags()).thenReturn(List.of(new Tag("science", "Science", "science"), new Tag("art", "Art", "draw"),
				new Tag("bedtime-stories", "Bedtime", "nightlight"), new Tag("podcasts", "Podcasts", "podcasts")));
		when(taxonomy.categoryNameForTag(any())).thenReturn("Science & Discovery");
		properties = new AramProperties();
		properties.getBuilder().setReviewDir(reviewDir.toString());
		CatalogService catalog = new CatalogService(repo, taxonomy, CLOCK);
		CandidateAssessor assessor = new CandidateAssessor(safety, catalog, videos, feeds, properties);
		service = new CatalogBuilderService(discovery, assessor, repo, properties, CLOCK);
		when(videos.fetchLatestVideos(any())).thenReturn(List.of());
		when(feeds.fetchFeed(any())).thenReturn(new PodcastFeedParser.Feed("Show", "About the show", false, null, List.of()));
	}

	private void found(Candidate... candidates) {
		when(discovery.discover(anyBoolean(), anyBoolean(), anyInt())).thenReturn(
				new CandidateDiscovery.Discovery(List.of(candidates), 3, 303, false, List.of()));
	}

	private static boolean anyBoolean() {
		return org.mockito.ArgumentMatchers.anyBoolean();
	}

	private CatalogBuilderService.Options options(boolean dryRun, int maxNew) {
		return new CatalogBuilderService.Options(dryRun, 40, maxNew, true, true, Set.of());
	}

	private List<Path> files() throws Exception {
		try (Stream<Path> stream = Files.list(reviewDir)) {
			return stream.toList();
		}
	}

	@Test
	void aRunStoresClearedAndRefusedCandidatesButNeverApprovesAnythingAndWritesAReviewSheet() throws Exception {
		found(youtube('a', "Good science", 50_000L, true), youtube('b', "Risky channel", 80_000L, true), youtube('c', "Teen science", 900_000L, false));
		when(safety.assess(any(), any(), anyInt())).thenAnswer(call -> ((com.aram.backend.model.ChannelFacts) call.getArgument(0)).title().startsWith("Good")
				? cleared(3, 5, "science") : refused());

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(report.assessed()).isEqualTo(2);
		assertThat(report.cleared()).isEqualTo(1);
		assertThat(report.refusedByModel()).isEqualTo(1);
		assertThat(report.held()).isEqualTo(1);
		ContentSource good = repo.sources.get("youtube_" + channelId('a'));
		assertThat(good.assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
		assertThat(good.catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
		assertThat(good.originTypes()).containsExactly(ContentSource.CATALOG_BUILDER);
		ContentSource risky = repo.sources.get("youtube_" + channelId('b'));
		assertThat(risky.assessmentStatus()).isEqualTo(Statuses.Assessment.REVIEW_REQUIRED);
		assertThat(risky.catalogStatus()).isEqualTo(Statuses.Catalog.UNDER_REVIEW);
		assertThat(repo.sources).doesNotContainKey("youtube_" + channelId('c'));
		assertThat(repo.sources.values()).as("the builder never approves for recommendation")
				.noneMatch(s -> s.assessmentStatus() == Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		verify(safety, never()).assess(org.mockito.ArgumentMatchers.argThat(f -> f != null && f.title().equals("Teen science")), any(), anyInt());
	}

	@Test
	void theReviewSheetListsReadyAndRefusedRowsWithBlankDecisionsAndTheHeldBackFileSaysHowToIncludeThem() throws Exception {
		found(youtube('a', "Good science", 50_000L, true), youtube('b', "Risky channel", 80_000L, true), youtube('c', "Teen science", 900_000L, false));
		when(safety.assess(any(), any(), anyInt())).thenAnswer(call -> ((com.aram.backend.model.ChannelFacts) call.getArgument(0)).title().startsWith("Good")
				? cleared(3, 5, "science") : refused());

		CatalogBuilderService.Report report = service.run(options(false, 40));

		List<Map<String, String>> rows = ReviewSheet.parse(Files.readString(report.reviewSheet()));
		assertThat(rows).hasSize(2);
		Map<String, String> ready = rows.stream().filter(r -> r.get("name").equals("Good science")).findFirst().orElseThrow();
		assertThat(ready).containsEntry("status", "READY (model cleared)").containsEntry("suggested_min_age_years", "3")
				.containsEntry("suggested_max_age_years", "5").containsEntry("suggested_tags", "science").containsEntry("decision", "")
				.containsEntry("model_verdict", "SAFE").containsEntry("subscribers", "50000");
		assertThat(rows.stream().filter(r -> r.get("name").equals("Risky channel")).findFirst().orElseThrow().get("status")).contains("MODEL REFUSED");
		String held = Files.readString(report.heldSheet());
		assertThat(held).contains("Teen science").contains("not made for kids").contains("--include-held=" + channelId('c'));
		assertThat(files()).hasSize(2);
	}

	@Test
	void aSheetTheBuilderWroteCanBeEditedAndAppliedEndToEnd() throws Exception {
		found(youtube('a', "Good science", 50_000L, true));
		when(safety.assess(any(), any(), anyInt())).thenReturn(cleared(3, 5, "science"));
		CatalogBuilderService.Report report = service.run(options(false, 40));
		String edited = Files.readString(report.reviewSheet()).replace("\r\n", "\n");
		String[] lines = edited.split("\n");
		lines[1] = lines[1].replaceAll(",,,,,$", ",approve,science;art,3,5,looks good");
		TagCatalogService tags = taxonomy;
		CatalogReviewService review = new CatalogReviewService(repo, tags, CLOCK);

		CatalogReviewService.Result result = review.apply(ReviewSheet.parse(String.join("\n", lines)), "owner");

		assertThat(result.approved()).isEqualTo(1);
		ContentSource approved = repo.sources.get("youtube_" + channelId('a'));
		assertThat(approved.assessmentStatus()).isEqualTo(Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		assertThat(approved.tagIds()).containsExactly("science", "art");
	}

	@Test
	void thePerRunCapLimitsHowManyCandidatesAreAssessed() throws Exception {
		found(youtube('a', "One", 5_000L, true), youtube('b', "Two", 5_000L, true), youtube('c', "Three", 5_000L, true));
		when(safety.assess(any(), any(), anyInt())).thenReturn(cleared(3, 5, "science"));

		CatalogBuilderService.Report report = service.run(options(false, 2));

		assertThat(report.assessed()).isEqualTo(2);
		assertThat(repo.sources).hasSize(2);
	}

	@Test
	void aDryRunAssessesAndStoresNothingAndWritesNoFiles() throws Exception {
		found(youtube('a', "Good science", 50_000L, true), youtube('c', "Teen science", 900_000L, false));

		CatalogBuilderService.Report report = service.run(options(true, 40));

		assertThat(report.assessed()).isZero();
		assertThat(report.held()).isEqualTo(1);
		assertThat(repo.sources).isEmpty();
		assertThat(repo.assessments).isEmpty();
		assertThat(files()).isEmpty();
		verifyNoInteractions(safety);
	}

	@Test
	void aCandidateWhoseSafetyCheckCouldNotRunIsSkippedAndNotStored() throws Exception {
		found(youtube('a', "Good science", 50_000L, true));
		when(safety.assess(any(), any(), anyInt())).thenReturn(SafetyAssessment.unavailable("down", "m", 1L));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(report.failed()).isEqualTo(1);
		assertThat(repo.sources).isEmpty();
		assertThat(report.reviewSheet()).isNull();
	}

	@Test
	void podcastsAreAssessedFromTheirFeedAndExplicitShowsNeverReachTheModel() throws Exception {
		found(Candidate.podcast(show(1L, "Calm stories", false), "p", "bedtime-stories"), Candidate.podcast(show(2L, "Adult show", true), "p", "science"));
		when(safety.assessPodcast(any(), any(), anyInt())).thenReturn(cleared(3, 8, "bedtime-stories", "podcasts"));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		ContentSource stored = repo.sources.get("podcast_1");
		assertThat(stored.isPodcast()).isTrue();
		assertThat(stored.originTypes()).containsExactly(ContentSource.CATALOG_BUILDER);
		assertThat(stored.feedUrl()).isEqualTo("https://feeds.example.com/1.xml");
		assertThat(stored.assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
		assertThat(repo.sources).doesNotContainKey("podcast_2");
		assertThat(report.dropped()).containsEntry("marked explicit", 1);
	}

	@Test
	void aPodcastWhoseFeedIsNotInEnglishIsSkippedWithoutAModelCall() throws Exception {
		found(Candidate.podcast(show(3L, "Histoires du soir", false), "p", "bedtime-stories"));
		when(feeds.fetchFeed(any())).thenReturn(new PodcastFeedParser.Feed("Histoires du soir", "Pour les enfants", false, null, List.of(), "fr-FR"));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(report.skippedOtherLanguage()).isEqualTo(1);
		assertThat(repo.sources).isEmpty();
		verifyNoInteractions(safety);
	}

	@Test
	void aFeedThatSaysEnglishOrNothingIsAssessedNormally() throws Exception {
		found(Candidate.podcast(show(4L, "English tales", false), "p", "bedtime-stories"), Candidate.podcast(show(5L, "Unlabelled tales", false), "p", "bedtime-stories"));
		when(feeds.fetchFeed("https://feeds.example.com/4.xml")).thenReturn(new PodcastFeedParser.Feed("E", "d", false, null, List.of(), "en-us"));
		when(safety.assessPodcast(any(), any(), anyInt())).thenReturn(cleared(3, 8, "bedtime-stories"));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(report.cleared()).isEqualTo(2);
	}

	@Test
	void aCandidateAlreadyInTheCatalogIsNeverAssessedAgain() throws Exception {
		repo.sources.put("youtube_" + channelId('a'), InMemoryStores.source(channelId('a'), "Good science", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "science"));
		found(youtube('a', "Good science", 50_000L, true));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(report.dropped()).containsEntry("already in the catalog", 1);
		verifyNoInteractions(safety);
	}

	@Test
	void aLookAlikeIsAssessedButFlaggedForTheReviewer() throws Exception {
		repo.sources.put("youtube_" + channelId('k'), InMemoryStores.source(channelId('k'), "Good science", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 71, "science"));
		found(youtube('z', "Good Science!", 50_000L, true));
		when(safety.assess(any(), any(), anyInt())).thenReturn(cleared(3, 5, "science"));

		CatalogBuilderService.Report report = service.run(options(false, 40));

		assertThat(ReviewSheet.parse(Files.readString(report.reviewSheet())).get(0).get("lookalike_of")).isEqualTo("Good science");
	}
}
