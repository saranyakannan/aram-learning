package com.aram.backend.service;

import com.aram.backend.catalog.ChildProfile;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.catalog.TestWorld;
import com.aram.backend.exception.InvalidRequestException;
import com.aram.backend.model.ParentProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.aram.backend.catalog.InMemoryStores.source;
import static com.aram.backend.catalog.InMemoryStores.youtubeId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParentProfileServiceTest {

	private static final String PARENT = TestWorld.PARENT;

	private TestWorld world;

	@BeforeEach
	void setUp() {
		world = new TestWorld();
	}

	@Test
	void anUnknownParentGetsAnEmptyProfileWithNothingApproved() {
		ParentProfile profile = world.profiles.getProfile(PARENT);

		assertThat(profile.getParentName()).isNull();
		assertThat(profile.getTags()).isEmpty();
		assertThat(profile.getChannelIds()).isEmpty();
		assertThat(profile.getChildAge()).isNull();
	}

	@Test
	void basicsCreateTheParentAndChildDocumentsInTheirOwnCollectionsAndComputeTheAge() {
		ParentProfile profile = world.profiles.saveBasics(PARENT, " Asha Rao ", "asha.rao", "Mira", "2022-03");

		assertThat(world.family.parents.get(PARENT).displayName()).isEqualTo("Asha Rao");
		assertThat(world.family.parents.get(PARENT).accountStatus()).isEqualTo("ACTIVE");
		assertThat(world.family.parents.get(PARENT).socialDiscoveryEnabled()).isFalse();
		ChildProfile child = world.family.children.get(PARENT + "-c1");
		assertThat(child.parentId()).isEqualTo(PARENT);
		assertThat(child.birthYearMonth()).isEqualTo("2022-03");
		assertThat(profile.getChildName()).isEqualTo("Mira");
		assertThat(profile.getChildAgeMonths()).isEqualTo(55);
		assertThat(profile.getChildAge()).isEqualTo(4);
		assertThat(profile.getBirthYearMonth()).isEqualTo("2022-03");
	}

	@Test
	void savingBasicsAgainKeepsWhenTheAccountWasCreatedAndTheChildsInterests() {
		world.profiles.saveBasics(PARENT, "Asha", "asha.rao", "Mira", "2022-03");
		world.profiles.saveTags(PARENT, List.of("math"));
		var created = world.family.parents.get(PARENT).createdAt();

		world.profiles.saveBasics(PARENT, "Asha R", "asha.rao", "Mira", "2022-04");

		assertThat(world.family.parents.get(PARENT).createdAt()).isEqualTo(created);
		assertThat(world.profiles.getProfile(PARENT).getTags()).containsExactly("math");
		assertThat(world.profiles.getProfile(PARENT).getBirthYearMonth()).isEqualTo("2022-04");
	}

	@Test
	void basicsAreValidated() {
		assertThatThrownBy(() -> world.profiles.saveBasics(PARENT, "", "asha.rao", "Mira", "2022-03"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.profiles.saveBasics(PARENT, "Asha", "Bad Handle!", "Mira", "2022-03"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.profiles.saveBasics(PARENT, "Asha", "asha.rao", " ", "2022-03"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.profiles.saveBasics(PARENT, "Asha", "asha.rao", "Mira", "2030-01"))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> world.profiles.saveBasics("../other/doc", "Asha", "asha.rao", "Mira", "2022-03"))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(world.family.parents).isEmpty();
		assertThat(world.family.children).isEmpty();
	}

	@Test
	void pickingTagsStoresInterestsAndNeverApprovesAnyChannel() {
		world.onboard(PARENT, 55);
		world.addSource(source(youtubeId('a'), "Counting show", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));

		ParentProfile saved = world.profiles.saveTags(PARENT, List.of("math", "music"));

		assertThat(saved.getTags()).containsExactlyInAnyOrder("math", "music");
		assertThat(world.family.interests.get(PARENT + "-c1")).containsExactly("math", "music");
		assertThat(saved.getChannelIds()).isEmpty();
		assertThat(world.family.entries).isEmpty();
	}

	@Test
	void tagsAreRefusedBeforeTheChildExistsAndUnknownTagsAreRejected() {
		assertThatThrownBy(() -> world.profiles.saveTags(PARENT, List.of("math")))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("finish setting up");
		world.onboard(PARENT, 55);
		assertThatThrownBy(() -> world.profiles.saveTags(PARENT, List.of("not-a-tag")))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining("Unknown tag");
	}

	@Test
	void savingChannelsApprovesThroughTheSharedPolicyAndTheProfileListsOnlyApprovedIds() {
		world.onboard(PARENT, 55);
		ContentSource ok = world.addSource(source(youtubeId('a'), "Ok", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));
		ContentSource old = world.addSource(source(youtubeId('b'), "Old", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 96, 143, "math"));

		assertThat(world.profiles.saveChannels(PARENT, List.of(ok.id())).getChannelIds()).containsExactly(ok.id());
		assertThatThrownBy(() -> world.profiles.saveChannels(PARENT, List.of(ok.id(), old.id())))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(world.profiles.getProfile(PARENT).getChannelIds()).containsExactly(ok.id());
		assertThat(world.profiles.saveChannels(PARENT, List.of()).getChannelIds()).isEmpty();
		assertThatThrownBy(() -> world.profiles.saveChannels(PARENT, null)).isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void oneParentsApprovalsNeverAppearInAnotherParentsProfile() {
		world.onboard(PARENT, 55);
		world.onboard(TestWorld.OTHER_PARENT, 55);
		ContentSource ok = world.addSource(source(youtubeId('a'), "Ok", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 24, 71, "math"));

		world.profiles.saveChannels(PARENT, List.of(ok.id()));

		assertThat(world.profiles.getProfile(TestWorld.OTHER_PARENT).getChannelIds()).isEmpty();
	}

	@Test
	void theTaxonomyListsEveryTagWithHowManyRecommendableChannelsCarryIt() {
		world.addSource(source(youtubeId('a'), "A", Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 0, 99, "math", "music"));
		world.addSource(source(youtubeId('b'), "B", Statuses.Assessment.ASSESSED, 0, 99, "math"));

		var counts = world.tags.getTags().stream().collect(java.util.stream.Collectors.toMap(t -> t.id(), t -> t.channelCount()));

		assertThat(counts).containsEntry("math", 1).containsEntry("music", 1).containsEntry("emotions", 0);
		assertThat(world.tags.getTags()).extracting(t -> t.id()).containsExactly("math", "emotions", "music", "nature");
	}
}
