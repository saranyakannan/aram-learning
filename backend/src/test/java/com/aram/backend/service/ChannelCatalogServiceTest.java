package com.aram.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.ChannelNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelCatalogServiceTest {

	private ChannelCatalogService service;

	@BeforeEach
	void setUp() {
		AramProperties properties = new AramProperties();
		properties.setChannelsFile("classpath:channels.json");
		service = new ChannelCatalogService(new DefaultResourceLoader(), properties, new ObjectMapper());
		service.loadChannels();
	}

	@Test
	void loadsCuratedChannelsFromJson() {
		assertThat(service.getChannels()).isNotEmpty();
	}

	@Test
	void getChannelReturnsMatchingChannel() {
		var channel = service.getChannels().get(0);
		assertThat(service.getChannel(channel.id())).isEqualTo(channel);
	}

	@Test
	void getChannelThrowsWhenMissing() {
		assertThatThrownBy(() -> service.getChannel("does-not-exist"))
				.isInstanceOf(ChannelNotFoundException.class);
	}
}
