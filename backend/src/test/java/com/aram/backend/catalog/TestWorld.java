package com.aram.backend.catalog;

import com.aram.backend.agent.StagedChannelService;
import com.aram.backend.model.Tag;
import com.aram.backend.service.ParentProfileService;
import com.aram.backend.service.RecommendationService;
import com.aram.backend.service.TagCatalogService;
import com.aram.backend.service.TagService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The real services wired over in-memory repositories, with a fixed clock (14 October 2026). */
public final class TestWorld {

	public static final String PARENT = "parent-uid-0000000000000000001";
	public static final String OTHER_PARENT = "parent-uid-0000000000000000002";

	public final Clock clock;
	public final InMemoryStores.Catalog catalogRepo = new InMemoryStores.Catalog();
	public final InMemoryStores.Family family = new InMemoryStores.Family();
	public final InMemoryStores.Submissions submissionRepo = new InMemoryStores.Submissions();
	public final TagCatalogService taxonomy = mock(TagCatalogService.class);
	public final CatalogService catalog;
	public final EligibilityPolicy policy = new EligibilityPolicy();
	public final ChildCatalogService childCatalog;
	public final TagService tags;
	public final ParentProfileService profiles;
	public final StagedChannelService staged;
	public final RecommendationService recommendations;
	private final com.aram.backend.service.ParentChannelService parentChannels;

	public TestWorld() {
		this(Instant.parse("2026-10-14T12:00:00Z"));
	}

	public TestWorld(Instant now) {
		this.clock = Clock.fixed(now, ZoneOffset.UTC);
		when(taxonomy.getTags()).thenReturn(List.of(
				new Tag("math", "Math & Numbers", "calculate", "math-counting", null, 24, 96),
				new Tag("emotions", "Friendship & Emotions", "favorite", "social-emotional", null, 18, 96),
				new Tag("music", "Music & Rhythm", "music_note", "songs-music", null, 0, 96),
				new Tag("nature", "Nature & Wildlife", "pets", "nature-animals", null, 12, 120)));
		when(taxonomy.categoryNameForTag(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
			case "math" -> "Math & Counting";
			case "emotions" -> "Social-Emotional Learning";
			case "music" -> "Songs & Music";
			case "nature" -> "Nature & Animals";
			default -> "Other";
		});
		catalog = new CatalogService(catalogRepo, taxonomy, clock);
		childCatalog = new ChildCatalogService(family, catalogRepo, policy, clock);
		tags = new TagService(taxonomy, catalog);
		profiles = new ParentProfileService(family, tags, childCatalog, clock);
		staged = new StagedChannelService(submissionRepo, catalog, childCatalog, policy, profiles, taxonomy, clock);
		recommendations = new RecommendationService(catalog, childCatalog, profiles, policy);
		parentChannels = new com.aram.backend.service.ParentChannelService(catalog, childCatalog);
	}

	/** Completes onboarding step 1 for a parent whose child was born {@code monthsOld} months before "today". */
	public ParentProfileService.ChildContext onboard(String parentId, int monthsOld) {
		String birth = InMemoryStores.monthsAgo(clock, monthsOld).toString();
		profiles.saveBasics(parentId, "Asha Rao", "asha.rao", "Mira", birth);
		return profiles.requireChild(parentId);
	}

	public com.aram.backend.service.ParentChannelService parentChannels() {
		return parentChannels;
	}

	public ContentSource addSource(ContentSource source) {
		catalogRepo.sources.put(source.id(), source);
		return source;
	}
}
