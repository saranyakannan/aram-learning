package com.aram.backend.agent;

import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.ContentSubmission;
import com.aram.backend.catalog.EligibilityPolicy;
import com.aram.backend.catalog.SourceDescriptor;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.SubmissionRepository;
import com.aram.backend.exception.InvalidRequestException;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.model.SafetyAssessment;
import com.aram.backend.service.ParentProfileService;
import com.aram.backend.catalog.AgeMath;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The parent's pending changes. A channel the agent has cleared for this child is staged as a submission in state
 * {@code READY_FOR_PARENT}; nothing reaches the child until the parent saves, and saving re-checks the stored
 * assessment with the shared eligibility policy. The browser only ever names source ids.
 */
@Service
public class StagedChannelService {

	/** What the Changes panel shows for one staged channel. {@code alreadyInCatalog} means Aram already had it. */
	public record StagedChannel(String id, ChannelFacts facts, StagedSafety safety, boolean alreadyInCatalog,
			String sourceType) {

		public StagedChannel(String id, ChannelFacts facts, StagedSafety safety, boolean alreadyInCatalog) {
			this(id, facts, safety, alreadyInCatalog, ContentSource.YOUTUBE_CHANNEL);
		}
	}

	public record StagedSafety(String category, Integer estimatedMinAge, Integer estimatedMaxAge, String reason,
			List<String> tags) {
	}

	/** Result of asking to stage a channel; {@code message} is safe to show the parent. */
	public record StageOutcome(boolean staged, boolean alreadyOnList, String message, StagedChannel channel) {
	}

	static final Duration STAGED_TTL = Duration.ofHours(24);
	static final int MAX_SAVE_AT_ONCE = 10;

	private static final String NOT_CLEAR = "That channel isn't clearly safe for your child, so I can't add it.";

	private final SubmissionRepository submissions;
	private final CatalogService catalog;
	private final ChildCatalogService childCatalog;
	private final EligibilityPolicy policy;
	private final ParentProfileService profileService;
	private final com.aram.backend.service.TagCatalogService taxonomy;
	private final Clock clock;

	public StagedChannelService(SubmissionRepository submissions, CatalogService catalog, ChildCatalogService childCatalog,
			EligibilityPolicy policy, ParentProfileService profileService,
			com.aram.backend.service.TagCatalogService taxonomy, Clock clock) {
		this.submissions = submissions;
		this.catalog = catalog;
		this.childCatalog = childCatalog;
		this.policy = policy;
		this.profileService = profileService;
		this.taxonomy = taxonomy;
		this.clock = clock;
	}

	/**
	 * Records a channel the agent has just assessed (source, then assessment, then the parent's submission) and stages
	 * it only if the shared policy lets this child have it. A failed check stores no source, only a failed submission.
	 */
	public StageOutcome recordAssessment(String parentId, ChannelFacts facts, SafetyAssessment assessment) {
		return recordAssessment(parentId, SourceDescriptor.youtube(facts), assessment);
	}

	/** The same for any kind of source (YouTube channel or podcast show). */
	public StageOutcome recordAssessment(String parentId, SourceDescriptor descriptor, SafetyAssessment assessment) {
		ParentProfileService.ChildContext child = profileService.requireChild(parentId);
		if (assessment.status() != SafetyAssessment.Status.CHECKED) {
			saveSubmission(child, descriptor.sourceId(), descriptor.name(), descriptor.canonicalUrl(),
					descriptor.submissionType(), null, Statuses.Submission.FAILED, null, assessment.reason());
			return new StageOutcome(false, false, NOT_CLEAR, null);
		}

		CatalogService.Registered registered = catalog.registerAssessed(descriptor, assessment, child.ageMonths());
		if (!registered.created()) {
			return stageExisting(parentId, registered.source());
		}
		return stageIfEligible(child, registered.source(), registered.assessment(), false, null);
	}

	/**
	 * Stages a channel Aram already has (curated, or added earlier by any parent). It is not assessed again; it is
	 * staged only if the policy lets this child have it, so a channel the model never cleared stays blocked.
	 */
	public StageOutcome stageExisting(String parentId, ContentSource source) {
		ParentProfileService.ChildContext child = profileService.requireChild(parentId);
		if (childCatalog.isApproved(child.childId(), source.id())) {
			return new StageOutcome(false, true, source.name() + " is already on your child's list, so there is nothing to add or save.", null);
		}
		Optional<ContentAssessment> latest = catalog.findAssessment(source);
		return stageIfEligible(child, source, latest.orElse(null), true, source.id());
	}

	private StageOutcome stageIfEligible(ParentProfileService.ChildContext child, ContentSource source,
			ContentAssessment assessment, boolean alreadyInCatalog, String duplicateOf) {
		EligibilityPolicy.Decision decision = policy.forChild(source, child.ageMonths());
		String type = source.isPodcast() ? "PODCAST" : "YOUTUBE_CHANNEL";
		String input = source.handle() != null ? source.handle() : source.name();
		if (!decision.eligible()) {
			saveSubmission(child, source.id(), input, source.canonicalUrl(), type, assessment,
					Statuses.Submission.REVIEW_REQUIRED, duplicateOf, decision.reason());
			return new StageOutcome(false, false, decision.reason(), null);
		}
		saveSubmission(child, source.id(), input, source.canonicalUrl(), type, assessment,
				Statuses.Submission.READY_FOR_PARENT, duplicateOf, null);
		return new StageOutcome(true, false, "Added to your changes. Click Save to confirm.",
				view(source, assessment, alreadyInCatalog));
	}

	private void saveSubmission(ParentProfileService.ChildContext child, String sourceId, String inputText, String inputUrl,
			String submissionType, ContentAssessment assessment, Statuses.Submission status, String duplicateOf,
			String failureReason) {
		Instant now = clock.instant();
		String id = ContentSubmission.idFor(child.childId(), sourceId);
		Instant created = submissions.find(id).map(ContentSubmission::createdAt).orElse(now);
		submissions.save(new ContentSubmission(id, child.parentId(), child.childId(), inputText, inputUrl,
				submissionType, status, sourceId, duplicateOf, assessment == null ? null : assessment.id(),
				failureReason, created, now, now.plus(STAGED_TTL)));
	}

	public List<StagedChannel> listStaged(String parentId) {
		Optional<ParentProfileService.ChildContext> child = profileService.findChild(parentId);
		if (child.isEmpty()) {
			return List.of();
		}
		Instant now = clock.instant();
		return submissions.findByParent(parentId, Statuses.Submission.READY_FOR_PARENT).stream()
				.filter(submission -> child.get().childId().equals(submission.childId()))
				.filter(submission -> submission.expiresAt() != null && submission.expiresAt().isAfter(now))
				.map(submission -> catalog.findSource(submission.resolvedSourceId())
						.filter(source -> policy.forChild(source, child.get().ageMonths()).eligible())
						.map(source -> view(source, catalog.findAssessment(source).orElse(null),
								submission.duplicateOfSourceId() != null)))
				.flatMap(Optional::stream)
				.toList();
	}

	public void discard(String parentId, List<String> sourceIds) {
		ParentProfileService.ChildContext child = profileService.requireChild(parentId);
		requireSourceIds(sourceIds);
		Instant now = clock.instant();
		for (String sourceId : sourceIds) {
			submissions.find(ContentSubmission.idFor(child.childId(), sourceId))
					.filter(submission -> submission.status() == Statuses.Submission.READY_FOR_PARENT)
					.ifPresent(submission -> submissions.save(submission.withStatus(Statuses.Submission.DISMISSED, now)));
		}
	}

	/** Approves staged channels for the child. The stored assessment is re-checked; the browser supplies no verdict. */
	public ParentProfile save(String parentId, List<String> sourceIds) {
		ParentProfileService.ChildContext child = profileService.requireChild(parentId);
		requireSourceIds(sourceIds);
		if (sourceIds.isEmpty() || sourceIds.size() > MAX_SAVE_AT_ONCE) {
			throw new InvalidRequestException("Choose between 1 and " + MAX_SAVE_AT_ONCE + " channels to save");
		}
		Instant now = clock.instant();
		List<ContentSubmission> toSave = sourceIds.stream().distinct().map(sourceId -> {
			ContentSubmission submission = submissions.find(ContentSubmission.idFor(child.childId(), sourceId)).orElse(null);
			boolean usable = submission != null && submission.status() == Statuses.Submission.READY_FOR_PARENT
					&& submission.expiresAt() != null && submission.expiresAt().isAfter(now)
					&& parentId.equals(submission.submittedByParentId());
			if (!usable) {
				throw new InvalidRequestException("That channel is no longer waiting to be saved. Ask me to add it again.");
			}
			return submission;
		}).toList();

		childCatalog.approve(parentId, child.childId(), child.ageMonths(),
				toSave.stream().map(ContentSubmission::resolvedSourceId).toList());
		toSave.forEach(submission -> submissions.save(submission.withStatus(Statuses.Submission.COMPLETED, now)));
		return profileService.getProfile(parentId);
	}

	private StagedChannel view(ContentSource source, ContentAssessment assessment, boolean alreadyInCatalog) {
		String firstTag = source.tagIds().isEmpty() ? null : source.tagIds().get(0);
		ChannelFacts facts = new ChannelFacts(source.platformSourceId(), source.name(), source.handle(),
				source.description(), source.thumbnailUrl(), source.subscriberCount(), null,
				source.isPodcast() ? ChannelFacts.PODCAST : null);
		StagedSafety safety = new StagedSafety(firstTag == null ? "Other" : taxonomy.categoryNameForTag(firstTag),
				AgeMath.toWholeYearsOrNull(source.minAgeMonths()), AgeMath.toWholeYearsOrNull(source.maxAgeMonths()),
				assessment == null ? null : assessment.summary(), source.tagIds());
		return new StagedChannel(source.id(), facts, safety, alreadyInCatalog, source.sourceType());
	}

	private static void requireSourceIds(List<String> sourceIds) {
		if (sourceIds == null) {
			throw new InvalidRequestException("channelIds must be provided");
		}
		for (String id : sourceIds) {
			if (!ContentSource.isValidId(id)) {
				throw new InvalidRequestException("That isn't a valid channel id");
			}
		}
	}
}
