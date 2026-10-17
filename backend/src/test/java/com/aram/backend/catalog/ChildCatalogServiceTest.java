package com.aram.backend.catalog;

import com.aram.backend.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChildCatalogServiceTest {

	private static final int AGE = 48;

	private TestWorld world;
	private String childId;
	private ContentSource fits;
	private ContentSource tooOld;
	private ContentSource unreviewed;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
		childId = world.onboard(TestWorld.PARENT, AGE).childId();
		fits = world.addSource(source(youtubeId('a'), "Fits", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		tooOld = world.addSource(source(youtubeId('b'), "Too old", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 96, 143, "math"));
		unreviewed = world.addSource(source(youtubeId('c'), "Unreviewed", Statuses.Assessment.REVIEW_REQUIRED, 24, 71, "math"));
	}

	@Test
	void approvingCreatesAnApprovedEntryRecordingWhoApprovedIt() {
		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(fits.id()));

		ChildCatalogEntry entry = world.family.entries.get(childId + "_" + fits.id());
		assertThat(entry.status()).isEqualTo(Statuses.Entry.APPROVED);
		assertThat(entry.approvedBy()).isEqualTo(TestWorld.PARENT);
		assertThat(entry.approvedAt()).isEqualTo(world.clock.instant());
		assertThat(entry.contentItemId()).isNull();
		assertThat(world.childCatalog.isApproved(childId, fits.id())).isTrue();
	}

	@Test
	void theSharedPolicyDecidesAndRefusalsCarryAReasonForTheParent() {
		assertThatThrownBy(() -> world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(tooOld.id())))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("different age");
		assertThatThrownBy(() -> world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(unreviewed.id())))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("isn't clearly safe");
		assertThatThrownBy(() -> world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of("youtube_UCnotInTheCatalog00000")))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unknown channel");
		assertThat(world.family.entries).isEmpty();
	}

	@Test
	void removingAChannelRevokesTheEntryInsteadOfDeletingIt() {
		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(fits.id()));

		world.childCatalog.revoke(childId, fits.id());

		assertThat(world.family.entries.get(childId + "_" + fits.id()).status()).isEqualTo(Statuses.Entry.REVOKED);
		assertThat(world.childCatalog.isApproved(childId, fits.id())).isFalse();
		assertThat(world.childCatalog.approvedSourceIds(childId)).isEmpty();
	}

	@Test
	void approvingAgainAfterARevokeReusesTheEntryAndKeepsWhenItWasFirstCreated() {
		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(fits.id()));
		var created = world.family.entries.get(childId + "_" + fits.id()).createdAt();
		world.childCatalog.revoke(childId, fits.id());

		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(fits.id()));

		assertThat(world.family.entries).hasSize(1);
		assertThat(world.family.entries.get(childId + "_" + fits.id()).status()).isEqualTo(Statuses.Entry.APPROVED);
		assertThat(world.family.entries.get(childId + "_" + fits.id()).createdAt()).isEqualTo(created);
	}

	@Test
	void replacingTheApprovedSetRevokesWhatIsMissingAndRefusesBeforeChangingAnything() {
		ContentSource second = world.addSource(source(youtubeId('d'), "Second", Statuses.Assessment.ASSESSED, 24, 71, "math"));
		world.childCatalog.setApproved(TestWorld.PARENT, childId, AGE, List.of(fits.id(), second.id()));
		assertThat(world.childCatalog.approvedSourceIds(childId)).containsExactly(fits.id(), second.id());

		world.childCatalog.setApproved(TestWorld.PARENT, childId, AGE, List.of(second.id()));
		assertThat(world.childCatalog.approvedSourceIds(childId)).containsExactly(second.id());

		assertThatThrownBy(() -> world.childCatalog.setApproved(TestWorld.PARENT, childId, AGE, List.of(tooOld.id())))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(world.childCatalog.approvedSourceIds(childId)).containsExactly(second.id());
	}

	@Test
	void aSourceTheCatalogNoLongerClearsStopsBeingPlayableEvenThoughTheEntryRemains() {
		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(fits.id()));
		world.catalogRepo.sources.put(fits.id(), new ContentSource(fits.id(), fits.name(), fits.normalizedName(),
				fits.sourceType(), fits.platform(), fits.canonicalUrl(), fits.platformSourceId(), fits.description(),
				fits.tagIds(), fits.originTypes(), Statuses.Catalog.ACTIVE, Statuses.Assessment.REJECTED,
				fits.latestAssessmentId(), fits.minAgeMonths(), fits.maxAgeMonths(), fits.handle(), fits.thumbnailUrl(),
				fits.subscriberCount(), fits.createdAt(), fits.updatedAt(), fits.lastVerifiedAt()));

		assertThat(world.childCatalog.approvedSources(childId)).isEmpty();
	}

	@Test
	void approvedSourcesComeBackInApprovalOrder() {
		ContentSource second = world.addSource(source(youtubeId('e'), "Second", Statuses.Assessment.ASSESSED, 24, 71, "math"));
		world.childCatalog.approve(TestWorld.PARENT, childId, AGE, List.of(second.id()));
		var later = new TestWorld(java.time.Instant.parse("2026-10-15T12:00:00Z"));
		// A different clock for the second approval is simulated by writing the entry directly.
		world.family.saveEntry(new ChildCatalogEntry(childId + "_" + fits.id(), TestWorld.PARENT, childId, fits.id(), null,
				Statuses.Entry.APPROVED, TestWorld.PARENT, later.clock.instant(), List.of(), later.clock.instant(),
				later.clock.instant()));

		assertThat(world.childCatalog.approvedSourceIds(childId)).containsExactly(second.id(), fits.id());
	}
}
