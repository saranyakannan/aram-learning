package com.aram.backend.service;

import com.aram.backend.catalog.BookSignal;
import com.aram.backend.catalog.BookSignalRepository;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.InMemoryStores;
import com.aram.backend.catalog.SourceSignal;
import com.aram.backend.catalog.Statuses;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.service.BookRecommendationService.BookPick;
import com.aram.backend.service.CuratedBookCatalog.Book;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookRecommendationServiceTest {

	private static final String URL = "https://www.amazon.in/dp/0241003008";

	private static Book book(String id, int min, int max, String... tags) {
		return new Book(id, "Book " + id, "Author", min, max, List.of(tags), "Description.", "9780241003008", URL);
	}

	private static List<String> ids(List<BookPick> picks) {
		return picks.stream().map(BookPick::id).toList();
	}

	@Test
	void onlyBooksThatFitTheChildsAgeAreEverOffered() {
		List<Book> books = List.of(book("baby", 0, 12, "nature"), book("toddler", 12, 36, "nature"), book("older", 48, 96, "nature"));

		assertThat(ids(BookRecommendationService.pick(books, 0, List.of("nature")))).containsExactly("baby");
		assertThat(ids(BookRecommendationService.pick(books, 12, List.of("nature")))).containsExactlyInAnyOrder("baby", "toddler");
		assertThat(ids(BookRecommendationService.pick(books, 36, List.of("nature")))).containsExactly("toddler");
		assertThat(BookRecommendationService.pick(books, 40, List.of("nature"))).as("nothing for this age").isEmpty();
	}

	@Test
	void moreMatchedInterestsRankFirstAndTheMatchesAreReported() {
		List<Book> books = List.of(book("one", 0, 60, "math"), book("two", 0, 60, "math", "nature"), book("none", 0, 60, "space"));

		List<BookPick> picks = BookRecommendationService.pick(books, 24, List.of("nature", "math", "bedtime-stories"));

		assertThat(picks.get(0).id()).isEqualTo("two");
		assertThat(picks.get(0).matchedTags()).containsExactlyInAnyOrder("math", "nature");
		assertThat(picks.get(1).matchedTags()).containsExactly("math");
	}

	@Test
	void whenFewBooksMatchOtherAgeFitBooksTopUpTheListWithoutMatches() {
		List<Book> books = List.of(book("match", 0, 60, "math"), book("b", 0, 60, "space"), book("c", 0, 60, "art"),
				book("late", 80, 96, "math"));

		List<BookPick> picks = BookRecommendationService.pick(books, 24, List.of("math"));

		assertThat(ids(picks)).containsExactly("match", "b", "c");
		assertThat(picks.get(0).matchedTags()).containsExactly("math");
		assertThat(picks.get(1).matchedTags()).as("a top-up claims no match").isEmpty();
	}

	@Test
	void noInterestsStillGivesAgeFitBooksAndTheListIsCapped() {
		List<Book> books = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			books.add(book("b" + (char) ('a' + i), 0, 60, "math"));
		}

		assertThat(BookRecommendationService.pick(books, 24, List.of())).hasSize(BookRecommendationService.MAX_BOOKS);
		assertThat(BookRecommendationService.pick(books, 24, null)).hasSize(BookRecommendationService.MAX_BOOKS);
		assertThat(BookRecommendationService.pick(books, 24, List.of("math"))).hasSize(BookRecommendationService.MAX_BOOKS);
	}

	@Test
	void theCoverComesFromTheIsbnAndTheStoreFromTheLink() {
		BookPick pick = BookRecommendationService.pick(List.of(book("a", 0, 60, "math")), 12, List.of("math")).get(0);

		assertThat(pick.coverUrl()).isEqualTo("https://covers.openlibrary.org/b/isbn/9780241003008-M.jpg?default=false");
		assertThat(pick.store()).isEqualTo("Amazon");
		assertThat(pick.buyUrl()).isEqualTo(URL);
	}

	@Test
	void aParentWithNoChildYetGetsNoBooks() {
		ParentProfileService profiles = mock(ParentProfileService.class);
		CuratedBookCatalog catalog = mock(CuratedBookCatalog.class);
		when(catalog.getBooks()).thenReturn(List.of(book("a", 0, 60, "math")));
		ParentProfile noChild = new ParentProfile();
		when(profiles.getProfile("parent-1")).thenReturn(noChild);

		assertThat(new BookRecommendationService(catalog, profiles).forParent("parent-1")).isEmpty();
	}

	@Test
	void theParentsOwnProfileDrivesTheAgeAndInterests() {
		ParentProfileService profiles = mock(ParentProfileService.class);
		CuratedBookCatalog catalog = mock(CuratedBookCatalog.class);
		when(catalog.getBooks()).thenReturn(List.of(book("fits", 0, 60, "math"), book("tooOld", 70, 96, "math")));
		ParentProfile profile = new ParentProfile();
		profile.setChildAgeMonths(30);
		profile.setTags(List.of("math"));
		when(profiles.getProfile("parent-1")).thenReturn(profile);

		assertThat(ids(new BookRecommendationService(catalog, profiles).forParent("parent-1"))).containsExactly("fits");
	}

	// ---- engine-assisted ranking (decision record 0017) ----

	private static final String PARENT = "parent-1";
	private static final ContentSource NUMBERBLOCKS = InMemoryStores.source(InMemoryStores.youtubeId('a'), "Numberblocks",
			Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 36, 83, "math");
	private static final ContentSource SONGS = InMemoryStores.source(InMemoryStores.youtubeId('b'), "Songs for Kids",
			Statuses.Assessment.APPROVED_FOR_RECOMMENDATION, 0, 83, "music");

	private ParentProfileService profiles;
	private CuratedBookCatalog catalog;
	private ChildCatalogService childCatalog;
	private BookSignalRepository signals;
	private AramProperties.Ml ml;

	private BookRecommendationService engine(List<Book> books, List<ContentSource> approved, Map<String, BookSignal> signalsById,
			List<String> interests, boolean blend) {
		profiles = mock(ParentProfileService.class);
		catalog = mock(CuratedBookCatalog.class);
		childCatalog = mock(ChildCatalogService.class);
		signals = mock(BookSignalRepository.class);
		ml = new AramProperties.Ml();
		ml.setBlendEnabled(blend);
		when(catalog.getBooks()).thenReturn(books);
		ParentProfile profile = new ParentProfile();
		profile.setChildAgeMonths(40);
		profile.setTags(interests);
		when(profiles.getProfile(PARENT)).thenReturn(profile);
		when(profiles.findChild(PARENT)).thenReturn(Optional.of(new ParentProfileService.ChildContext(PARENT, "child-1", "Ananya", 40)));
		when(childCatalog.approvedSources("child-1")).thenReturn(approved);
		when(signals.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(signalsById);
		return new BookRecommendationService(catalog, profiles, childCatalog, signals, ml);
	}

	private static BookSignal signal(String book, String source, double similarity) {
		return new BookSignal(book, List.of(new SourceSignal.Neighbor(source, similarity)), "text-embedding-005", Instant.EPOCH);
	}

	@Test
	void aBookSimilarToSomethingTheChildHasOutranksATagOnlyMatchAndSaysWhy() {
		List<Book> books = List.of(book("by-tag", 0, 60, "math"), book("by-engine", 0, 60, "space"), book("neither", 0, 60, "art"));
		var service = engine(books, List.of(NUMBERBLOCKS), Map.of("by-engine", signal("by-engine", NUMBERBLOCKS.id(), 0.9)),
				List.of("math"), true);

		List<BookPick> picks = service.forParent(PARENT);

		assertThat(ids(picks).subList(0, 2)).containsExactly("by-engine", "by-tag");
		assertThat(picks.get(0).reason()).isEqualTo("Similar to Numberblocks");
		assertThat(picks.get(0).matchedTags()).isEmpty();
		assertThat(picks.get(1).reason()).as("tag-only match has no engine reason").isNull();
	}

	@Test
	void theBestSimilarityCountsAndOnlyWhatTheChildHasAndOnlyAboveTheThreshold() {
		List<Book> books = List.of(book("b1", 0, 60, "space"), book("b2", 0, 60, "space"), book("b3", 0, 60, "space"));
		BookSignal b1 = new BookSignal("b1", List.of(new SourceSignal.Neighbor(NUMBERBLOCKS.id(), 0.75), new SourceSignal.Neighbor(SONGS.id(), 0.95)),
				"m", Instant.EPOCH);
		BookSignal b2 = signal("b2", "youtube_UCnotMine000000000000000", 0.99); // similar, but the child does not have it
		BookSignal b3 = signal("b3", NUMBERBLOCKS.id(), 0.55); // below the minimum similarity for books
		var service = engine(books, List.of(NUMBERBLOCKS, SONGS), Map.of("b1", b1, "b2", b2, "b3", b3), List.of(), true);

		List<BookPick> picks = service.forParent(PARENT);

		assertThat(picks.get(0).id()).isEqualTo("b1");
		assertThat(picks.get(0).reason()).isEqualTo("Similar to Songs for Kids");
		assertThat(picks.stream().filter(p -> p.reason() != null).map(BookPick::id)).containsExactly("b1");
	}

	@Test
	void booksUseTheirOwnLowerSimilarityMinimumBecauseTheyAreComparedWithChannelText() {
		// 0.66 would be rejected by the channel threshold (0.70) but is a good book match
		var service = engine(List.of(book("b", 0, 60, "space")), List.of(NUMBERBLOCKS), Map.of("b", signal("b", NUMBERBLOCKS.id(), 0.66)), List.of(), true);

		assertThat(service.forParent(PARENT).get(0).reason()).isEqualTo("Similar to Numberblocks");
		assertThat(new AramProperties.Ml().getBookMinSimilarity()).isLessThan(new AramProperties.Ml().getMinSimilarity());
	}

	@Test
	void theAgeRangeStaysAHardFilterWhateverTheEngineSays() {
		List<Book> books = List.of(book("too-old", 70, 96, "math"), book("fits", 24, 60, "space"));
		var service = engine(books, List.of(NUMBERBLOCKS), Map.of("too-old", signal("too-old", NUMBERBLOCKS.id(), 0.99),
				"fits", signal("fits", NUMBERBLOCKS.id(), 0.80)), List.of("math"), true);

		assertThat(ids(service.forParent(PARENT))).containsExactly("fits");
	}

	@Test
	void withTheFlagOffOrNothingApprovedOrNoSimilarBookTheRuleBasedListIsUsed() {
		List<Book> books = List.of(book("a", 0, 60, "math"), book("b", 0, 60, "space"));
		Map<String, BookSignal> sig = Map.of("b", signal("b", NUMBERBLOCKS.id(), 0.9));

		var off = engine(books, List.of(NUMBERBLOCKS), sig, List.of("math"), false);
		assertThat(ids(off.forParent(PARENT))).as("flag off").containsExactly("a", "b");
		assertThat(off.forParent(PARENT)).allSatisfy(pick -> assertThat(pick.reason()).isNull());

		var none = engine(books, List.of(), sig, List.of("math"), true);
		assertThat(ids(none.forParent(PARENT))).as("nothing approved yet").containsExactly("a", "b");

		var noSimilar = engine(books, List.of(NUMBERBLOCKS), Map.of(), List.of("math"), true);
		assertThat(ids(noSimilar.forParent(PARENT))).as("no signals").containsExactly("a", "b");
	}

	@Test
	void aFailingSignalReadNeverBreaksTheBookList() {
		var service = engine(List.of(book("a", 0, 60, "math")), List.of(NUMBERBLOCKS), Map.of(), List.of("math"), true);
		when(signals.findByIds(org.mockito.ArgumentMatchers.anyCollection())).thenThrow(new IllegalStateException("firestore down"));

		assertThat(ids(service.forParent(PARENT))).containsExactly("a");
	}
}
