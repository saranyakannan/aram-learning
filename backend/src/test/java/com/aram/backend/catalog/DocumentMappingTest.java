package com.aram.backend.catalog;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The stored shape is the data-model spec: these tests pin the field names and that every record survives a round trip. */
class DocumentMappingTest {

	private static final Instant T = Instant.parse("2026-10-14T12:00:00Z");

	@Test
	void aContentSourceKeepsTheSpecFieldsAndRoundTrips() {
		ContentSource source = new ContentSource("youtube_UCaaaaaaaaaaaaaaaaaaaaaa", "Super Simple Songs", "super simple songs",
				ContentSource.YOUTUBE_CHANNEL, ContentSource.YOUTUBE, "https://www.youtube.com/@SuperSimpleSongs",
				"UCaaaaaaaaaaaaaaaaaaaaaa", "Songs", List.of("music"), List.of(ContentSource.PREPOPULATED),
				Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, "a1", 0, 59, "@SuperSimpleSongs",
				"https://t/x.jpg", 42L, T, T, T);

		Map<String, Object> map = source.toMap();

		assertThat(map).containsKeys("name", "normalizedName", "sourceType", "platform", "canonicalUrl", "platformSourceId",
				"description", "tagIds", "originTypes", "catalogStatus", "assessmentStatus", "createdAt", "updatedAt",
				"lastVerifiedAt");
		assertThat(map.get("createdAt")).isInstanceOf(Timestamp.class);
		assertThat(map.get("catalogStatus")).isEqualTo("ACTIVE");
		assertThat(ContentSource.fromMap(source.id(), map)).isEqualTo(source);
	}

	@Test
	void anAssessmentStoresAgeInMonthsEvidenceAndVersionsAndLeavesConfidenceNull() {
		ContentAssessment assessment = new ContentAssessment("a1", ContentAssessment.CONTENT_SOURCE, "youtube_UCx",
				Statuses.Assessor.LLM, Statuses.Assessment.ASSESSED, "SAFE", "HIGH", 24, 59, List.of("math"), List.of(),
				"UNKNOWN", List.of("none"), "Calm counting.", ContentAssessment.METADATA_ONLY, List.of(),
				List.of("No video review"), "gemini-3.8-flash", "gemini-3.8-flash", ContentAssessment.POLICY_VERSION, 48, T,
				null, null);

		Map<String, Object> map = assessment.toMap();

		assertThat(map).containsKeys("targetType", "targetId", "assessorType", "assessmentStatus", "minAgeMonths",
				"maxAgeMonths", "suggestedTagIds", "learningGoals", "presentationStyle", "safetyFindings", "evidenceLevel",
				"evidenceReferences", "confidence", "limitations", "modelName", "modelVersion", "policyVersion", "assessedAt",
				"reviewedBy", "reviewedAt");
		assertThat(map.get("confidence")).isNull();
		assertThat(map.get("policyVersion")).isEqualTo("content-policy-v1");
		assertThat(ContentAssessment.fromMap("a1", map)).isEqualTo(assessment);
	}

	@Test
	void submissionsEntriesParentsAndChildrenRoundTrip() {
		ContentSubmission submission = new ContentSubmission("c1_youtube_UCx", "p1", "c1", "@Bluey", "https://youtube.com/@Bluey",
				"YOUTUBE_CHANNEL", Statuses.Submission.READY_FOR_PARENT, "youtube_UCx", null, "a1", null, T, T, T.plusSeconds(60));
		ChildCatalogEntry entry = new ChildCatalogEntry("c1_youtube_UCx", "p1", "c1", "youtube_UCx", null,
				Statuses.Entry.APPROVED, "p1", T, List.of(), T, T);
		ParentAccount parent = new ParentAccount("p1", "Asha", "asha.rao", "en", "ACTIVE", false, T, T);
		ChildProfile child = new ChildProfile("p1-c1", "p1", "Mira", "2022-03", "en", "ACTIVE", T, T);

		assertThat(ContentSubmission.fromMap(submission.id(), submission.toMap())).isEqualTo(submission);
		assertThat(ChildCatalogEntry.fromMap(entry.id(), entry.toMap())).isEqualTo(entry);
		assertThat(ParentAccount.fromMap("p1", parent.toMap())).isEqualTo(parent);
		assertThat(ChildProfile.fromMap("p1-c1", child.toMap())).isEqualTo(child);
		assertThat(entry.toMap()).containsKeys("parentId", "childId", "sourceId", "contentItemId", "status", "approvedBy",
				"approvedAt", "restrictions");
		assertThat(child.toMap()).doesNotContainKey("age");
	}

	@Test
	void aPodcastSourceKeepsItsFeedAddressAndRoundTrips() {
		ContentSource show = new ContentSource("podcast_1233834541", "Wow in the World", "wow in the world",
				ContentSource.PODCAST_FEED, ContentSource.APPLE_PODCASTS, "https://podcasts.apple.com/podcast/id1233834541",
				"1233834541", "Science", List.of("science", "podcasts"), List.of(ContentSource.PREPOPULATED),
				Statuses.Catalog.ACTIVE, Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, "a1", 60, 131, "Tinkercast",
				"https://t/x.jpg", null, T, T, T, "https://rss.art19.com/wow-in-the-world");

		Map<String, Object> map = show.toMap();

		assertThat(map.get("sourceType")).isEqualTo("PODCAST_FEED");
		assertThat(map.get("feedUrl")).isEqualTo("https://rss.art19.com/wow-in-the-world");
		assertThat(ContentSource.fromMap(show.id(), map)).isEqualTo(show);
		assertThat(show.isPodcast()).isTrue();
	}

	@Test
	void podcastIdsAreKeyedByTheDirectoryIdAndAreValidated() {
		assertThat(ContentSource.idForPodcast(1233834541L)).isEqualTo("podcast_1233834541");
		assertThat(ContentSource.isValidId("podcast_1233834541")).isTrue();
		assertThat(ContentSource.isValidId("podcast_")).isFalse();
		assertThat(ContentSource.isValidId("podcast_12a")).isFalse();
		assertThat(ContentSource.isValidId("podcast_1/../x")).isFalse();
	}

	@Test
	void sourceIdsAreOneToOneWithPlatformChannelIds() {
		assertThat(ContentSource.idForYoutubeChannel("UCEC-8nMiuXYH2QUs0yYcHTQ")).isEqualTo("youtube_UCEC-8nMiuXYH2QUs0yYcHTQ");
		assertThat(ContentSource.isValidId("youtube_UCEC-8nMiuXYH2QUs0yYcHTQ")).isTrue();
		assertThat(ContentSource.isValidId("youtube_../x")).isFalse();
		assertThat(ContentSource.isValidId("numberblocks")).isFalse();
		assertThat(ContentSource.isValidId(null)).isFalse();
	}
}
