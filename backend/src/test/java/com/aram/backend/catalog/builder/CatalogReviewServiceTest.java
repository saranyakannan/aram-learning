package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.EligibilityPolicy;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.model.Tag;
import com.aram.backend.service.TagCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aram.backend.catalog.builder.BuilderFixtures.channelId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogReviewServiceTest {

	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

	private final InMemoryStores.Catalog repo = new InMemoryStores.Catalog();
	private CatalogReviewService service;

	@BeforeEach
	void setUp() {
		TagCatalogService taxonomy = mock(TagCatalogService.class);
		when(taxonomy.getTags()).thenReturn(List.of(new Tag("science", "Science", "science"), new Tag("art", "Art", "draw"), new Tag("music", "Music", "music_note")));
		service = new CatalogReviewService(repo, taxonomy, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private ContentSource put(char letter, Statuses.Assessment status) {
		ContentSource base = InMemoryStores.source(channelId(letter), "Channel " + letter, status, 36, 71, "science");
		ContentSource source = new ContentSource(base.id(), base.name(), base.normalizedName(), base.sourceType(), base.platform(), base.canonicalUrl(),
				base.platformSourceId(), base.description(), base.tagIds(), List.of(ContentSource.CATALOG_BUILDER),
				status == Statuses.Assessment.ASSESSED ? Statuses.Catalog.ACTIVE : Statuses.Catalog.UNDER_REVIEW, status, null, 36, 71, base.handle(),
				base.thumbnailUrl(), base.subscriberCount(), base.createdAt(), base.updatedAt(), base.lastVerifiedAt());
		repo.sources.put(source.id(), source);
		return source;
	}

	private static Map<String, String> decision(String sourceId, String decision, String... extra) {
		Map<String, String> row = new LinkedHashMap<>();
		row.put("source_id", sourceId);
		row.put("decision", decision);
		for (int i = 0; i < extra.length; i += 2) {
			row.put(extra[i], extra[i + 1]);
		}
		return row;
	}

	@Test
	void anApprovalAppendsAHumanAssessmentAndMakesTheSourceRecommendableWithTheReviewersEdits() {
		ContentSource source = put('a', Statuses.Assessment.ASSESSED);

		CatalogReviewService.Result result = service.apply(List.of(decision(source.id(), "approve", "final_tags", "science; art", "final_min_age_years", "3",
				"final_max_age_years", "5", "notes", "Checked the channel page")), "reviewer-one");

		assertThat(result).isEqualTo(new CatalogReviewService.Result(1, 0, 0, 0));
		ContentSource after = repo.sources.get(source.id());
		assertThat(after.assessmentStatus()).isEqualTo(Statuses.Assessment.APPROVED_FOR_RECOMMENDATION);
		assertThat(after.catalogStatus()).isEqualTo(Statuses.Catalog.ACTIVE);
		assertThat(after.tagIds()).containsExactly("science", "art");
		assertThat(after.minAgeMonths()).isEqualTo(36);
		assertThat(after.maxAgeMonths()).isEqualTo(71);
		ContentAssessment human = repo.assessments.get(after.latestAssessmentId());
		assertThat(human.assessorType()).isEqualTo(Statuses.Assessor.HUMAN_REVIEW);
		assertThat(human.reviewedBy()).isEqualTo("reviewer-one");
		assertThat(human.reviewedAt()).isEqualTo(NOW);
		assertThat(human.evidenceLevel()).isEqualTo("HUMAN_REVIEW");
		assertThat(human.limitations()).anyMatch(text -> text.contains("not reviewed"));
		assertThat(new EligibilityPolicy().recommendable(after, 50)).isTrue();
	}

	@Test
	void blankTagsAndAgesKeepTheModelsDraftAndAnOlderAssessmentIsNeverEdited() {
		ContentSource source = put('b', Statuses.Assessment.ASSESSED);
		repo.assessments.put("model-1", new ContentAssessment("model-1", ContentAssessment.CONTENT_SOURCE, source.id(), Statuses.Assessor.LLM,
				Statuses.Assessment.ASSESSED, "SAFE", "HIGH", 36, 71, List.of("science"), List.of(), "UNKNOWN", List.of(), "draft", "METADATA_ONLY",
				List.of(), List.of(), "m", "m", "content-policy-v1", 48, NOW.minusSeconds(3600), null, null));

		service.apply(List.of(decision(source.id(), "approve")), "owner");

		assertThat(repo.sources.get(source.id()).tagIds()).containsExactly("science");
		assertThat(repo.sources.get(source.id()).minAgeMonths()).isEqualTo(36);
		assertThat(repo.assessments).as("history is append-only").containsKey("model-1").hasSize(2);
		assertThat(repo.assessments.get("model-1").assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
	}

	@Test
	void aRejectionRetiresTheSourceAndRecordsWhy() {
		ContentSource source = put('c', Statuses.Assessment.ASSESSED);

		CatalogReviewService.Result result = service.apply(List.of(decision(source.id(), "reject", "notes", "Mostly toy unboxing")), "owner");

		assertThat(result.rejected()).isEqualTo(1);
		assertThat(repo.sources.get(source.id()).assessmentStatus()).isEqualTo(Statuses.Assessment.REJECTED);
		assertThat(repo.sources.get(source.id()).catalogStatus()).isEqualTo(Statuses.Catalog.RETIRED);
		assertThat(new EligibilityPolicy().forChild(repo.sources.get(source.id()), 50).eligible()).isFalse();
	}

	@Test
	void blankOrSkipDecisionsChangeNothing() {
		ContentSource source = put('d', Statuses.Assessment.ASSESSED);

		CatalogReviewService.Result result = service.apply(List.of(decision(source.id(), ""), decision(source.id(), "skip")), "owner");

		assertThat(result.skipped()).isEqualTo(2);
		assertThat(repo.sources.get(source.id())).isEqualTo(source);
		assertThat(repo.assessments).isEmpty();
	}

	@Test
	void aSourceTheModelRefusedCanBeRejectedButNeverApproved() {
		ContentSource refused = put('e', Statuses.Assessment.REVIEW_REQUIRED);

		assertThatThrownBy(() -> service.apply(List.of(decision(refused.id(), "approve")), "owner"))
				.isInstanceOf(CatalogReviewService.InvalidReviewException.class).hasMessageContaining("did not clear");
		assertThat(repo.sources.get(refused.id()).assessmentStatus()).isEqualTo(Statuses.Assessment.REVIEW_REQUIRED);

		assertThat(service.apply(List.of(decision(refused.id(), "reject")), "owner").rejected()).isEqualTo(1);
	}

	@Test
	void oneBadRowAbortsTheWholeSheetAndNothingIsWritten() {
		ContentSource good = put('f', Statuses.Assessment.ASSESSED);
		ContentSource other = put('g', Statuses.Assessment.ASSESSED);
		ContentSource third = put('k', Statuses.Assessment.ASSESSED);
		List<Map<String, String>> sheet = List.of(
				decision(good.id(), "approve"),
				decision(other.id(), "approve", "final_tags", "science;not-a-tag"),
				decision(third.id(), "approve", "final_min_age_years", "6", "final_max_age_years", "4"),
				decision(other.id(), "maybe"),
				decision("youtube_UCdoesNotExist0000000", "approve"));

		assertThatThrownBy(() -> service.apply(sheet, "owner")).isInstanceOf(CatalogReviewService.InvalidReviewException.class)
				.satisfies(e -> assertThat(((CatalogReviewService.InvalidReviewException) e).problems()).hasSizeGreaterThanOrEqualTo(4)
						.anyMatch(p -> p.contains("unknown tag 'not-a-tag'")).anyMatch(p -> p.contains("min <= max"))
						.anyMatch(p -> p.contains("approve, reject or skip")).anyMatch(p -> p.contains("no such source")));
		assertThat(repo.assessments).isEmpty();
		assertThat(repo.sources.get(good.id()).assessmentStatus()).isEqualTo(Statuses.Assessment.ASSESSED);
	}

	@Test
	void agesMustBeWholeYearsInRangeAndDecidingASourceTwiceIsAnError() {
		ContentSource source = put('h', Statuses.Assessment.ASSESSED);

		for (Map<String, String> bad : List.of(decision(source.id(), "approve", "final_min_age_years", "x", "final_max_age_years", "5"),
				decision(source.id(), "approve", "final_min_age_years", "3"),
				decision(source.id(), "approve", "final_min_age_years", "-1", "final_max_age_years", "5"),
				decision(source.id(), "approve", "final_min_age_years", "3", "final_max_age_years", "19"))) {
			assertThatThrownBy(() -> service.apply(List.of(bad), "owner")).as(bad.toString()).isInstanceOf(CatalogReviewService.InvalidReviewException.class);
		}
		assertThatThrownBy(() -> service.apply(List.of(decision(source.id(), "approve"), decision(source.id(), "reject")), "owner"))
				.hasMessageContaining("more than once");
		assertThat(repo.assessments).isEmpty();
	}

	@Test
	void applyingTheSameSheetTwiceChangesNothingTheSecondTime() {
		ContentSource a = put('i', Statuses.Assessment.ASSESSED);
		ContentSource b = put('j', Statuses.Assessment.ASSESSED);
		List<Map<String, String>> sheet = List.of(decision(a.id(), "approve"), decision(b.id(), "reject"));
		service.apply(sheet, "owner");
		int assessmentsAfterFirst = repo.assessments.size();

		CatalogReviewService.Result second = service.apply(sheet, "owner");

		assertThat(second).isEqualTo(new CatalogReviewService.Result(0, 0, 0, 2));
		assertThat(repo.assessments).hasSize(assessmentsAfterFirst);
	}
}
