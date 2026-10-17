package com.aram.backend.catalog.builder;

import com.aram.backend.agent.YoutubeChannelLookup;
import com.aram.backend.exception.PodcastLookupException;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.podcast.PodcastDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Runs the reviewed searches. YouTube searches are capped because each one costs about 101 of the 10,000 daily quota units. */
@Service
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CandidateDiscovery {

	/** A YouTube search costs 100 units, plus 1 for the details call. */
	public static final int QUOTA_UNITS_PER_SEARCH = 101;

	public record Discovery(List<Candidate> candidates, int youtubeSearches, int quotaUnits, boolean quotaStopped, List<String> notes) {
	}

	private static final Logger log = LoggerFactory.getLogger(CandidateDiscovery.class);

	private final YoutubeChannelLookup youtube;
	private final PodcastDirectory podcasts;
	private final CatalogQueries queries;

	public CandidateDiscovery(YoutubeChannelLookup youtube, PodcastDirectory podcasts, CatalogQueries queries) {
		this.youtube = youtube;
		this.podcasts = podcasts;
		this.queries = queries;
	}

	public Discovery discover(boolean includeYoutube, boolean includePodcasts, int maxYoutubeSearches) {
		List<Candidate> found = new ArrayList<>();
		List<String> notes = new ArrayList<>();
		int searches = 0;
		boolean stopped = false;

		if (includeYoutube) {
			for (CatalogQueries.Query query : queries.plan().youtube()) {
				if (searches >= maxYoutubeSearches) {
					notes.add("YouTube search cap reached after " + searches + " searches; remaining queries were not run");
					break;
				}
				searches++;
				try {
					youtube.searchChannels(query.query()).forEach(channel -> found.add(Candidate.youtube(channel, query.query(), query.tag())));
				} catch (YoutubeIntegrationException e) {
					log.warn("YouTube search failed ({}); stopping YouTube discovery", e.getMessage());
					notes.add("YouTube search failed (likely quota); stopped after " + searches + " searches");
					stopped = true;
					break;
				}
			}
		}
		if (includePodcasts) {
			for (CatalogQueries.Query query : queries.plan().podcasts()) {
				try {
					podcasts.search(query.query()).forEach(show -> found.add(Candidate.podcast(show, query.query(), query.tag())));
				} catch (PodcastLookupException e) {
					notes.add("Podcast search failed for '" + query.query() + "'");
				}
			}
		}
		return new Discovery(List.copyOf(found), searches, searches * QUOTA_UNITS_PER_SEARCH, stopped, List.copyOf(notes));
	}
}
