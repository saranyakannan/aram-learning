package com.aram.backend.service;

import com.aram.backend.exception.PodcastLookupException;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.model.Channel;
import com.aram.backend.model.TopPick;
import com.aram.backend.model.Video;
import com.aram.backend.podcast.PodcastEpisode;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.util.Parallel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * The first strip of Kid Space: about ten items mixing popular videos and fresh podcast episodes, drawn only from sources the
 * parent approved. Videos are the most viewed recent uploads of each approved channel (at most {@value #PER_SOURCE_CAP} per
 * channel); podcasts have no view counts, so a show contributes its newest episode. The picks are sampled from a wider pool on
 * every request and videos and episodes alternate, so the strip changes from visit to visit and is never one channel in a row.
 * Selection only reorders what is already approved; it cannot make anything playable.
 */
@Service
public class TopPicksService {

	private static final Logger log = LoggerFactory.getLogger(TopPicksService.class);

	public static final int SIZE = 10;
	public static final int MAX_SIZE = 40;
	static final int PER_SOURCE_CAP = 2;

	private final ParentChannelService parentChannelService;
	private final YoutubeVideoService youtubeVideoService;
	private final PodcastFeedService podcastFeedService;

	public TopPicksService(ParentChannelService parentChannelService, YoutubeVideoService youtubeVideoService,
			PodcastFeedService podcastFeedService) {
		this.parentChannelService = parentChannelService;
		this.youtubeVideoService = youtubeVideoService;
		this.podcastFeedService = podcastFeedService;
	}

	public List<TopPick> topPicks(String parentId) {
		return topPicks(parentId, SIZE);
	}

	/** {@code size} is clamped to 1..{@value #MAX_SIZE}; the strip shows the first rows and offers the rest under "Show more". */
	public List<TopPick> topPicks(String parentId, int size) {
		return topPicks(parentId, clamp(size), new Random());
	}

	static int clamp(int size) {
		return Math.max(1, Math.min(MAX_SIZE, size));
	}

	List<TopPick> topPicks(String parentId, int size, Random random) {
		List<Channel> channels = parentChannelService.approvedChannels(parentId).stream()
				.filter(channel -> Channel.YOUTUBE_CHANNEL.equals(channel.sourceType())).toList();
		List<List<TopPick>> perChannel = Parallel.map(channels, channel -> {
			List<TopPick> picks = new ArrayList<>();
			try {
				for (Video video : youtubeVideoService.getTopVideos(channel)) {
					picks.add(new TopPick(TopPick.VIDEO, video.getVideoId(), channel.id(), channel.name(), video.getTitle(),
							video.getThumbnailUrl(), video.getPublishedAt(), video.getViewCount(), null, video.getDurationSeconds()));
				}
			} catch (YoutubeIntegrationException e) {
				log.warn("Skipping channel '{}' in top picks: {}", channel.id(), e.getMessage());
			}
			return picks;
		});
		List<TopPick> videos = perChannel.stream().flatMap(List::stream).collect(java.util.stream.Collectors.toCollection(ArrayList::new));

		List<ContentSource> shows = parentChannelService.approvedPodcasts(parentId);
		List<TopPick> episodes = Parallel.map(shows, show -> {
			try {
				List<PodcastEpisode> latest = podcastFeedService.episodesFor(show);
				if (latest.isEmpty()) {
					return (TopPick) null;
				}
				PodcastEpisode episode = latest.get(0);
				return new TopPick(TopPick.EPISODE, episode.id(), show.id(), show.name(), episode.title(),
						episode.imageUrl() != null ? episode.imageUrl() : show.thumbnailUrl(), episode.publishedAt(), null,
						episode.audioUrl(), episode.durationSeconds());
			} catch (PodcastLookupException e) {
				log.warn("Skipping podcast '{}' in top picks: {}", show.id(), e.getMessage());
				return (TopPick) null;
			}
		}).stream().filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
		return select(videos, episodes, size, random);
	}

	/** Pure selection, kept separate so it can be tested with a seeded {@link Random}. */
	static List<TopPick> select(List<TopPick> videos, List<TopPick> episodes, int size, Random random) {
		List<TopPick> videoPool = cappedPopular(videos);
		List<TopPick> episodePool = new ArrayList<>(episodes.stream().filter(Objects::nonNull).toList());

		int episodeSlots = Math.min(episodePool.size(), Math.max(1, Math.round(size * 0.3f)));
		int videoSlots = Math.min(videoPool.size(), size - episodeSlots);
		episodeSlots = Math.min(episodePool.size(), size - videoSlots);

		List<TopPick> chosenVideos = sample(videoPool, videoSlots, random);
		Collections.shuffle(episodePool, random);
		List<TopPick> chosenEpisodes = new ArrayList<>(episodePool.subList(0, episodeSlots));

		// Every third tile is an episode (while there are some), the rest are videos.
		List<TopPick> strip = new ArrayList<>();
		int v = 0;
		int e = 0;
		while (v < chosenVideos.size() || e < chosenEpisodes.size()) {
			boolean episodeTurn = (strip.size() + 1) % 3 == 0;
			if ((episodeTurn && e < chosenEpisodes.size()) || v >= chosenVideos.size()) {
				strip.add(chosenEpisodes.get(e++));
			} else {
				strip.add(chosenVideos.get(v++));
			}
		}
		return separateNeighbours(strip);
	}

	/** At most {@value #PER_SOURCE_CAP} videos per channel (its most viewed), then the whole pool most viewed first. */
	private static List<TopPick> cappedPopular(List<TopPick> videos) {
		Map<String, List<TopPick>> bySource = new HashMap<>();
		for (TopPick pick : videos) {
			bySource.computeIfAbsent(pick.sourceId(), key -> new ArrayList<>()).add(pick);
		}
		List<TopPick> capped = new ArrayList<>();
		for (List<TopPick> own : bySource.values()) {
			own.sort(views());
			capped.addAll(own.subList(0, Math.min(PER_SOURCE_CAP, own.size())));
		}
		capped.sort(views().thenComparing(TopPick::id, Comparator.nullsLast(Comparator.naturalOrder())));
		return capped;
	}

	private static Comparator<TopPick> views() {
		return Comparator.comparingLong((TopPick pick) -> pick.viewCount() == null ? 0 : pick.viewCount()).reversed();
	}

	/** Takes {@code count} from the most viewed {@code 2 * count} (so popular ones dominate but the top is not fixed). */
	private static List<TopPick> sample(List<TopPick> popularFirst, int count, Random random) {
		List<TopPick> pool = new ArrayList<>(popularFirst.subList(0, Math.min(popularFirst.size(), count * 2)));
		Collections.shuffle(pool, random);
		return new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
	}

	/** Swaps tiles so two neighbours rarely come from the same channel or show; a swap is kept only if it removes a clash. */
	static List<TopPick> separateNeighbours(List<TopPick> strip) {
		List<TopPick> result = new ArrayList<>(strip);
		for (int pass = 0; pass < result.size() && clashes(result) > 0; pass++) {
			for (int i = 1; i < result.size(); i++) {
				if (!result.get(i).sourceId().equals(result.get(i - 1).sourceId())) {
					continue;
				}
				int before = clashes(result);
				for (int j = 0; j < result.size(); j++) {
					if (j == i) {
						continue;
					}
					Collections.swap(result, i, j);
					if (clashes(result) < before) {
						break;
					}
					Collections.swap(result, i, j);
				}
			}
		}
		return result;
	}

	private static int clashes(List<TopPick> strip) {
		int count = 0;
		for (int i = 1; i < strip.size(); i++) {
			if (strip.get(i).sourceId().equals(strip.get(i - 1).sourceId())) {
				count++;
			}
		}
		return count;
	}
}
