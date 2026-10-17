package com.aram.backend.catalog;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.model.Channel;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.Tag;
import com.aram.backend.podcast.PodcastDirectory;
import com.aram.backend.podcast.PodcastFacts;
import com.aram.backend.service.ChannelCatalogService;
import com.aram.backend.service.CuratedPodcastCatalog;
import com.aram.backend.service.TagCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fills Firestore from the reviewed files in the repository: the category/tag taxonomy, and one canonical source plus
 * a first assessment for every channel in {@code channels.json}. Safe to run repeatedly: a source that already exists is
 * left alone. Run it with {@code ARAM_SEED_ENABLED=true}; it is off by default so normal startups never write.
 *
 * <p>Curated channels are recorded as {@code APPROVED_FOR_RECOMMENDATION} by a developer review ({@code CURATED_SEED}),
 * with the limitation stated: they were chosen from public channel information and individual videos are not reviewed.
 */
@Component
@ConditionalOnProperty(prefix = "aram", name = "seed-enabled", havingValue = "true")
public class CatalogSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

	private final CatalogRepository repository;
	private final TagCatalogService taxonomy;
	private final ChannelCatalogService curated;
	private final YoutubeChannelLookup lookup;
	private final CuratedPodcastCatalog curatedPodcasts;
	private final PodcastDirectory podcastDirectory;
	private final Clock clock;

	public CatalogSeeder(CatalogRepository repository, TagCatalogService taxonomy, ChannelCatalogService curated,
			YoutubeChannelLookup lookup, CuratedPodcastCatalog curatedPodcasts, PodcastDirectory podcastDirectory,
			Clock clock) {
		this.repository = repository;
		this.taxonomy = taxonomy;
		this.curated = curated;
		this.lookup = lookup;
		this.curatedPodcasts = curatedPodcasts;
		this.podcastDirectory = podcastDirectory;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		seed();
	}

	/** Returns how many sources were created, skipped because they already existed, or failed to resolve. */
	public int[] seed() {
		Instant now = clock.instant();
		taxonomy.getCategories().forEach(category -> repository.saveCategory(category, now));
		taxonomy.getTags().forEach(tag -> repository.saveTag(tag, now));
		log.info("Seeded {} categories and {} tags", taxonomy.getCategories().size(), taxonomy.getTags().size());

		Set<String> knownTags = taxonomy.getTags().stream().map(Tag::id).collect(Collectors.toSet());
		int created = 0;
		int skipped = 0;
		int failed = 0;
		for (Channel channel : curated.getChannels()) {
			Optional<ChannelFacts> facts = resolve(channel);
			if (facts.isEmpty()) {
				log.warn("Could not resolve curated channel '{}' on YouTube; skipping", channel.id());
				failed++;
				continue;
			}
			String sourceId = ContentSource.idForYoutubeChannel(facts.get().channelId());
			if (repository.findSource(sourceId).isPresent()) {
				skipped++;
				continue;
			}
			seedOne(channel, facts.get(), sourceId, knownTags, clock.instant());
			created++;
		}
		for (CuratedPodcastCatalog.CuratedPodcast podcast : curatedPodcasts.getPodcasts()) {
			Optional<PodcastFacts> facts = podcastDirectory.findById(podcast.appleId());
			if (facts.isEmpty() || facts.get().explicit()) {
				log.warn("Could not use curated podcast '{}' ({}): not found or marked explicit; skipping", podcast.name(),
						podcast.appleId());
				failed++;
				continue;
			}
			if (repository.findSource(facts.get().sourceId()).isPresent()) {
				skipped++;
				continue;
			}
			seedPodcast(podcast, facts.get(), knownTags, clock.instant());
			created++;
		}
		log.info("Catalog seed finished: {} sources created, {} already present, {} failed", created, skipped, failed);
		return new int[] { created, skipped, failed };
	}

	private void seedPodcast(CuratedPodcastCatalog.CuratedPodcast podcast, PodcastFacts facts, Set<String> knownTags,
			Instant now) {
		List<String> tags = podcast.tags().stream().filter(knownTags::contains).toList();
		Integer min = podcast.minAge() == null ? null : AgeMath.minMonthsForYears(podcast.minAge());
		Integer max = podcast.maxAge() == null ? null : AgeMath.maxMonthsForYears(podcast.maxAge());
		String sourceId = facts.sourceId();

		ContentAssessment assessment = new ContentAssessment(null, ContentAssessment.CONTENT_SOURCE, sourceId,
				Statuses.Assessor.CURATED_SEED, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, null, min, max, tags,
				List.of(), "UNKNOWN", List.of(), "Part of Aram's curated starter list.", ContentAssessment.DEVELOPER_REVIEW,
				List.of("podcasts.json"),
				List.of("Chosen by the developers from public show information; individual episodes are not reviewed."),
				null, null, ContentAssessment.POLICY_VERSION, null, now, "seed:podcasts.json", now);
		String assessmentId = repository.addAssessment(assessment);

		ContentSource source = new ContentSource(sourceId, podcast.name(), ContentSource.normalize(podcast.name()),
				ContentSource.PODCAST_FEED, ContentSource.APPLE_PODCASTS, facts.canonicalUrl(),
				Long.toString(facts.collectionId()), podcast.description(), tags, List.of(ContentSource.PREPOPULATED),
				Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, assessmentId, min, max,
				facts.author(), facts.artworkUrl(), null, now, now, now, facts.feedUrl());
		repository.createSource(source);
		log.info("Seeded podcast {} ({})", sourceId, podcast.name());
	}

	private Optional<ChannelFacts> resolve(Channel channel) {
		if (channel.youtubeChannelId() != null && !channel.youtubeChannelId().isBlank()) {
			return lookup.findById(channel.youtubeChannelId());
		}
		return lookup.findByHandle(channel.youtubeHandle());
	}

	private void seedOne(Channel channel, ChannelFacts facts, String sourceId, Set<String> knownTags, Instant now) {
		List<String> tags = channel.tags().stream().filter(knownTags::contains).toList();
		Integer min = channel.minAge() == null ? null : AgeMath.minMonthsForYears(channel.minAge());
		Integer max = channel.maxAge() == null ? null : AgeMath.maxMonthsForYears(channel.maxAge());

		ContentAssessment assessment = new ContentAssessment(null, ContentAssessment.CONTENT_SOURCE, sourceId,
				Statuses.Assessor.CURATED_SEED, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, null, min, max, tags,
				List.of(), "UNKNOWN", List.of(), "Part of Aram's curated starter list.", ContentAssessment.DEVELOPER_REVIEW,
				List.of("channels.json"),
				List.of("Chosen by the developers from public channel information; individual videos are not reviewed."),
				null, null, ContentAssessment.POLICY_VERSION, null, now, "seed:channels.json", now);
		String assessmentId = repository.addAssessment(assessment);

		ContentSource source = new ContentSource(sourceId, channel.name(), ContentSource.normalize(channel.name()),
				ContentSource.YOUTUBE_CHANNEL, ContentSource.YOUTUBE, CatalogService.canonicalUrl(facts), facts.channelId(),
				channel.description(), tags, List.of(ContentSource.PREPOPULATED), Statuses.Catalog.ACTIVE,
				Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, assessmentId, min, max, facts.handle(), facts.thumbnailUrl(),
				facts.subscriberCount(), now, now, now);
		repository.createSource(source);
		log.info("Seeded source {} ({})", sourceId, channel.name());
	}
}
