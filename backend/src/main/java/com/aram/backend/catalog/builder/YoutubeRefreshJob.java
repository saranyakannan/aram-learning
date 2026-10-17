package com.aram.backend.catalog.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Entry point for the YouTube data refresh. Active only with {@code ARAM_REFRESH_ENABLED=true}; runs once and exits with a
 * non-zero code if any batch failed or a channel could not be refreshed. {@code --dry-run} reads YouTube and reports, writing nothing.
 * Run at least every 14 days so stored data never passes YouTube's 30-day limit.
 */
@Component
@ConditionalOnProperty(prefix = "aram", name = "refresh-enabled", havingValue = "true")
public class YoutubeRefreshJob implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(YoutubeRefreshJob.class);

	private final YoutubeRefreshService service;
	private final ApplicationContext context;

	public YoutubeRefreshJob(YoutubeRefreshService service, ApplicationContext context) {
		this.service = service;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			YoutubeRefreshService.Result result = service.run(args.containsOption("dry-run"));
			if (!result.vanishedIds().isEmpty()) {
				log.warn("No longer on YouTube (now out of the live catalog): {}", result.vanishedIds());
			}
			code = result.failed() > 0 ? 1 : 0;
		} catch (RuntimeException e) {
			log.error("YouTube refresh failed: {}", e.getClass().getSimpleName(), e);
			code = 1;
		}
		int exitCode = code;
		System.exit(SpringApplication.exit(context, () -> exitCode));
	}
}
