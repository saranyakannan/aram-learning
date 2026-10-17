package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.exception.ChannelNotFoundException;
import com.aram.backend.exception.PodcastLookupException;
import com.aram.backend.podcast.PodcastEpisode;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.service.ParentChannelService;
import com.aram.backend.util.Parallel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Episodes of podcasts the parent approved for the child. Anything not approved is simply not found. */
@RestController
public class PodcastController {

	private static final Logger log = LoggerFactory.getLogger(PodcastController.class);

	private final ParentChannelService parentChannelService;
	private final PodcastFeedService feedService;

	public PodcastController(ParentChannelService parentChannelService, PodcastFeedService feedService) {
		this.parentChannelService = parentChannelService;
		this.feedService = feedService;
	}

	/** Latest episodes for every approved show, keyed by show id. One broken feed leaves only that show empty. */
	@GetMapping("/api/podcast-feed")
	public Map<String, List<PodcastEpisode>> getFeed(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		List<ContentSource> shows = parentChannelService.approvedPodcasts(parent.uid());
		List<Map.Entry<String, List<PodcastEpisode>>> loaded = Parallel.map(shows, show -> {
			try {
				return Map.entry(show.id(), feedService.episodesFor(show));
			} catch (PodcastLookupException e) {
				log.warn("Skipping podcast '{}' in feed: {}", show.id(), e.getMessage());
				return Map.entry(show.id(), List.<PodcastEpisode>of());
			}
		});
		Map<String, List<PodcastEpisode>> feed = new LinkedHashMap<>();
		loaded.forEach(entry -> feed.put(entry.getKey(), entry.getValue()));
		return feed;
	}

	@GetMapping("/api/podcasts/{sourceId}/episodes")
	public List<PodcastEpisode> getEpisodes(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@PathVariable String sourceId) {
		ContentSource show = parentChannelService.approvedPodcasts(parent.uid()).stream()
				.filter(candidate -> candidate.id().equals(sourceId)).findFirst()
				.orElseThrow(() -> new ChannelNotFoundException(sourceId));
		return feedService.episodesFor(show);
	}
}
