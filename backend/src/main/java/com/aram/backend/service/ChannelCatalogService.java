package com.aram.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.ChannelNotFoundException;
import com.aram.backend.model.Channel;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

/** Loads the starter list of curated YouTube channels from {@code channels.json}. Only the seed job reads it; at runtime channels come from the Firestore catalog. */
@Service
public class ChannelCatalogService {

	private final ResourceLoader resourceLoader;
	private final AramProperties properties;
	private final ObjectMapper objectMapper;

	private List<Channel> channels = List.of();

	public ChannelCatalogService(ResourceLoader resourceLoader, AramProperties properties, ObjectMapper objectMapper) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@PostConstruct
	void loadChannels() {
		Resource resource = resourceLoader.getResource(properties.getChannelsFile());
		try (var input = resource.getInputStream()) {
			this.channels = List.of(objectMapper.readValue(input, Channel[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load curated channels from " + properties.getChannelsFile(), e);
		}
	}

	public List<Channel> getChannels() {
		return channels;
	}

	public Channel getChannel(String channelId) {
		return channels.stream()
				.filter(channel -> channel.id().equals(channelId))
				.findFirst()
				.orElseThrow(() -> new ChannelNotFoundException(channelId));
	}
}
