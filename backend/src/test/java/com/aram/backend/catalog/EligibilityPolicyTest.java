package com.aram.backend.catalog;

import org.junit.jupiter.api.Test;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;

class EligibilityPolicyTest {

	private final EligibilityPolicy policy = new EligibilityPolicy();
	private final String id = youtubeId('a');

	@Test
	void anAssessedOrReviewedSourceIsEligibleInsideItsAgeRangeAndNotOutsideIt() {
		for (Statuses.Assessment status : new Statuses.Assessment[] { Statuses.Assessment.ASSESSED,
				Statuses.Assessment.APPROVED_FOR_RECOMMENDATION }) {
			ContentSource source = source(id, "Show", status, 24, 59, "math");

			assertThat(policy.forChild(source, 24).eligible()).isTrue();
			assertThat(policy.forChild(source, 59).eligible()).isTrue();
			assertThat(policy.forChild(source, 23).eligible()).isFalse();
			assertThat(policy.forChild(source, 60).reason()).contains("different age");
		}
	}

	@Test
	void anythingNotClearedByAnAssessmentIsNeverEligible() {
		for (Statuses.Assessment status : new Statuses.Assessment[] { Statuses.Assessment.PENDING,
				Statuses.Assessment.REVIEW_REQUIRED, Statuses.Assessment.REJECTED, Statuses.Assessment.STALE }) {
			EligibilityPolicy.Decision decision = policy.forChild(source(id, "Show", status, 0, 200, "math"), 40);

			assertThat(decision.eligible()).as(status.name()).isFalse();
			assertThat(decision.reason()).contains("isn't clearly safe");
		}
	}

	@Test
	void aSourceOutsideTheLiveCatalogIsNeverEligible() {
		ContentSource underReview = withCatalog(source(id, "Show", Statuses.Assessment.ASSESSED, 0, 200), Statuses.Catalog.UNDER_REVIEW);

		assertThat(policy.forChild(underReview, 40).eligible()).isFalse();
		assertThat(policy.stillPlayable(underReview)).isFalse();
	}

	@Test
	void missingAgeInformationMeansNotEligibleRatherThanAnyAge() {
		ContentSource noAges = new ContentSource(source(id, "Show", Statuses.Assessment.ASSESSED, 0, 0).id(), "Show", "show",
				"YOUTUBE_CHANNEL", "YOUTUBE", null, id, null, null, null, Statuses.Catalog.ACTIVE, Statuses.Assessment.ASSESSED,
				null, null, null, null, null, null, null, null, null);

		assertThat(policy.forChild(noAges, 40).eligible()).isFalse();
	}

	@Test
	void aFourYearOldMayBeOfferedSourcesWhoseMinimumAgeIsUpToAYearHigher() {
		ContentSource fiveAndUp = source(id, "Five and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "science");

		assertThat(policy.forChild(fiveAndUp, 48).eligible()).as("4y0m").isTrue();
		assertThat(policy.forChild(fiveAndUp, 59).eligible()).as("4y11m").isTrue();
		assertThat(policy.forChild(source(id, "Six and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 72, 119, "science"), 59)
				.eligible()).as("a source for 6+ is more than a year above").isFalse();
		assertThat(policy.forChild(source(id, "Edge", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 71, 119, "science"), 59).eligible())
				.as("exactly 12 months above").isTrue();
		assertThat(policy.forChild(source(id, "Edge", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 72, 119, "science"), 59).eligible())
				.as("13 months above").isFalse();
	}

	@Test
	void theStretchAppliesOnlyToFourYearOldsAndOnlyToTheLowerBound() {
		ContentSource fiveAndUp = source(id, "Five and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "science");

		assertThat(policy.forChild(fiveAndUp, 47).eligible()).as("3y11m is not stretched").isFalse();
		assertThat(policy.forChild(source(id, "Four and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 48, 119, "science"), 30)
				.eligible()).as("a 2-year-old is not stretched").isFalse();
		assertThat(policy.forChild(source(id, "Under fives", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 12, 47, "music"), 48)
				.eligible()).as("no stretch on the upper bound").isFalse();
		assertThat(policy.forChild(fiveAndUp, 60).eligible()).as("a 5-year-old is simply in range").isTrue();
	}

	@Test
	void theStretchNeverRelaxesTheSafetyChecks() {
		for (Statuses.Assessment status : new Statuses.Assessment[] { Statuses.Assessment.REVIEW_REQUIRED, Statuses.Assessment.REJECTED,
				Statuses.Assessment.STALE, Statuses.Assessment.PENDING }) {
			assertThat(policy.forChild(source(id, "Five and up", status, 60, 119, "science"), 50).eligible()).as(status.name()).isFalse();
		}
		assertThat(policy.recommendable(source(id, "Five and up", Statuses.Assessment.ASSESSED, 60, 119, "science"), 50))
				.as("still needs human review to be recommended").isFalse();
		assertThat(policy.recommendable(source(id, "Five and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "science"), 50)).isTrue();
	}

	@Test
	void theStretchCanBeSwitchedOffAndWithinRangeIgnoresIt() {
		EligibilityPolicy strict = new EligibilityPolicy(0, 48, 59);
		ContentSource fiveAndUp = source(id, "Five and up", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 60, 119, "science");

		assertThat(strict.forChild(fiveAndUp, 50).eligible()).isFalse();
		assertThat(policy.withinRange(fiveAndUp, 50)).as("range check has no stretch").isFalse();
		assertThat(policy.withinRange(fiveAndUp, 60)).isTrue();
	}

	@Test
	void onlyHumanClearedSourcesAreRecommendableToOtherParents() {
		assertThat(policy.recommendable(source(id, "S", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 0, 99), 40)).isTrue();
		assertThat(policy.recommendable(source(id, "S", Statuses.Assessment.ASSESSED, 0, 99), 40)).isFalse();
		assertThat(policy.recommendable(source(id, "S", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 0, 30), 40)).isFalse();
	}

	@Test
	void playbackOnlyChecksStatusNotAgeBecauseTheParentAlreadyApprovedIt() {
		assertThat(policy.stillPlayable(source(id, "S", Statuses.Assessment.ASSESSED, 24, 30))).isTrue();
		assertThat(policy.stillPlayable(source(id, "S", Statuses.Assessment.REJECTED, 24, 30))).isFalse();
		assertThat(policy.stillPlayable(source(id, "S", Statuses.Assessment.STALE, 24, 30))).isFalse();
	}

	private static ContentSource withCatalog(ContentSource s, Statuses.Catalog catalog) {
		return new ContentSource(s.id(), s.name(), s.normalizedName(), s.sourceType(), s.platform(), s.canonicalUrl(),
				s.platformSourceId(), s.description(), s.tagIds(), s.originTypes(), catalog, s.assessmentStatus(),
				s.latestAssessmentId(), s.minAgeMonths(), s.maxAgeMonths(), s.handle(), s.thumbnailUrl(), s.subscriberCount(),
				s.createdAt(), s.updatedAt(), s.lastVerifiedAt());
	}
}
