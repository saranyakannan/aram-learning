package com.aram.backend.podcast;

import com.aram.backend.exception.PodcastLookupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Asks a show's audio host whether the newest episode can actually be fetched. A feed can keep listing episodes whose
 * files the publisher has removed or locked (the host answers 403/404), and a child would then press play on something
 * that never starts. Only a clear "no" counts: a timeout, a 5xx or an unreachable host is "unknown" and treated as
 * playable, so a short outage never hides a good show. Results are remembered in memory for a while (a refusal for a
 * shorter time, so a fixed show comes back soon). The request goes through the same guarded fetcher as feeds.
 */
@Component
public class PodcastAudioCheck {

	public enum Verdict { PLAYABLE, UNPLAYABLE, UNKNOWN }

	private static final Logger log = LoggerFactory.getLogger(PodcastAudioCheck.class);
	private static final Duration PLAYABLE_FOR = Duration.ofMinutes(60);
	private static final Duration UNPLAYABLE_FOR = Duration.ofMinutes(15);
	private static final int PROBE_BYTES = 1024;
	private static final int MAX_REMEMBERED = 500;

	private record Remembered(Verdict verdict, Instant at) {
	}

	private final HttpFetcher http;
	private final Clock clock;
	private final ConcurrentHashMap<String, Remembered> remembered = new ConcurrentHashMap<>();

	public PodcastAudioCheck(HttpFetcher http, Clock clock) {
		this.http = http;
		this.clock = clock;
	}

	/** The verdict for a show, judged by its newest episode that has an audio link (no episodes: unknown). */
	public Verdict forEpisodes(List<PodcastEpisode> episodes) {
		return episodes.stream().filter(episode -> episode.audioUrl() != null && !episode.audioUrl().isBlank()).findFirst()
				.map(episode -> check(episode.audioUrl())).orElse(Verdict.UNKNOWN);
	}

	public boolean isDefinitelyUnplayable(List<PodcastEpisode> episodes) {
		return forEpisodes(episodes) == Verdict.UNPLAYABLE;
	}

	public Verdict check(String audioUrl) {
		Remembered known = remembered.get(audioUrl);
		if (known != null) {
			Duration keep = known.verdict() == Verdict.UNPLAYABLE ? UNPLAYABLE_FOR : PLAYABLE_FOR;
			if (known.at().plus(keep).isAfter(clock.instant())) {
				return known.verdict();
			}
		}
		Verdict verdict = probe(audioUrl);
		if (verdict != Verdict.UNKNOWN) {
			if (remembered.size() >= MAX_REMEMBERED) {
				remembered.clear();
			}
			remembered.put(audioUrl, new Remembered(verdict, clock.instant()));
		}
		return verdict;
	}

	private Verdict probe(String audioUrl) {
		try {
			HttpFetcher.Response response = http.get(URI.create(audioUrl), PROBE_BYTES);
			int status = response.status();
			if (status >= 200 && status < 300) {
				String type = response.contentType() == null ? "" : response.contentType().toLowerCase(Locale.ROOT);
				// An error page served with a 200 is as good as a refusal.
				boolean notAudio = type.contains("html") || type.contains("xml") || type.contains("json");
				return notAudio ? Verdict.UNPLAYABLE : Verdict.PLAYABLE;
			}
			boolean refused = status >= 400 && status < 500 && status != 408 && status != 429;
			if (refused) {
				log.warn("Podcast audio answered {} for {}", status, URI.create(audioUrl).getHost());
			}
			return refused ? Verdict.UNPLAYABLE : Verdict.UNKNOWN;
		} catch (PodcastLookupException | IllegalArgumentException e) {
			return Verdict.UNKNOWN;
		}
	}
}
