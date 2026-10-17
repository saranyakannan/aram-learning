package com.aram.backend.service;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.model.CachedChannelVideos;
import com.aram.backend.model.Channel;
import com.aram.backend.model.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fetches the latest uploaded videos for a curated channel, backed by a Firestore
 * cache so a page load doesn't burn a YouTube Data API quota unit on every request.
 */
@Service
public class YoutubeVideoService {

	private static final Logger log = LoggerFactory.getLogger(YoutubeVideoService.class);

	private final YouTube youTube;
	private final AramProperties properties;
	private final VideoCacheRepository videoCacheRepository;

	/** channelId -> resolved "uploads" playlist id, kept for the life of the process. */
	private final ConcurrentHashMap<String, String> uploadsPlaylistIdByChannel = new ConcurrentHashMap<>();

	private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
	static final long LOOKUP_TTL_NANOS = 10L * 60 * 1_000_000_000L;
	private static final int LOOKUP_MAX_ENTRIES = 500;

	private record Looked(Video video, long atNanos) {
	}

	/** Single-video lookups, kept in memory only for ten minutes (never stored), so a refresh or a re-open costs no quota. */
	private final ConcurrentHashMap<String, Looked> lookedUp = new ConcurrentHashMap<>();

	public YoutubeVideoService(YouTube youTube, AramProperties properties, VideoCacheRepository videoCacheRepository) {
		this.youTube = youTube;
		this.properties = properties;
		this.videoCacheRepository = videoCacheRepository;
	}

	/** How many of a channel's most viewed recent uploads are kept for the "top picks" strip. */
	static final int TOP_KEPT = 5;
	static final int TOP_DESCRIPTION_CHARS = 500;

	public List<Video> getLatestVideos(Channel channel) {
		return cachedOrRefresh(channel).getVideos();
	}

	/**
	 * One video of an approved channel, for the watch page. Any video of the channel qualifies (not only the cached latest and
	 * top ones), but only if YouTube says it belongs to this channel: the id arrives in the page address, so an id from any
	 * other channel is refused, exactly like an unknown one. Already-cached videos cost no YouTube call; anything else costs one
	 * quota unit and is then remembered in memory for ten minutes.
	 */
	public Optional<Video> getVideo(Channel channel, String videoId) {
		if (videoId == null || !VIDEO_ID.matcher(videoId).matches() || !StringUtils.hasText(channel.youtubeChannelId())) {
			return Optional.empty();
		}
		Optional<CachedChannelVideos> cached = videoCacheRepository.findFresh(channel.id());
		if (cached.isPresent()) {
			Optional<Video> hit = mergeWatchable(cached.get().getVideos(), cached.get().getTopVideos()).stream()
					.filter(video -> videoId.equals(video.getVideoId())).findFirst();
			if (hit.isPresent()) {
				return hit;
			}
		}
		String key = channel.id() + "|" + videoId;
		Looked remembered = lookedUp.get(key);
		if (remembered != null && System.nanoTime() - remembered.atNanos() < LOOKUP_TTL_NANOS) {
			return Optional.of(remembered.video());
		}
		Optional<Video> fetched = fetchOwnVideo(channel, videoId);
		fetched.ifPresent(video -> {
			if (lookedUp.size() >= LOOKUP_MAX_ENTRIES) {
				lookedUp.clear();
			}
			lookedUp.put(key, new Looked(video, System.nanoTime()));
		});
		return fetched;
	}

	private Optional<Video> fetchOwnVideo(Channel channel, String videoId) {
		try {
			var response = youTube.videos().list(List.of("snippet", "contentDetails", "statistics")).setId(List.of(videoId)).execute();
			if (response.getItems() == null || response.getItems().isEmpty()) {
				return Optional.empty();
			}
			var item = response.getItems().get(0);
			var snippet = item.getSnippet();
			if (snippet == null || !channel.youtubeChannelId().equals(snippet.getChannelId())) {
				return Optional.empty();
			}
			String thumbnail = null;
			if (snippet.getThumbnails() != null) {
				var t = snippet.getThumbnails();
				var best = t.getHigh() != null ? t.getHigh() : t.getMedium() != null ? t.getMedium() : t.getDefault();
				thumbnail = best == null ? null : best.getUrl();
			}
			Integer seconds = null;
			if (item.getContentDetails() != null && item.getContentDetails().getDuration() != null) {
				try {
					seconds = (int) java.time.Duration.parse(item.getContentDetails().getDuration()).toSeconds();
				} catch (java.time.format.DateTimeParseException ignored) {
					// length unknown
				}
			}
			BigInteger views = item.getStatistics() == null ? null : item.getStatistics().getViewCount();
			return Optional.of(Video.builder().videoId(videoId).title(snippet.getTitle()).description(snippet.getDescription())
					.thumbnailUrl(thumbnail).publishedAt(snippet.getPublishedAt() == null ? null : snippet.getPublishedAt().toString())
					.channelId(channel.id()).channelTitle(channel.name()).videoUrl("https://www.youtube.com/watch?v=" + videoId)
					.viewCount(views == null ? null : views.longValue()).durationSeconds(seconds).build());
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to look up video '" + videoId + "'", e);
		}
	}

	/**
	 * Every video of this channel the app can link to: the latest uploads plus the most viewed recent ones (the "top picks" strip
	 * links to those, and they may be older than the latest few). Latest first, no duplicates.
	 */
	public List<Video> getWatchableVideos(Channel channel) {
		CachedChannelVideos cached = cachedOrRefresh(channel);
		return mergeWatchable(cached.getVideos(), cached.getTopVideos());
	}

	static List<Video> mergeWatchable(List<Video> latest, List<Video> top) {
		java.util.LinkedHashMap<String, Video> byId = new java.util.LinkedHashMap<>();
		for (Video video : latest == null ? List.<Video>of() : latest) {
			byId.putIfAbsent(video.getVideoId(), video);
		}
		for (Video video : top == null ? List.<Video>of() : top) {
			byId.putIfAbsent(video.getVideoId(), video);
		}
		return List.copyOf(byId.values());
	}

	/** The channel's most viewed videos among its recent uploads (see {@code youtube.pool-size}), most viewed first. */
	public List<Video> getTopVideos(Channel channel) {
		List<Video> top = cachedOrRefresh(channel).getTopVideos();
		return top == null ? List.of() : top;
	}

	private CachedChannelVideos cachedOrRefresh(Channel channel) {
		Optional<CachedChannelVideos> cached = videoCacheRepository.findFresh(channel.id());
		if (cached.isPresent() && cached.get().getTopVideos() != null) {
			return cached.get();
		}

		List<Video> pool = fetchRecentUploadsWithViews(channel);
		int latestCount = Math.min(properties.getYoutube().getMaxResults(), pool.size());
		CachedChannelVideos fresh = new CachedChannelVideos(channel.id(), List.copyOf(pool.subList(0, latestCount)),
				Instant.now().toEpochMilli(), topByViews(pool, TOP_KEPT));
		videoCacheRepository.save(fresh);
		return fresh;
	}

	/** YouTube Shorts are at most this long; they are not "top picks" for the strip. */
	static final int SHORTS_MAX_SECONDS = 60;

	/** The {@code n} most viewed videos (known count, not a Short), most viewed first, with descriptions cut to {@value #TOP_DESCRIPTION_CHARS} characters. */
	static List<Video> topByViews(List<Video> videos, int n) {
		return videos.stream()
				.filter(video -> video.getViewCount() != null)
				.filter(video -> video.getDurationSeconds() == null || video.getDurationSeconds() > SHORTS_MAX_SECONDS)
				.sorted(Comparator.comparingLong(Video::getViewCount).reversed())
				.limit(n)
				.map(video -> Video.builder().videoId(video.getVideoId()).title(video.getTitle())
						.description(video.getDescription() == null ? null
								: video.getDescription().substring(0, Math.min(TOP_DESCRIPTION_CHARS, video.getDescription().length())))
						.thumbnailUrl(video.getThumbnailUrl()).publishedAt(video.getPublishedAt()).channelId(video.getChannelId())
						.channelTitle(video.getChannelTitle()).videoUrl(video.getVideoUrl()).viewCount(video.getViewCount())
						.durationSeconds(video.getDurationSeconds()).build())
				.toList();
	}

	/**
	 * Recent uploads (newest first) with their public view counts. One playlist call plus one statistics call (1 quota unit each).
	 * If the statistics call fails the videos are still returned, just without counts.
	 */
	private List<Video> fetchRecentUploadsWithViews(Channel channel) {
		List<Video> uploads = fetchUploads(channel, properties.getYoutube().getPoolSize());
		List<String> ids = uploads.stream().map(Video::getVideoId).filter(java.util.Objects::nonNull).toList();
		if (ids.isEmpty()) {
			return uploads;
		}
		try {
			var response = youTube.videos().list(List.of("statistics", "contentDetails")).setId(ids).setMaxResults((long) ids.size()).execute();
			Map<String, Long> views = new HashMap<>();
			Map<String, Integer> lengths = new HashMap<>();
			if (response.getItems() != null) {
				response.getItems().forEach(item -> {
					BigInteger count = item.getStatistics() == null ? null : item.getStatistics().getViewCount();
					if (count != null) {
						views.put(item.getId(), count.longValue());
					}
					if (item.getContentDetails() != null && item.getContentDetails().getDuration() != null) {
						try {
							lengths.put(item.getId(), (int) java.time.Duration.parse(item.getContentDetails().getDuration()).toSeconds());
						} catch (java.time.format.DateTimeParseException ignored) {
							// no length: the video stays eligible
						}
					}
				});
			}
			uploads.forEach(video -> {
				video.setViewCount(views.get(video.getVideoId()));
				video.setDurationSeconds(lengths.get(video.getVideoId()));
			});
		} catch (IOException e) {
			log.warn("Could not read view counts for channel '{}': {}", channel.id(), e.getMessage());
		}
		return uploads;
	}

	/** Latest uploads straight from YouTube, bypassing the Firestore cache (used to vet channels not yet approved). */
	public List<Video> fetchLatestVideos(Channel channel) {
		return fetchUploads(channel, properties.getYoutube().getMaxResults());
	}

	private List<Video> fetchUploads(Channel channel, int count) {
		try {
			String uploadsPlaylistId = resolveUploadsPlaylistId(channel);
			PlaylistItemListResponse response = youTube.playlistItems()
					.list(List.of("snippet", "contentDetails"))
					.setPlaylistId(uploadsPlaylistId)
					.setMaxResults((long) Math.min(50, count))
					.execute();

			List<PlaylistItem> items = response.getItems();
			if (items == null) {
				return List.of();
			}
			return items.stream()
					.map(item -> toVideo(item, channel))
					.collect(Collectors.toList());
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to fetch videos for channel '" + channel.id() + "'", e);
		}
	}

	private String resolveUploadsPlaylistId(Channel channel) throws IOException {
		String cachedPlaylistId = uploadsPlaylistIdByChannel.get(channel.id());
		if (cachedPlaylistId != null) {
			return cachedPlaylistId;
		}

		YouTube.Channels.List request = youTube.channels().list(List.of("contentDetails"));
		if (StringUtils.hasText(channel.youtubeChannelId())) {
			request.setId(List.of(channel.youtubeChannelId()));
		} else if (StringUtils.hasText(channel.youtubeHandle())) {
			request.setForHandle(channel.youtubeHandle());
		} else {
			throw new YoutubeIntegrationException(
					"Channel '" + channel.id() + "' has neither a youtubeChannelId nor a youtubeHandle configured", null);
		}

		var response = request.execute();
		if (response.getItems() == null || response.getItems().isEmpty()) {
			throw new YoutubeIntegrationException(
					"YouTube returned no channel matching '" + channel.id() + "'. Check the channel id/handle in channels.json.", null);
		}

		String uploadsPlaylistId = response.getItems().get(0)
				.getContentDetails()
				.getRelatedPlaylists()
				.getUploads();

		uploadsPlaylistIdByChannel.put(channel.id(), uploadsPlaylistId);
		log.info("Resolved uploads playlist for channel '{}': {}", channel.id(), uploadsPlaylistId);
		return uploadsPlaylistId;
	}

	private Video toVideo(PlaylistItem item, Channel channel) {
		var snippet = item.getSnippet();
		String videoId = item.getContentDetails() != null ? item.getContentDetails().getVideoId() : null;
		String thumbnailUrl = null;
		if (snippet.getThumbnails() != null) {
			var thumbnails = snippet.getThumbnails();
			var best = thumbnails.getHigh() != null ? thumbnails.getHigh()
					: thumbnails.getMedium() != null ? thumbnails.getMedium()
					: thumbnails.getDefault();
			if (best != null) {
				thumbnailUrl = best.getUrl();
			}
		}

		return Video.builder()
				.videoId(videoId)
				.title(snippet.getTitle())
				.description(snippet.getDescription())
				.thumbnailUrl(thumbnailUrl)
				.publishedAt(snippet.getPublishedAt() != null ? snippet.getPublishedAt().toString() : null)
				.channelId(channel.id())
				.channelTitle(channel.name())
				.videoUrl(videoId != null ? "https://www.youtube.com/watch?v=" + videoId : null)
				.build();
	}
}
