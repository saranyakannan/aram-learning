package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.exception.ChannelNotFoundException;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.model.Channel;
import com.aram.backend.model.Video;
import com.aram.backend.service.ParentChannelService;
import com.aram.backend.service.YoutubeVideoService;
import com.aram.backend.util.Parallel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class VideoController {

	private static final Logger log = LoggerFactory.getLogger(VideoController.class);

	private final ParentChannelService parentChannelService;
	private final YoutubeVideoService youtubeVideoService;

	public VideoController(ParentChannelService parentChannelService, YoutubeVideoService youtubeVideoService) {
		this.parentChannelService = parentChannelService;
		this.youtubeVideoService = youtubeVideoService;
	}

	/** The channel's videos (latest uploads plus its most viewed recent ones), but only if the parent approved it for their child. */
	@GetMapping("/api/channels/{channelId}/videos")
	public List<Video> getVideosForChannel(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@PathVariable String channelId) {
		Channel channel = parentChannelService.getApprovedChannel(parent.uid(), channelId);
		if (!Channel.YOUTUBE_CHANNEL.equals(channel.sourceType())) {
			throw new ChannelNotFoundException(channelId);
		}
		return youtubeVideoService.getWatchableVideos(channel);
	}

	/**
	 * One video for the watch page. Any video of an approved channel can be opened, not only the cached latest or top ones, but
	 * only when it really belongs to that channel; anything else is "not found".
	 */
	@GetMapping("/api/channels/{channelId}/videos/{videoId}")
	public Video getVideo(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent, @PathVariable String channelId,
			@PathVariable String videoId) {
		Channel channel = parentChannelService.getApprovedChannel(parent.uid(), channelId);
		if (!Channel.YOUTUBE_CHANNEL.equals(channel.sourceType())) {
			throw new ChannelNotFoundException(channelId);
		}
		return youtubeVideoService.getVideo(channel, videoId).orElseThrow(() -> new ChannelNotFoundException(videoId));
	}

	/**
	 * Home-page feed: latest videos for every channel approved for the child, keyed by channel id.
	 * One channel failing to resolve on YouTube (bad handle, transient API error, ...)
	 * must not blank out every other channel's videos, so failures are caught per
	 * channel and that channel is simply returned empty rather than failing the request.
	 * When {@code channelIds} is given, only those of them that are approved are fetched; a channel
	 * that is not approved is never returned, whatever is asked for.
	 */
	@GetMapping("/api/feed")
	public Map<String, List<Video>> getFeed(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestParam(required = false) List<String> channelIds) {
		List<Channel> targets = parentChannelService.approvedChannels(parent.uid()).stream()
				.filter(channel -> Channel.YOUTUBE_CHANNEL.equals(channel.sourceType()))
				.filter(channel -> channelIds == null || channelIds.contains(channel.id()))
				.toList();
		// Each channel is one cache read (or one YouTube call on a miss): do them side by side, not one after another.
		List<Map.Entry<String, List<Video>>> loaded = Parallel.map(targets, channel -> {
			try {
				return Map.entry(channel.id(), youtubeVideoService.getLatestVideos(channel));
			} catch (YoutubeIntegrationException e) {
				log.warn("Skipping channel '{}' in feed: {}", channel.id(), e.getMessage());
				return Map.entry(channel.id(), List.<Video>of());
			}
		});
		Map<String, List<Video>> feed = new LinkedHashMap<>();
		loaded.forEach(entry -> feed.put(entry.getKey(), entry.getValue()));
		return feed;
	}
}
