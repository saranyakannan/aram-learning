package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.AgeMath;
import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.config.AramProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One builder run: discover candidates, filter them, have the model assess up to a cap, and write a review sheet for a person.
 * A dry run stops after discovery and filtering and writes nothing. Nothing here approves a source.
 */
@Service
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CatalogBuilderService {

	public record Options(boolean dryRun, int maxSearches, int maxNew, boolean youtube, boolean podcasts, Set<String> includeHeld) {
	}

	public record Report(int found, int searches, int quotaUnits, Map<String, Integer> dropped, int held, int assessed, int cleared,
			int refusedByModel, int alreadyExisting, int failed, Path reviewSheet, Path heldSheet, List<String> notes, int skippedOtherLanguage) {
	}

	private static final Logger log = LoggerFactory.getLogger(CatalogBuilderService.class);

	private final CandidateDiscovery discovery;
	private final CandidateAssessor assessor;
	private final CatalogRepository repository;
	private final AramProperties properties;
	private final Clock clock;

	public CatalogBuilderService(CandidateDiscovery discovery, CandidateAssessor assessor, CatalogRepository repository,
			AramProperties properties, Clock clock) {
		this.discovery = discovery;
		this.assessor = assessor;
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public Report run(Options options) throws IOException {
		AramProperties.Builder settings = properties.getBuilder();
		int maxSearches = Math.min(options.maxSearches(), settings.getMaxSearches());
		CandidateDiscovery.Discovery found = discovery.discover(options.youtube(), options.podcasts(), maxSearches);
		CandidateFilter.Filtered filtered = CandidateFilter.filter(found.candidates(), repository.findAllSources(),
				settings.getMinSubscribers(), options.includeHeld(), settings.getPodcastGenres());
		List<String> notes = new ArrayList<>(found.notes());
		log.info("Found {} candidates in {} searches (about {} YouTube quota units): {} kept, {} held back, dropped {}", found.candidates().size(),
				found.youtubeSearches(), found.quotaUnits(), filtered.keep().size(), filtered.held().size(), filtered.dropped());

		if (options.dryRun()) {
			filtered.keep().stream().limit(options.maxNew()).forEach(c -> log.info("[dry run] would assess {} {} ({}){}", c.kind(), c.name(), c.link(),
					filtered.lookalikes().containsKey(c.sourceId()) ? "  LOOK-ALIKE of " + filtered.lookalikes().get(c.sourceId()) : ""));
			filtered.held().forEach(h -> log.info("[dry run] held back: {} ({}): {}", h.candidate().name(), h.candidate().link(), h.reason()));
			return new Report(found.candidates().size(), found.youtubeSearches(), found.quotaUnits(), filtered.dropped(), filtered.held().size(), 0, 0, 0, 0, 0,
					null, null, notes, 0);
		}

		List<CandidateAssessor.Outcome> outcomes = assessor.assess(filtered.keep(), Math.min(options.maxNew(), settings.getMaxNewCandidates()));
		int cleared = 0;
		int refused = 0;
		int existing = 0;
		int failed = 0;
		int skippedLanguage = 0;
		List<Map<String, String>> rows = new ArrayList<>();
		for (CandidateAssessor.Outcome outcome : outcomes) {
			switch (outcome.state()) {
				case ASSESSED -> cleared++;
				case UNDER_REVIEW -> refused++;
				case EXISTING -> existing++;
				case FAILED -> failed++;
				case SKIPPED -> skippedLanguage++;
			}
			if (outcome.assessment() != null) {
				rows.add(row(outcome, filtered.lookalikes().get(outcome.candidate().sourceId())));
			}
		}
		Path dir = Path.of(settings.getReviewDir());
		Files.createDirectories(dir);
		String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant());
		Path reviewSheet = null;
		if (!rows.isEmpty()) {
			reviewSheet = dir.resolve("review-" + stamp + ".csv");
			Files.writeString(reviewSheet, ReviewSheet.toCsv(rows), StandardCharsets.UTF_8);
		}
		Path heldSheet = null;
		if (!filtered.held().isEmpty()) {
			List<Map<String, String>> heldRows = new ArrayList<>();
			for (CandidateFilter.Held held : filtered.held()) {
				Map<String, String> r = new LinkedHashMap<>();
				r.put("source_id", held.candidate().sourceId());
				r.put("kind", held.candidate().kind().name());
				r.put("name", held.candidate().name());
				r.put("link", held.candidate().link());
				r.put("subscribers", held.candidate().subscribers() == null ? "" : held.candidate().subscribers().toString());
				r.put("made_for_kids", String.valueOf(held.candidate().madeForKids()));
				r.put("status", "HELD BACK");
				r.put("reason", held.reason() + ". To assess it anyway, rerun with --include-held=" + held.candidate().youtube().channelId());
				r.put("found_by", held.candidate().foundByQuery());
				heldRows.add(r);
			}
			heldSheet = dir.resolve("held-back-" + stamp + ".csv");
			Files.writeString(heldSheet, ReviewSheet.toCsv(heldRows), StandardCharsets.UTF_8);
		}
		log.info("Assessed {} candidates: {} cleared by the model, {} refused, {} already existed, {} failed. Review sheet: {}", outcomes.size(), cleared,
				refused, existing, failed, reviewSheet);
		return new Report(found.candidates().size(), found.youtubeSearches(), found.quotaUnits(), filtered.dropped(), filtered.held().size(),
				outcomes.size(), cleared, refused, existing, failed, reviewSheet, heldSheet, notes, skippedLanguage);
	}

	private static Map<String, String> row(CandidateAssessor.Outcome outcome, String lookalikeOf) {
		Candidate candidate = outcome.candidate();
		ContentSource source = outcome.source();
		ContentAssessment assessment = outcome.assessment();
		Map<String, String> row = new LinkedHashMap<>();
		row.put("source_id", source.id());
		row.put("kind", candidate.kind().name());
		row.put("name", source.name());
		row.put("link", source.canonicalUrl());
		row.put("subscribers", candidate.subscribers() == null ? "" : candidate.subscribers().toString());
		row.put("made_for_kids", candidate.madeForKids() == null ? "unknown" : candidate.madeForKids().toString());
		row.put("status", outcome.state() == CandidateAssessor.State.ASSESSED ? "READY (model cleared)" : "MODEL REFUSED (cannot be approved)");
		row.put("model_verdict", assessment.verdict() == null ? "" : assessment.verdict());
		row.put("model_confidence", assessment.confidenceLabel() == null ? "" : assessment.confidenceLabel());
		row.put("suggested_min_age_years", assessment.minAgeMonths() == null ? "" : String.valueOf(AgeMath.wholeYears(assessment.minAgeMonths())));
		row.put("suggested_max_age_years", assessment.maxAgeMonths() == null ? "" : String.valueOf(AgeMath.wholeYears(assessment.maxAgeMonths())));
		row.put("suggested_tags", String.join(";", assessment.suggestedTagIds()));
		row.put("reason", assessment.summary() == null ? "" : assessment.summary());
		row.put("concerns", String.join("; ", assessment.safetyFindings()));
		row.put("lookalike_of", lookalikeOf == null ? "" : lookalikeOf);
		row.put("found_by", candidate.foundByQuery());
		return row;
	}
}
