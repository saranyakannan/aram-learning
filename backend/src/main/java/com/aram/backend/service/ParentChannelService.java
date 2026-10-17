package com.aram.backend.service;

import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ChildProfile;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.exception.ChannelNotFoundException;
import com.aram.backend.model.Channel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a signed-in parent may see. Approved channels are the only ones that can be played; the live catalog's
 * recommendable channels are visible so the parent can pick from them, but never playable until approved.
 */
@Service
public class ParentChannelService {

	private final CatalogService catalog;
	private final ChildCatalogService childCatalog;

	public ParentChannelService(CatalogService catalog, ChildCatalogService childCatalog) {
		this.catalog = catalog;
		this.childCatalog = childCatalog;
	}

	/** Approved channels first (in approval order), then the other channels the parent can choose from. */
	public List<Channel> visibleChannels(String parentId) {
		Map<String, Channel> visible = new LinkedHashMap<>();
		for (Channel channel : approvedChannels(parentId)) {
			visible.put(channel.id(), channel);
		}
		for (ContentSource source : catalog.recommendable()) {
			visible.putIfAbsent(source.id(), catalog.toChannel(source));
		}
		return new ArrayList<>(visible.values());
	}

	public Channel getVisibleChannel(String parentId, String channelId) {
		return visibleChannels(parentId).stream().filter(channel -> channel.id().equals(channelId)).findFirst()
				.orElseThrow(() -> new ChannelNotFoundException(channelId));
	}

	public List<Channel> approvedChannels(String parentId) {
		ParentIds.requireValid(parentId);
		return childCatalog.approvedSources(ChildProfile.firstChildId(parentId)).stream().map(catalog::toChannel).toList();
	}

	/** Approved podcast shows (with their feed addresses), for the episode endpoints. */
	public List<ContentSource> approvedPodcasts(String parentId) {
		ParentIds.requireValid(parentId);
		return childCatalog.approvedSources(ChildProfile.firstChildId(parentId)).stream().filter(ContentSource::isPodcast).toList();
	}

	/** Playback authorization: only a channel the parent approved for the child (and that is still clear) qualifies. */
	public Channel getApprovedChannel(String parentId, String channelId) {
		return approvedChannels(parentId).stream().filter(channel -> channel.id().equals(channelId)).findFirst()
				.orElseThrow(() -> new ChannelNotFoundException(channelId));
	}
}
