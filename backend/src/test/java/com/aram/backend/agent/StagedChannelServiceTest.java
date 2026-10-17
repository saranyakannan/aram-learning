package com.aram.backend.agent;

import com.aram.backend.catalog.ChildCatalogEntry;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.ContentSubmission;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.exception.InvalidRequestException;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.model.SafetyAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedChannelServiceTest {

	private static final String PARENT = TestWorld.PARENT;
	private static final int AGE = 55;

	private TestWorld world;
	private String childId;
	private final String yt = youtubeId('z');
	private final String sourceId = "youtube_" + yt;
	private final ChannelFacts facts = new ChannelFacts(yt, "Bluey", "@Bluey", "Family stories.", "https://t/x.jpg", 5L, true);

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		childId = world.onboard(PARENT, AGE).childId();
	}

	private static SafetyAssessment assessment(SafetyAssessment.Verdict verdict, SafetyAssessment.Confidence confidence,
			boolean ageOk) {
		return new SafetyAssessment(SafetyAssessment.Status.CHECKED, verdict, ageOk, confidence, List.of(), "Calm stories.",
				3, 6, "Stories", List.of("emotions"), "gemini-3.8-flash", 1L);
	}

	private static SafetyAssessment safe() {
		return assessment(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, true);
	}

	@Test
	void aClearedNewChannelIsRecordedInTheCatalogAndStagedAsASubmissionAwaitingSave() {
		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, facts, safe());

		assertThat(outcome.staged()).isTrue();
		assertThat(outcome.channel().id()).isEqualTo(sourceId);
		assertThat(outcome.channel().alreadyInCatalog()).isFalse();
		assertThat(outcome.channel().safety().reason()).isEqualTo("Calm stories.");
		assertThat(outcome.channel().safety().category()).isEqualTo("Social-Emotional Learning");
		assertThat(world.catalogRepo.sources).containsKey(sourceId);
		assertThat(world.catalogRepo.assessments).hasSize(1);
		ContentSubmission submission = world.submissionRepo.all.get(childId + "_" + sourceId);
		assertThat(submission.status()).isEqualTo(Statuses.Submission.READY_FOR_PARENT);
		assertThat(submission.submittedByParentId()).isEqualTo(PARENT);
		assertThat(submission.resolvedSourceId()).isEqualTo(sourceId);
		assertThat(submission.assessmentId()).isNotNull();
		assertThat(submission.expiresAt()).isEqualTo(world.clock.instant().plus(StagedChannelService.STAGED_TTL));
		assertThat(world.family.entries).isEmpty();
		assertThat(world.staged.listStaged(PARENT)).extracting(StagedChannelService.StagedChannel::id).containsExactly(sourceId);
	}

	@Test
	void anythingNotClearlySafeIsNeitherStagedNorOfferedAndIsKeptOutOfTheLiveCatalog() {
		for (SafetyAssessment unsafe : List.of(
				assessment(SafetyAssessment.Verdict.NOT_CLEARLY_SAFE, SafetyAssessment.Confidence.HIGH, true),
				assessment(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.MEDIUM, true))) {
			TestWorld fresh = new TestWorld();
			fresh.onboard(PARENT, AGE);

			StagedChannelService.StageOutcome outcome = fresh.staged.recordAssessment(PARENT, facts, unsafe);

			assertThat(outcome.staged()).isFalse();
			assertThat(outcome.message()).contains("isn't clearly safe");
			assertThat(fresh.staged.listStaged(PARENT)).isEmpty();
			assertThat(fresh.catalog.recommendable()).isEmpty();
			assertThat(fresh.submissionRepo.all.values()).allMatch(s -> s.status() == Statuses.Submission.REVIEW_REQUIRED);
		}
	}

	@Test
	void aSafeChannelForADifferentAgeIsNotStagedForThisChild() {
		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, facts,
				new SafetyAssessment(SafetyAssessment.Status.CHECKED, SafetyAssessment.Verdict.SAFE, false,
						SafetyAssessment.Confidence.HIGH, List.of(), "For older kids.", 9, 12, "Science", List.of(), "m", 1L));

		assertThat(outcome.staged()).isFalse();
		assertThat(outcome.message()).contains("different age");
	}

	@Test
	void aSafetyCheckThatCouldNotRunStoresNoSourceAndRecordsAFailedSubmission() {
		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, facts,
				SafetyAssessment.unavailable("down", "m", 1L));

		assertThat(outcome.staged()).isFalse();
		assertThat(world.catalogRepo.sources).isEmpty();
		assertThat(world.catalogRepo.assessments).isEmpty();
		assertThat(world.submissionRepo.all.get(childId + "_" + sourceId).status()).isEqualTo(Statuses.Submission.FAILED);
	}

	@Test
	void aSourceAnotherParentAlreadyRecordedIsReusedNotDuplicatedOrReassessed() {
		world.onboard(TestWorld.OTHER_PARENT, AGE);
		world.staged.recordAssessment(TestWorld.OTHER_PARENT, facts, safe());

		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, facts, safe());

		assertThat(world.catalogRepo.sources).hasSize(1);
		assertThat(world.catalogRepo.assessments).hasSize(1);
		assertThat(outcome.staged()).isTrue();
		assertThat(outcome.channel().alreadyInCatalog()).isTrue();
		assertThat(world.submissionRepo.all.get(childId + "_" + sourceId).duplicateOfSourceId()).isEqualTo(sourceId);
	}

	@Test
	void anExistingCatalogChannelIsStagedWithoutAnyNewAssessment() {
		ContentSource curated = world.addSource(source(yt, "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 83, "emotions"));

		StagedChannelService.StageOutcome outcome = world.staged.stageExisting(PARENT, curated);

		assertThat(outcome.staged()).isTrue();
		assertThat(outcome.channel().alreadyInCatalog()).isTrue();
		assertThat(world.catalogRepo.assessments).isEmpty();
	}

	@Test
	void anExistingChannelTheChildAlreadyHasIsNotStagedAgain() {
		ContentSource curated = world.addSource(source(yt, "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 83, "emotions"));
		world.childCatalog.approve(PARENT, childId, AGE, List.of(curated.id()));

		StagedChannelService.StageOutcome outcome = world.staged.stageExisting(PARENT, curated);

		assertThat(outcome.staged()).isFalse();
		assertThat(outcome.alreadyOnList()).isTrue();
		assertThat(outcome.message()).contains("already");
	}

	@Test
	void anExistingChannelTheModelNeverClearedStaysBlockedEvenIfAskedForAgain() {
		ContentSource blocked = world.addSource(source(yt, "Bluey", Statuses.Assessment.REVIEW_REQUIRED, 36, 83, "emotions"));

		StagedChannelService.StageOutcome outcome = world.staged.stageExisting(PARENT, blocked);

		assertThat(outcome.staged()).isFalse();
		assertThat(outcome.message()).contains("isn't clearly safe");
		assertThat(world.staged.listStaged(PARENT)).isEmpty();
	}

	@Test
	void savingApprovesTheChannelForTheChildAndCompletesTheSubmission() {
		world.staged.recordAssessment(PARENT, facts, safe());

		ParentProfile profile = world.staged.save(PARENT, List.of(sourceId));

		assertThat(profile.getChannelIds()).containsExactly(sourceId);
		ChildCatalogEntry entry = world.family.entries.get(childId + "_" + sourceId);
		assertThat(entry.status()).isEqualTo(Statuses.Entry.APPROVED);
		assertThat(entry.approvedBy()).isEqualTo(PARENT);
		assertThat(world.submissionRepo.all.get(childId + "_" + sourceId).status()).isEqualTo(Statuses.Submission.COMPLETED);
		assertThat(world.staged.listStaged(PARENT)).isEmpty();
	}

	@Test
	void savingAStagedExistingChannelNeverCreatesASecondSource() {
		ContentSource curated = world.addSource(source(yt, "Bluey", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 83, "emotions"));
		world.staged.stageExisting(PARENT, curated);

		world.staged.save(PARENT, List.of(curated.id()));

		assertThat(world.catalogRepo.sources).hasSize(1);
		assertThat(world.childCatalog.isApproved(childId, curated.id())).isTrue();
	}

	@Test
	void nothingCanBeSavedThatWasNotStagedForThisParent() {
		assertThatThrownBy(() -> world.staged.save(PARENT, List.of(sourceId)))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("no longer waiting");
		world.onboard(TestWorld.OTHER_PARENT, AGE);
		world.staged.recordAssessment(TestWorld.OTHER_PARENT, facts, safe());

		assertThatThrownBy(() -> world.staged.save(PARENT, List.of(sourceId))).isInstanceOf(InvalidRequestException.class);
		assertThat(world.family.entries).isEmpty();
	}

	@Test
	void savingReChecksThePolicyAgainstTheStoredAssessment() {
		world.staged.recordAssessment(PARENT, facts, safe());
		world.catalogRepo.sources.computeIfPresent(sourceId, (id, s) -> new ContentSource(s.id(), s.name(), s.normalizedName(),
				s.sourceType(), s.platform(), s.canonicalUrl(), s.platformSourceId(), s.description(), s.tagIds(),
				s.originTypes(), s.catalogStatus(), Statuses.Assessment.REJECTED, s.latestAssessmentId(), s.minAgeMonths(),
				s.maxAgeMonths(), s.handle(), s.thumbnailUrl(), s.subscriberCount(), s.createdAt(), s.updatedAt(),
				s.lastVerifiedAt()));

		assertThatThrownBy(() -> world.staged.save(PARENT, List.of(sourceId))).isInstanceOf(InvalidRequestException.class);
		assertThat(world.family.entries).isEmpty();
		assertThat(world.staged.listStaged(PARENT)).isEmpty();
	}

	@Test
	void aStagedChannelExpiresAfterADay() {
		world.staged.recordAssessment(PARENT, facts, safe());
		TestWorld later = new TestWorld(Instant.parse("2026-10-15T12:00:01Z"));
		later.catalogRepo.sources.putAll(world.catalogRepo.sources);
		later.catalogRepo.assessments.putAll(world.catalogRepo.assessments);
		later.family.parents.putAll(world.family.parents);
		later.family.children.putAll(world.family.children);
		later.submissionRepo.all.putAll(world.submissionRepo.all);

		assertThat(later.staged.listStaged(PARENT)).isEmpty();
		assertThatThrownBy(() -> later.staged.save(PARENT, List.of(sourceId))).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void discardingDismissesTheSubmissionWithoutApprovingAnything() {
		world.staged.recordAssessment(PARENT, facts, safe());

		world.staged.discard(PARENT, List.of(sourceId));

		assertThat(world.submissionRepo.all.get(childId + "_" + sourceId).status()).isEqualTo(Statuses.Submission.DISMISSED);
		assertThat(world.staged.listStaged(PARENT)).isEmpty();
		assertThat(world.family.entries).isEmpty();
	}

	private static final com.aram.backend.catalog.SourceDescriptor SHOW = new com.aram.backend.podcast.PodcastFacts(
			1233834541L, "Wow in the World", "Tinkercast", "Science stories.", "https://rss.art19.com/wow-in-the-world",
			"https://img.example.com/a.jpg", false, "Education for Kids").toDescriptor();

	@Test
	void aClearedPodcastIsRecordedAsAPodcastSourceStagedAndSavedLikeAChannel() {
		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, SHOW,
				new SafetyAssessment(SafetyAssessment.Status.CHECKED, SafetyAssessment.Verdict.SAFE, true,
						SafetyAssessment.Confidence.HIGH, List.of(), "Gentle science stories.", 3, 8, "Science",
						List.of("math"), "gemini-3.8-flash", 1L));

		assertThat(outcome.staged()).isTrue();
		assertThat(outcome.channel().id()).isEqualTo("podcast_1233834541");
		assertThat(outcome.channel().sourceType()).isEqualTo("PODCAST_FEED");
		assertThat(outcome.channel().facts().kind()).isEqualTo("PODCAST");
		ContentSource stored = world.catalogRepo.sources.get("podcast_1233834541");
		assertThat(stored.feedUrl()).isEqualTo("https://rss.art19.com/wow-in-the-world");
		assertThat(stored.platform()).isEqualTo("APPLE_PODCASTS");
		assertThat(world.catalogRepo.assessments.get(stored.latestAssessmentId()).limitations())
				.anyMatch(text -> text.contains("no audio was reviewed"));
		assertThat(world.submissionRepo.all.get(childId + "_podcast_1233834541").submissionType()).isEqualTo("PODCAST");

		ParentProfile profile = world.staged.save(PARENT, List.of("podcast_1233834541"));

		assertThat(profile.getChannelIds()).containsExactly("podcast_1233834541");
		assertThat(world.parentChannels().approvedPodcasts(PARENT)).extracting(ContentSource::id)
				.containsExactly("podcast_1233834541");
	}

	@Test
	void aPodcastThatIsNotClearlySafeIsNeverStagedOrOffered() {
		StagedChannelService.StageOutcome outcome = world.staged.recordAssessment(PARENT, SHOW,
				new SafetyAssessment(SafetyAssessment.Status.CHECKED, SafetyAssessment.Verdict.NOT_CLEARLY_SAFE, false,
						SafetyAssessment.Confidence.HIGH, List.of("Marked explicit"), "Explicit.", null, null, null, List.of(),
						"explicit-flag", 1L));

		assertThat(outcome.staged()).isFalse();
		assertThat(world.catalog.recommendable()).isEmpty();
		assertThat(world.staged.listStaged(PARENT)).isEmpty();
		assertThat(world.catalogRepo.sources.get("podcast_1233834541").catalogStatus()).isEqualTo(Statuses.Catalog.UNDER_REVIEW);
	}

	@Test
	void idsAndBatchSizesAreValidated() {
		assertThatThrownBy(() -> world.staged.save(PARENT, List.of("../x"))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.staged.save(PARENT, List.of())).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.staged.save(PARENT, null)).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.staged.discard(PARENT, List.of("numberblocks"))).isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.staged.recordAssessment("../x", facts, safe())).isInstanceOf(InvalidRequestException.class);
	}
}
