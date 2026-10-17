package com.aram.backend.catalog.builder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aram.backend.config.AramProperties;

/**
 * Entry point of the catalog builder; active only with {@code ARAM_BUILDER_ENABLED=true}. Runs once and exits.
 *
 * <p>Options: {@code --dry-run}, {@code --max-searches=n}, {@code --max-new=n}, {@code --youtube-only}, {@code --podcasts-only},
 * {@code --include-held=UC...,UC...} (assess channels held back as "not made for kids"), and {@code --apply-review=<csv>}
 * (apply a reviewed sheet; {@code --reviewer=label} records who decided).
 */
@Component
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CatalogBuilderJob implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CatalogBuilderJob.class);

	private final CatalogBuilderService builder;
	private final CatalogReviewService reviews;
	private final AramProperties properties;
	private final ApplicationContext context;

	public CatalogBuilderJob(CatalogBuilderService builder, CatalogReviewService reviews, AramProperties properties, ApplicationContext context) {
		this.builder = builder;
		this.reviews = reviews;
		this.properties = properties;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			code = execute(args);
		} catch (CatalogReviewService.InvalidReviewException e) {
			log.error("{}\nNothing was changed.", e.getMessage());
			code = 2;
		} catch (Exception e) {
			log.error("Catalog builder failed: {}", e.getClass().getSimpleName(), e);
			code = 1;
		}
		int exitCode = code;
		System.exit(SpringApplication.exit(context, () -> exitCode));
	}

	int execute(ApplicationArguments args) throws Exception {
		List<String> review = args.getOptionValues("apply-review");
		if (review != null && !review.isEmpty()) {
			String reviewer = first(args, "reviewer", properties.getBuilder().getReviewer());
			var rows = ReviewSheet.parse(Files.readString(Path.of(review.get(0)), StandardCharsets.UTF_8));
			CatalogReviewService.Result result = reviews.apply(rows, reviewer);
			log.info("Review applied: {} approved, {} rejected, {} skipped, {} already done", result.approved(), result.rejected(), result.skipped(),
					result.alreadyDone());
			return 0;
		}
		Set<String> includeHeld = new HashSet<>();
		List<String> held = args.getOptionValues("include-held");
		if (held != null) {
			for (String value : held) {
				for (String id : value.split(",")) {
					if (!id.isBlank()) {
						includeHeld.add(id.strip());
					}
				}
			}
		}
		AramProperties.Builder settings = properties.getBuilder();
		boolean youtubeOnly = args.containsOption("youtube-only");
		boolean podcastsOnly = args.containsOption("podcasts-only");
		CatalogBuilderService.Report report = builder.run(new CatalogBuilderService.Options(args.containsOption("dry-run"),
				Integer.parseInt(first(args, "max-searches", String.valueOf(settings.getMaxSearches()))),
				Integer.parseInt(first(args, "max-new", String.valueOf(settings.getMaxNewCandidates()))), !podcastsOnly, !youtubeOnly, includeHeld));
		log.info("Builder report: {}", report);
		return 0;
	}

	private static String first(ApplicationArguments args, String name, String fallback) {
		List<String> values = args.getOptionValues(name);
		return values == null || values.isEmpty() ? fallback : values.get(0);
	}
}
