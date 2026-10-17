package com.aram.backend.podcast;

import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.EpisodeCacheRepository;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Reads show feeds: uncached for vetting a new show, cached (and served stale if the feed is down) for playback. */
@Service
public class PodcastFeedService {

	private static final Logger log = LoggerFactory.getLogger(PodcastFeedService.class);

	private final HttpFetcher http;
	private final EpisodeCacheRepository cache;
	private final AramProperties properties;
	private final Clock clock;
	private final PodcastAudioCheck audioCheck;

	public PodcastFeedService(HttpFetcher http, EpisodeCacheRepository cache, AramProperties properties, Clock clock) {
		this(http, cache, properties, clock, null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	public PodcastFeedService(HttpFetcher http, EpisodeCacheRepository cache, AramProperties properties, Clock clock,
			PodcastAudioCheck audioCheck) {
		this.http = http;
		this.cache = cache;
		this.properties = properties;
		this.clock = clock;
		this.audioCheck = audioCheck;
	}

	/** True when the show's audio host clearly refuses the newest episode (see {@link PodcastAudioCheck}). */
	public boolean isAudioUnavailable(List<PodcastEpisode> episodes) {
		return audioCheck != null && audioCheck.isDefinitelyUnplayable(episodes);
	}

	/** The feed as it is right now, straight from the publisher. */
	public PodcastFeedParser.Feed fetchFeed(String feedUrl) {
		HttpFetcher.Response response = http.get(URI.create(feedUrl), properties.getPodcast().getFeedMaxBytes());
		if (response.status() != 200) {
			throw new PodcastLookupException("The feed answered " + response.status());
		}
		return PodcastFeedParser.parse(response.body(), properties.getPodcast().getMaxEpisodes());
	}

	/** Latest episodes for an approved show; empty when the show's audio is clearly not available, so no dead tile is shown. */
	public List<PodcastEpisode> episodesFor(ContentSource source) {
		List<PodcastEpisode> episodes = loadEpisodes(source);
		if (isAudioUnavailable(episodes)) {
			log.warn("Hiding {}: its audio host refuses the newest episode", source.id());
			return List.of();
		}
		return episodes;
	}

	/** Same list without the playability filter (the agent asks the check itself to say why a show is refused). */
	public List<PodcastEpisode> episodesForUnfiltered(ContentSource source) {
		return loadEpisodes(source);
	}

	private List<PodcastEpisode> loadEpisodes(ContentSource source) {
		if (!source.isPodcast() || source.feedUrl() == null) {
			return List.of();
		}
		Optional<EpisodeCacheRepository.Cached> cached = cache.find(source.id());
		Duration ttl = Duration.ofMinutes(properties.getPodcast().getCacheTtlMinutes());
		if (cached.isPresent() && cached.get().fetchedAt() != null
				&& cached.get().fetchedAt().plus(ttl).isAfter(clock.instant())) {
			return cached.get().episodes();
		}
		try {
			List<PodcastEpisode> fresh = fetchFeed(source.feedUrl()).episodes();
			cache.save(source.id(), fresh, clock.instant());
			return fresh;
		} catch (PodcastLookupException e) {
			if (cached.isPresent()) {
				log.warn("Feed for {} unavailable ({}); serving the last copy", source.id(), e.getMessage());
				return cached.get().episodes();
			}
			throw e;
		}
	}
}
