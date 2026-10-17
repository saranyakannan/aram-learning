package com.aram.backend.catalog;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.model.Category;
import com.aram.backend.model.Channel;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.Tag;
import com.aram.backend.service.ChannelCatalogService;
import com.aram.backend.service.TagCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogSeederTest {

	private final InMemoryStores.Catalog repo = new InMemoryStores.Catalog();
	private final TagCatalogService taxonomy = mock(TagCatalogService.class);
	private final ChannelCatalogService curated = mock(ChannelCatalogService.class);
	private final YoutubeChannelLookup lookup = mock(YoutubeChannelLookup.class);
	private final com.aram.backend.service.CuratedPodcastCatalog curatedPodcasts =
			mock(com.aram.backend.service.CuratedPodcastCatalog.class);
	private final com.aram.backend.podcast.PodcastDirectory directory =
			mock(com.aram.backend.podcast.PodcastDirectory.class);
	private CatalogSeeder seeder;

	@BeforeEach
	void setUp() {
		when(taxonomy.getCategories()).thenReturn(List.of(new Category("math-counting", "Math & Counting", "d", "calculate", 3)));
		when(taxonomy.getTags()).thenReturn(List.of(new Tag("math", "Math & Numbers", "calculate", "math-counting", "d", 24, 96)));
		when(curatedPodcasts.getPodcasts()).thenReturn(List.of());
		seeder = new CatalogSeeder(repo, taxonomy, curated, lookup, curatedPodcasts, directory,
				Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC));
	}

	private static Channel channel(String slug, String handle, String youtubeId, String... tags) {
		return new Channel(slug, slug + " name", "Math", slug + " description", youtubeId, handle, 2, 4, List.of(tags));
	}

	@Test
	void itSeedsTheTaxonomyAndOneApprovedSourceWithAnAssessmentPerCuratedChannel() {
		String yt = youtubeId('n');
		when(curated.getChannels()).thenReturn(List.of(channel("numberblocks", "@Numberblocks", null, "math", "not-a-tag")));
		when(lookup.findByHandle("@Numberblocks")).thenReturn(
				Optional.of(new ChannelFacts(yt, "Numberblocks", "@Numberblocks", "yt text", "https://t/n.jpg", 9L, true)));

		int[] result = seeder.seed();

		assertThat(result).containsExactly(1, 0, 0);
		assertThat(repo.categories).containsKey("math-counting");
		assertThat(repo.tags).containsKey("math");
		ContentSource source = repo.sources.get("youtube_" + yt);
		assertThat(source.name()).isEqualTo("numberblocks name");
		assertThat(source.originTypes()).containsExactly("PREPOPULATED");
		assertThat(source.assessmentStatus()).isEqualTo(Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		assertThat(source.catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
		assertThat(source.tagIds()).containsExactly("math");
		assertThat(source.minAgeMonths()).isEqualTo(24);
		assertThat(source.maxAgeMonths()).isEqualTo(59);
		assertThat(source.thumbnailUrl()).isEqualTo("https://t/n.jpg");
		ContentAssessment assessment = repo.assessments.get(source.latestAssessmentId());
		assertThat(assessment.assessorType()).isEqualTo(Statuses.Assessor.CURATED_SEED);
		assertThat(assessment.evidenceLevel()).isEqualTo("DEVELOPER_REVIEW");
		assertThat(assessment.reviewedBy()).isEqualTo("seed:channels.json");
		assertThat(assessment.limitations()).anyMatch(text -> text.contains("not reviewed"));
	}

	private static com.aram.backend.service.CuratedPodcastCatalog.CuratedPodcast curatedShow(long id, String name) {
		return new com.aram.backend.service.CuratedPodcastCatalog.CuratedPodcast(id, name, name + " description", 4, 8,
				List.of("math", "not-a-tag"));
	}

	@Test
	void itSeedsCuratedPodcastsAsPodcastSourcesWithTheirFeedAndSkipsExplicitOrMissingOnes() {
		when(curated.getChannels()).thenReturn(List.of());
		when(curatedPodcasts.getPodcasts()).thenReturn(List.of(curatedShow(1233834541L, "Wow"), curatedShow(2L, "Explicit one"),
				curatedShow(3L, "Missing one")));
		when(directory.findById(1233834541L)).thenReturn(Optional.of(new com.aram.backend.podcast.PodcastFacts(
				1233834541L, "Wow in the World", "Tinkercast", "", "https://rss.art19.com/wow-in-the-world",
				"https://img.example.com/a.jpg", false, "Education for Kids")));
		when(directory.findById(2L)).thenReturn(Optional.of(new com.aram.backend.podcast.PodcastFacts(
				2L, "Explicit one", "Pub", "", "https://f.example.com/x", null, true, "Comedy")));
		when(directory.findById(3L)).thenReturn(Optional.empty());

		int[] result = seeder.seed();

		assertThat(result).containsExactly(1, 0, 2);
		ContentSource show = repo.sources.get("podcast_1233834541");
		assertThat(show.sourceType()).isEqualTo("PODCAST_FEED");
		assertThat(show.platform()).isEqualTo("APPLE_PODCASTS");
		assertThat(show.name()).isEqualTo("Wow");
		assertThat(show.feedUrl()).isEqualTo("https://rss.art19.com/wow-in-the-world");
		assertThat(show.handle()).isEqualTo("Tinkercast");
		assertThat(show.tagIds()).containsExactly("math");
		assertThat(show.minAgeMonths()).isEqualTo(48);
		assertThat(show.maxAgeMonths()).isEqualTo(107);
		assertThat(show.assessmentStatus()).isEqualTo(Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		assertThat(repo.assessments.get(show.latestAssessmentId()).reviewedBy()).isEqualTo("seed:podcasts.json");
		assertThat(repo.sources).containsOnlyKeys("podcast_1233834541");
		assertThat(seeder.seed()).containsExactly(0, 1, 2);
	}

	@Test
	void itResolvesByChannelIdWhenOneIsGivenAndIsSafeToRunTwice() {
		String yt = youtubeId('b');
		when(curated.getChannels()).thenReturn(List.of(channel("bluey", null, yt, "math")));
		when(lookup.findById(yt)).thenReturn(Optional.of(new ChannelFacts(yt, "Bluey", "@bluey", "", null, 1L, true)));

		assertThat(seeder.seed()).containsExactly(1, 0, 0);
		assertThat(seeder.seed()).containsExactly(0, 1, 0);

		assertThat(repo.sources).hasSize(1);
		assertThat(repo.assessments).hasSize(1);
	}

	@Test
	void aChannelYoutubeCannotResolveIsCountedAndSkippedWithoutStoppingTheRest() {
		String yt = youtubeId('c');
		when(curated.getChannels()).thenReturn(List.of(channel("ghost", "@Ghost", null, "math"), channel("real", "@Real", null, "math")));
		when(lookup.findByHandle("@Ghost")).thenReturn(Optional.empty());
		when(lookup.findByHandle("@Real")).thenReturn(Optional.of(new ChannelFacts(yt, "Real", "@Real", "", null, 1L, true)));

		assertThat(seeder.seed()).containsExactly(1, 0, 1);
		assertThat(repo.sources).containsOnlyKeys("youtube_" + yt);
	}
}
