package com.aram.backend.catalog;

import com.aram.backend.model.Channel;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.SafetyAssessment;
import com.aram.backend.model.Tag;
import com.aram.backend.service.TagCatalogService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The one canonical catalog: finding sources, recording new ones with their assessment, and presenting them as channels. */
@Service
public class CatalogService {

	/** What {@link #registerAssessed} did. {@code created} is false when the source already existed (then {@code assessment} is null). */
	public record Registered(ContentSource source, ContentAssessment assessment, boolean created) {
	}

	private final CatalogRepository repository;
	private final TagCatalogService taxonomy;
	private final Clock clock;

	public CatalogService(CatalogRepository repository, TagCatalogService taxonomy, Clock clock) {
		this.repository = repository;
		this.taxonomy = taxonomy;
		this.clock = clock;
	}

	public Optional<ContentSource> findSource(String sourceId) {
		return repository.findSource(sourceId);
	}

	/** The known sources among the ids, in the order asked (one batched read). */
	public List<ContentSource> findSources(java.util.Collection<String> sourceIds) {
		return repository.findSourcesByIds(sourceIds);
	}

	public Optional<ContentAssessment> findAssessment(ContentSource source) {
		return source.latestAssessmentId() == null ? Optional.empty() : repository.findAssessment(source.latestAssessmentId());
	}

	/** Every source shown to parents as a suggestion: live catalog, human-cleared. */
	public List<ContentSource> recommendable() {
		return repository.findSources(Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
	}

	/**
	 * Records a channel the agent just assessed, exactly once per platform channel: the source, then its first
	 * assessment. A model answer that is not a confident SAFE keeps the source out of the live catalog
	 * ({@code UNDER_REVIEW}) so it is never offered, yet later parents asking for it are recognised as duplicates.
	 */
	public Registered registerAssessed(ChannelFacts facts, SafetyAssessment safety, int childAgeMonths) {
		return registerAssessed(SourceDescriptor.youtube(facts), safety, childAgeMonths);
	}

	/** The same registration for any kind of source (YouTube channel or podcast show). */
	public Registered registerAssessed(SourceDescriptor descriptor, SafetyAssessment safety, int childAgeMonths) {
		return registerAssessed(descriptor, safety, childAgeMonths, ContentSource.PARENT_SUBMITTED);
	}

	/** As above, recording where the source came from (provenance, not approval). */
	public Registered registerAssessed(SourceDescriptor descriptor, SafetyAssessment safety, int childAgeMonths, String origin) {
		Instant now = clock.instant();
		String sourceId = descriptor.sourceId();
		Optional<ContentSource> already = repository.findSource(sourceId);
		if (already.isPresent()) {
			return new Registered(already.get(), null, false);
		}

		boolean cleared = safety.status() == SafetyAssessment.Status.CHECKED
				&& safety.verdict() == SafetyAssessment.Verdict.SAFE
				&& safety.confidence() == SafetyAssessment.Confidence.HIGH;

		Integer min = safety.estimatedMinAge() == null ? null : AgeMath.minMonthsForYears(safety.estimatedMinAge());
		Integer max = safety.estimatedMaxAge() == null ? null : AgeMath.maxMonthsForYears(safety.estimatedMaxAge());
		java.util.ArrayList<String> limitations = new java.util.ArrayList<>(List.of(descriptor.limitation()));
		if ((min == null || max == null) && safety.ageAppropriate()) {
			int year = AgeMath.wholeYears(childAgeMonths);
			min = AgeMath.minMonthsForYears(year);
			max = AgeMath.maxMonthsForYears(year);
			limitations.add("The model gave no age range, so approval is limited to the age year of the child it was checked for.");
		}
		boolean contradictory = !safety.ageAppropriate() && min != null && max != null
				&& childAgeMonths >= min && childAgeMonths <= max;
		if (contradictory) {
			cleared = false;
		}

		Statuses.Assessment status = cleared ? Statuses.Assessment.ASSESSED : Statuses.Assessment.REVIEW_REQUIRED;
		ContentAssessment assessment = new ContentAssessment(null, ContentAssessment.CONTENT_SOURCE, sourceId,
				Statuses.Assessor.LLM, status, safety.verdict().name(), safety.confidence().name(), min, max,
				safety.tags(), List.of(), "UNKNOWN", safety.concerns(), safety.reason(), ContentAssessment.METADATA_ONLY,
				List.of(), limitations, safety.model(), safety.model(), ContentAssessment.POLICY_VERSION, childAgeMonths,
				now, null, null);
		String assessmentId = repository.addAssessment(assessment);

		ContentSource source = new ContentSource(sourceId, descriptor.name(), ContentSource.normalize(descriptor.name()),
				descriptor.sourceType(), descriptor.platform(), descriptor.canonicalUrl(), descriptor.platformSourceId(),
				descriptor.description(), safety.tags(), List.of(origin),
				cleared ? Statuses.Catalog.ACTIVE : Statuses.Catalog.UNDER_REVIEW, status, assessmentId, min, max,
				descriptor.handle(), descriptor.thumbnailUrl(), descriptor.subscriberCount(), now, now, now,
				descriptor.feedUrl());

		if (!repository.createSource(source)) {
			return new Registered(repository.findSource(sourceId).orElse(source), null, false);
		}
		return new Registered(source, assessment.withId(assessmentId), true);
	}

	public static String canonicalUrl(ChannelFacts facts) {
		return SourceDescriptor.youtube(facts).canonicalUrl();
	}

	/** How a source is presented to the UI and to the video service. Ages are shown in whole years. */
	public Channel toChannel(ContentSource source) {
		String firstTag = source.tagIds().isEmpty() ? null : source.tagIds().get(0);
		return new Channel(source.id(), source.name(), firstTag == null ? "Other" : taxonomy.categoryNameForTag(firstTag),
				source.description(), source.platformSourceId(), source.handle(),
				AgeMath.toWholeYearsOrNull(source.minAgeMonths()), AgeMath.toWholeYearsOrNull(source.maxAgeMonths()),
				source.tagIds(), source.isStaleYoutubeData(clock.instant()) ? null : source.thumbnailUrl(), source.sourceType());
	}

	/** Tag labels are not looked up here; see {@link Tag} for the taxonomy. */
	public List<Tag> tags() {
		return taxonomy.getTags();
	}
}
