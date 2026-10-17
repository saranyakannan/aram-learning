package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.AgeMath;
import com.aram.backend.catalog.CatalogRepository;
import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.model.Tag;
import com.aram.backend.service.TagCatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Applies a person's decisions from a review sheet. This is the only way a source becomes {@code APPROVED_FOR_RECOMMENDATION}
 * other than the developer-reviewed seed files. The whole sheet is validated first and nothing is written if any row is wrong; each
 * decision then appends a {@code HUMAN_REVIEW} assessment (history is never edited). Applying the same sheet twice changes nothing.
 */
@Service
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CatalogReviewService {

	public record Result(int approved, int rejected, int skipped, int alreadyDone) {
	}

	/** Thrown with every problem found, one per line, before anything is written. */
	public static class InvalidReviewException extends RuntimeException {
		private final List<String> problems;

		public InvalidReviewException(List<String> problems) {
			super("The review sheet has " + problems.size() + " problem(s):\n - " + String.join("\n - ", problems));
			this.problems = List.copyOf(problems);
		}

		public List<String> problems() {
			return problems;
		}
	}

	private record Decision(ContentSource source, boolean approve, List<String> tags, Integer minMonths, Integer maxMonths, String notes) {
	}

	private final CatalogRepository repository;
	private final TagCatalogService taxonomy;
	private final Clock clock;

	public CatalogReviewService(CatalogRepository repository, TagCatalogService taxonomy, Clock clock) {
		this.repository = repository;
		this.taxonomy = taxonomy;
		this.clock = clock;
	}

	public Result apply(List<Map<String, String>> rows, String reviewer) {
		Set<String> knownTags = taxonomy.getTags().stream().map(Tag::id).collect(Collectors.toSet());
		List<String> problems = new ArrayList<>();
		Map<String, Decision> decisions = new LinkedHashMap<>();
		int skipped = 0;
		int alreadyDone = 0;

		for (int i = 0; i < rows.size(); i++) {
			Map<String, String> row = rows.get(i);
			String where = "row " + (i + 2) + " (" + row.getOrDefault("source_id", "?") + ")";
			String decision = row.getOrDefault("decision", "").strip().toLowerCase();
			if (decision.isEmpty() || decision.equals("skip")) {
				skipped++;
				continue;
			}
			if (!decision.equals("approve") && !decision.equals("reject")) {
				problems.add(where + ": decision must be approve, reject or skip, not '" + decision + "'");
				continue;
			}
			String sourceId = row.getOrDefault("source_id", "");
			Optional<ContentSource> found = ContentSource.isValidId(sourceId) ? repository.findSource(sourceId) : Optional.empty();
			if (found.isEmpty()) {
				problems.add(where + ": no such source in the catalog");
				continue;
			}
			ContentSource source = found.get();
			boolean approve = decision.equals("approve");
			if (decisions.containsKey(sourceId)) {
				problems.add(where + ": the sheet decides this source more than once");
				continue;
			}
			if (approve && source.assessmentStatus() != Statuses.Assessment.ASSESSED
					&& source.assessmentStatus() != Statuses.Assessment.APPROVED_FOR_RECOMMENDATION) {
				problems.add(where + ": cannot approve a source the model did not clear (status " + source.assessmentStatus() + ")");
				continue;
			}
			if ((approve && source.assessmentStatus() == Statuses.Assessment.APPROVED_FOR_RECOMMENDATION)
					|| (!approve && source.assessmentStatus() == Statuses.Assessment.REJECTED)) {
				alreadyDone++;
				continue;
			}

			List<String> tags = splitTags(row.getOrDefault("final_tags", ""));
			for (String tag : tags) {
				if (!knownTags.contains(tag)) {
					problems.add(where + ": unknown tag '" + tag + "'");
				}
			}
			if (tags.isEmpty()) {
				tags = source.tagIds();
			}
			Integer minMonths = source.minAgeMonths();
			Integer maxMonths = source.maxAgeMonths();
			String minText = row.getOrDefault("final_min_age_years", "");
			String maxText = row.getOrDefault("final_max_age_years", "");
			if (!minText.isEmpty() || !maxText.isEmpty()) {
				Integer min = parseAge(minText);
				Integer max = parseAge(maxText);
				if (min == null || max == null || min < 0 || max > AgeMath.MAX_AGE_YEARS || min > max) {
					problems.add(where + ": ages must both be whole years from 0 to " + AgeMath.MAX_AGE_YEARS + " with min <= max");
				} else {
					minMonths = AgeMath.minMonthsForYears(min);
					maxMonths = AgeMath.maxMonthsForYears(max);
				}
			}
			if (approve && (tags.isEmpty() || minMonths == null || maxMonths == null)) {
				problems.add(where + ": an approved source needs tags and an age range");
			}
			decisions.put(sourceId, new Decision(source, approve, tags, minMonths, maxMonths, row.getOrDefault("notes", "")));
		}
		if (!problems.isEmpty()) {
			throw new InvalidReviewException(problems);
		}

		int approved = 0;
		int rejected = 0;
		Instant now = clock.instant();
		for (Decision decision : decisions.values()) {
			if (decision.approve()) {
				approve(decision, reviewer, now);
				approved++;
			} else {
				reject(decision, reviewer, now);
				rejected++;
			}
		}
		return new Result(approved, rejected, skipped, alreadyDone);
	}

	private void approve(Decision decision, String reviewer, Instant now) {
		ContentSource source = decision.source();
		List<String> limitations = new ArrayList<>(List.of("Approved by a person from the channel or show details and the model's draft; individual videos or episodes were not reviewed."));
		ContentAssessment assessment = new ContentAssessment(null, ContentAssessment.CONTENT_SOURCE, source.id(), Statuses.Assessor.HUMAN_REVIEW,
				Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, null, null, decision.minMonths(), decision.maxMonths(), decision.tags(),
				List.of(), "UNKNOWN", List.of(), decision.notes(), "HUMAN_REVIEW", List.of(), limitations, null, null,
				ContentAssessment.POLICY_VERSION, null, now, reviewer, now);
		String assessmentId = repository.addAssessment(assessment);
		repository.saveSource(new ContentSource(source.id(), source.name(), source.normalizedName(), source.sourceType(), source.platform(),
				source.canonicalUrl(), source.platformSourceId(), source.description(), decision.tags(), source.originTypes(),
				Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, assessmentId, decision.minMonths(), decision.maxMonths(),
				source.handle(), source.thumbnailUrl(), source.subscriberCount(), source.createdAt(), now, source.lastVerifiedAt(), source.feedUrl()));
	}

	private void reject(Decision decision, String reviewer, Instant now) {
		ContentSource source = decision.source();
		ContentAssessment assessment = new ContentAssessment(null, ContentAssessment.CONTENT_SOURCE, source.id(), Statuses.Assessor.HUMAN_REVIEW,
				Statuses.Assessment.REJECTED, null, null, source.minAgeMonths(), source.maxAgeMonths(), source.tagIds(), List.of(), "UNKNOWN",
				List.of(), decision.notes(), "HUMAN_REVIEW", List.of(), List.of("Rejected by a person during catalog review."), null, null,
				ContentAssessment.POLICY_VERSION, null, now, reviewer, now);
		String assessmentId = repository.addAssessment(assessment);
		repository.saveSource(new ContentSource(source.id(), source.name(), source.normalizedName(), source.sourceType(), source.platform(),
				source.canonicalUrl(), source.platformSourceId(), source.description(), source.tagIds(), source.originTypes(),
				Statuses.Catalog.RETIRED, Statuses.Assessment.REJECTED, assessmentId, source.minAgeMonths(), source.maxAgeMonths(),
				source.handle(), source.thumbnailUrl(), source.subscriberCount(), source.createdAt(), now, source.lastVerifiedAt(), source.feedUrl()));
	}

	private static List<String> splitTags(String text) {
		return new ArrayList<>(java.util.Arrays.stream(text.split("[;,]")).map(String::strip).filter(tag -> !tag.isEmpty())
				.collect(Collectors.toCollection(LinkedHashSet::new)));
	}

	private static Integer parseAge(String text) {
		try {
			return Integer.valueOf(text.strip());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
