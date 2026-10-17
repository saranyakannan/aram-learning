package com.aram.backend.catalog;

import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.SafetyAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;

class CatalogServiceTest {

	private static final int AGE_MONTHS = 48;

	private TestWorld world;
	private final String id = youtubeId('b');
	private final ChannelFacts facts = new ChannelFacts(id, "Bluey", "@Bluey", "Family stories.", "https://t/x.jpg", 5L, true);

	@BeforeEach
	void setUp() {
		world = new TestWorld();
	}

	private static SafetyAssessment safety(SafetyAssessment.Verdict verdict, SafetyAssessment.Confidence confidence,
			boolean ageOk, Integer min, Integer max) {
		return new SafetyAssessment(SafetyAssessment.Status.CHECKED, verdict, ageOk, confidence, List.of("none"), "Calm stories.",
				min, max, "Stories", List.of("emotions"), "gemini-3.8-flash", 1L);
	}

	@Test
	void aConfidentSafeAnswerCreatesALiveAssessedSourceWithAnAppendOnlyAssessment() {
		CatalogService.Registered registered = world.catalog.registerAssessed(facts,
				safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, true, 3, 6), AGE_MONTHS);

		ContentSource source = registered.source();
		assertThat(registered.created()).isTrue();
		assertThat(source.id()).isEqualTo("youtube_" + id);
		assertThat(source.catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
		assertThat(source.assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
		assertThat(source.originTypes()).containsExactly(ContentSource.PARENT_SUBMITTED);
		assertThat(source.minAgeMonths()).isEqualTo(36);
		assertThat(source.maxAgeMonths()).isEqualTo(83);
		assertThat(source.canonicalUrl()).isEqualTo("https://www.youtube.com/@Bluey");
		assertThat(source.latestAssessmentId()).isEqualTo(registered.assessment().id());

		ContentAssessment assessment = world.catalogRepo.assessments.get(source.latestAssessmentId());
		assertThat(assessment.assessorType()).isEqualTo(Statuses.Assessor.LLM);
		assertThat(assessment.evidenceLevel()).isEqualTo("METADATA_ONLY");
		assertThat(assessment.policyVersion()).isEqualTo("content-policy-v1");
		assertThat(assessment.modelName()).isEqualTo("gemini-3.8-flash");
		assertThat(assessment.limitations()).isNotEmpty();
		assertThat(assessment.forChildAgeMonths()).isEqualTo(AGE_MONTHS);
	}

	@Test
	void anythingNotAConfidentSafeKeepsTheSourceUnderReviewAndOutOfTheLiveCatalog() {
		for (SafetyAssessment unsafe : List.of(
				safety(SafetyAssessment.Verdict.NOT_CLEARLY_SAFE, SafetyAssessment.Confidence.HIGH, true, 3, 6),
				safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.MEDIUM, true, 3, 6))) {
			TestWorld fresh = new TestWorld();

			ContentSource source = fresh.catalog.registerAssessed(facts, unsafe, AGE_MONTHS).source();

			assertThat(source.catalogStatus()).isEqualTo(Statuses.Catalog.UNDER_REVIEW);
			assertThat(source.assessmentStatus()).isEqualTo(Statuses.Assessment.REVIEW_REQUIRED);
			assertThat(fresh.catalog.recommendable()).isEmpty();
		}
	}

	@Test
	void aMissingAgeRangeIsLimitedToTheChildsAgeYearAndSaysSo() {
		ContentSource source = world.catalog.registerAssessed(facts,
				safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, true, null, null), AGE_MONTHS).source();

		assertThat(source.minAgeMonths()).isEqualTo(48);
		assertThat(source.maxAgeMonths()).isEqualTo(59);
		assertThat(world.catalogRepo.assessments.get(source.latestAssessmentId()).limitations())
				.anyMatch(text -> text.contains("no age range"));
	}

	@Test
	void anAnswerThatContradictsItselfAboutTheChildsAgeIsNotCleared() {
		ContentSource source = world.catalog.registerAssessed(facts,
				safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, false, 3, 6), AGE_MONTHS).source();

		assertThat(source.assessmentStatus()).isEqualTo(Statuses.Assessment.REVIEW_REQUIRED);
		assertThat(source.catalogStatus()).isEqualTo(Statuses.Catalog.UNDER_REVIEW);
	}

	@Test
	void aSafeChannelForADifferentAgeIsStillRecordedWithItsOwnRange() {
		ContentSource source = world.catalog.registerAssessed(facts,
				safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, false, 8, 12), AGE_MONTHS).source();

		assertThat(source.assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
		assertThat(world.policy.forChild(source, AGE_MONTHS).eligible()).isFalse();
		assertThat(world.policy.forChild(source, 100).eligible()).isTrue();
	}

	@Test
	void theSameChannelIsNeverStoredTwiceAndTheSecondCallGetsTheExistingSource() {
		SafetyAssessment safe = safety(SafetyAssessment.Verdict.SAFE, SafetyAssessment.Confidence.HIGH, true, 3, 6);
		ContentSource first = world.catalog.registerAssessed(facts, safe, AGE_MONTHS).source();

		CatalogService.Registered second = world.catalog.registerAssessed(facts, safe, AGE_MONTHS);

		assertThat(second.created()).isFalse();
		assertThat(second.source()).isEqualTo(first);
		assertThat(world.catalogRepo.sources).hasSize(1);
	}

	@Test
	void onlyLiveHumanClearedSourcesAreRecommendable() {
		world.addSource(source(youtubeId('c'), "Cleared", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 0, 99, "music"));
		world.addSource(source(youtubeId('d'), "Only assessed", Statuses.Assessment.ASSESSED, 0, 99, "music"));

		assertThat(world.catalog.recommendable()).extracting(ContentSource::name).containsExactly("Cleared");
	}

	@Test
	void aSourceIsPresentedAsAChannelWithAgesInYearsAndACategoryFromTheTaxonomy() {
		ContentSource source = source(id, "Daniel Tiger", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 59, "emotions", "music");

		var channel = world.catalog.toChannel(source);

		assertThat(channel.id()).isEqualTo("youtube_" + id);
		assertThat(channel.youtubeChannelId()).isEqualTo(id);
		assertThat(channel.category()).isEqualTo("Social-Emotional Learning");
		assertThat(channel.minAge()).isEqualTo(2);
		assertThat(channel.maxAge()).isEqualTo(4);
		assertThat(channel.tags()).containsExactly("emotions", "music");
		assertThat(channel.thumbnailUrl()).isEqualTo(source.thumbnailUrl());
	}
}
