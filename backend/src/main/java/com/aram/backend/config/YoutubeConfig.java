package com.aram.backend.config;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.YouTubeRequestInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.GeneralSecurityException;
import java.io.IOException;

@Configuration
public class YoutubeConfig {

	@Bean
	public YouTube youTube(AramProperties properties) throws GeneralSecurityException, IOException {
		return new YouTube.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				GsonFactory.getDefaultInstance(),
				null)
				.setApplicationName("aram")
				.setYouTubeRequestInitializer(new YouTubeRequestInitializer(properties.getYoutube().getApiKey()))
				.build();
	}
}
