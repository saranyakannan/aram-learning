package com.aram.backend.catalog.builder;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.model.ChannelFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Keeps the YouTube data we store inside YouTube's 30-day limit (decision record 0011). It re-reads every YouTube channel in
 * the catalog in batches of 50 (one quota unit per batch), refreshes the platform-derived fields and the verification date, and
 * takes channels YouTube no longer returns out of the live catalog and strips their platform data. A batch that fails after
 * retries changes nothing: a failed lookup is never treated as "the channel is gone".
 */
@Service
@ConditionalOnProperty(prefix = "aram", name = "refresh-enabled", havingValue = "true")
public class YoutubeRefreshService {

	/** {@code quotaUnits} is one per batch of up to 50 channels. */
	public record Result(int checked, int refreshed, int vanished, int failed, int quotaUnits, List<String> vanishedIds) {
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	static final int MAX_ATTEMPTS = 3;
	private static final Logger log = LoggerFactory.getLogger(YoutubeRefreshService.class);

	private final CatalogRepository repository;
	private final YoutubeChannelLookup lookup;
	private final Clock clock;
	private final Sleeper sleeper;

	@Autowired
	public YoutubeRefreshService(CatalogRepository repository, YoutubeChannelLookup lookup, Clock clock) {
		this(repository, lookup, clock, Thread::sleep);
	}

	YoutubeRefreshService(CatalogRepository repository, YoutubeChannelLookup lookup, Clock clock, Sleeper sleeper) {
		this.repository = repository;
		this.lookup = lookup;
		this.clock = clock;
		this.sleeper = sleeper;
	}

	public Result run(boolean dryRun) {
		List<ContentSource> sources = repository.findAllSources().stream()
				.filter(source -> ContentSource.YOUTUBE.equals(source.platform()) && source.platformSourceId() != null).toList();
		Instant now = clock.instant();
		int refreshed = 0;
		int vanished = 0;
		int failed = 0;
		int quota = 0;
		List<String> vanishedIds = new ArrayList<>();

		for (int from = 0; from < sources.size(); from += YoutubeChannelLookup.MAX_IDS_PER_CALL) {
			List<ContentSource> batch = sources.subList(from, Math.min(from + YoutubeChannelLookup.MAX_IDS_PER_CALL, sources.size()));
			quota++;
			Map<String, ChannelFacts> found;
			try {
				found = fetch(batch.stream().map(ContentSource::platformSourceId).toList());
			} catch (YoutubeIntegrationException e) {
				log.warn("YouTube refresh of {} channels failed after {} attempts; leaving them unchanged", batch.size(), MAX_ATTEMPTS);
				failed += batch.size();
				continue;
			}
			for (ContentSource source : batch) {
				ChannelFacts facts = found.get(source.platformSourceId());
				if (facts != null) {
					refreshed++;
					if (!dryRun) {
						repository.saveSource(source.withYoutubeData(facts, now));
					}
				} else {
					vanished++;
					vanishedIds.add(source.id());
					if (!dryRun) {
						repository.saveSource(source.withoutYoutubeData(now));
					}
				}
			}
		}
		log.info("YouTube refresh{}: {} channels checked, {} refreshed, {} no longer on YouTube, {} failed, {} quota units",
				dryRun ? " (dry run)" : "", sources.size(), refreshed, vanished, failed, quota);
		return new Result(sources.size(), refreshed, vanished, failed, quota, List.copyOf(vanishedIds));
	}

	private Map<String, ChannelFacts> fetch(List<String> ids) {
		for (int attempt = 1; ; attempt++) {
			try {
				return lookup.findByIds(ids);
			} catch (YoutubeIntegrationException e) {
				if (attempt >= MAX_ATTEMPTS) {
					throw e;
				}
				try {
					sleeper.sleep(1000L << attempt);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}
}
