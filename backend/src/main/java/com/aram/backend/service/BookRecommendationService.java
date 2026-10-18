package com.aram.backend.service;

import com.aram.backend.catalog.BookSignal;
import com.aram.backend.catalog.BookSignalRepository;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.service.CuratedBookCatalog.Book;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Picks books for the parent from the reviewed list: only books whose age range includes the child's age, ranked by how
 * many of the parent's chosen interests they match, topped up with other age-fit books when there are too few matches.
 * It reads the child's age and the interests the parent picked; nothing is stored and nothing leaves the server.
 *
 * <p>With {@code aram.ml.blend-enabled} the recommendation engine adds its precomputed {@code book_signals} (decision record
 * 0017): a book that is similar to a channel or podcast the child already has scores higher and says "Similar to ...". The
 * age range stays a hard filter, and any problem with the signals falls back to the rule-based list above.
 */
@Service
public class BookRecommendationService {

	static final int MAX_BOOKS = 8;
	static final int MIN_MATCHED_BEFORE_TOP_UP = 4;

	/** One suggestion: the book, the interests it matched (empty for a top-up), and the store name for the button. */
	public record BookPick(String id, String title, String author, int minAgeMonths, int maxAgeMonths, String description,
			String coverUrl, String buyUrl, String store, List<String> matchedTags, String reason) {
	}

	private static final Logger log = LoggerFactory.getLogger(BookRecommendationService.class);

	private final CuratedBookCatalog catalog;
	private final ParentProfileService profileService;
	private final ChildCatalogService childCatalog;
	private final BookSignalRepository signals;
	private final AramProperties.Ml settings;

	/** Rule-based picks only (no engine signals). */
	public BookRecommendationService(CuratedBookCatalog catalog, ParentProfileService profileService) {
		this(catalog, profileService, null, null, new AramProperties.Ml());
	}

	@Autowired
	public BookRecommendationService(CuratedBookCatalog catalog, ParentProfileService profileService, ChildCatalogService childCatalog,
			BookSignalRepository signals, AramProperties properties) {
		this(catalog, profileService, childCatalog, signals, properties.getMl());
	}

	BookRecommendationService(CuratedBookCatalog catalog, ParentProfileService profileService, ChildCatalogService childCatalog,
			BookSignalRepository signals, AramProperties.Ml settings) {
		this.catalog = catalog;
		this.profileService = profileService;
		this.childCatalog = childCatalog;
		this.signals = signals;
		this.settings = settings;
	}

	public List<BookPick> forParent(String parentId) {
		ParentProfile profile = profileService.getProfile(parentId);
		if (profile.getChildAgeMonths() == null) {
			return List.of();
		}
		List<BookPick> ruleBased = pick(catalog.getBooks(), profile.getChildAgeMonths(), profile.getTags());
		if (!settings.isBlendEnabled() || signals == null || childCatalog == null) {
			return ruleBased;
		}
		try {
			return profileService.findChild(parentId)
					.flatMap(child -> blended(child.childId(), profile.getChildAgeMonths(), profile.getTags()))
					.orElse(ruleBased);
		} catch (RuntimeException e) {
			log.warn("Book signals unavailable, using the rule-based picks: {}", e.getMessage());
			return ruleBased;
		}
	}

	/** Best similarity of a book to something the child already has, and what that something is called. */
	private record Similarity(double score, String to) {
	}

	/**
	 * Engine-assisted picks, or empty when the engine adds nothing (the child has nothing yet, or no book is similar to what
	 * the child has), so the caller keeps the rule-based list.
	 */
	private Optional<List<BookPick>> blended(String childId, int ageMonths, List<String> interests) {
		Map<String, String> approved = new HashMap<>();
		for (ContentSource source : childCatalog.approvedSources(childId)) {
			approved.put(source.id(), source.name());
		}
		if (approved.isEmpty()) {
			return Optional.empty();
		}
		List<Book> fits = catalog.getBooks().stream().filter(book -> book.fitsAge(ageMonths)).toList();
		Map<String, BookSignal> bySignal = signals.findByIds(fits.stream().map(Book::id).toList());
		Map<String, Similarity> similar = new HashMap<>();
		for (Book book : fits) {
			BookSignal signal = bySignal.get(book.id());
			if (signal == null) {
				continue;
			}
			signal.neighbors().stream()
					.filter(neighbor -> approved.containsKey(neighbor.sourceId()) && neighbor.similarity() >= settings.getBookMinSimilarity())
					.max(Comparator.comparingDouble(neighbor -> neighbor.similarity()))
					.ifPresent(best -> similar.put(book.id(), new Similarity(best.similarity(), approved.get(best.sourceId()))));
		}
		if (similar.isEmpty()) {
			return Optional.empty();
		}
		Set<String> wanted = Set.copyOf(interests == null ? List.<String>of() : interests);
		record Scored(Book book, double score) {
		}
		List<Scored> scored = new ArrayList<>();
		for (Book book : fits) {
			Similarity similarity = similar.get(book.id());
			List<String> matched = matches(book, wanted);
			if (matched.isEmpty() && similarity == null) {
				continue;
			}
			scored.add(new Scored(book, settings.getTagWeight() * matched.size()
					+ (similarity == null ? 0 : settings.getSimilarityWeight() * similarity.score())));
		}
		scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(entry -> entry.book().title()));
		List<BookPick> picks = new ArrayList<>();
		for (Scored entry : scored.stream().limit(MAX_BOOKS).toList()) {
			Similarity similarity = similar.get(entry.book().id());
			picks.add(toPick(entry.book(), matches(entry.book(), wanted), similarity == null ? null : "Similar to " + similarity.to()));
		}
		if (picks.size() < MIN_MATCHED_BEFORE_TOP_UP) {
			Set<String> already = new java.util.HashSet<>();
			picks.forEach(pick -> already.add(pick.id()));
			fits.stream().filter(book -> !already.contains(book.id())).sorted(Comparator.comparing(Book::title))
					.limit(MAX_BOOKS - picks.size()).forEach(book -> picks.add(toPick(book, List.of(), null)));
		}
		return Optional.of(picks);
	}

	static List<BookPick> pick(List<Book> books, int ageMonths, List<String> interests) {
		Set<String> wanted = Set.copyOf(interests == null ? List.<String>of() : interests);
		List<Book> fits = books.stream().filter(book -> book.fitsAge(ageMonths)).toList();
		Comparator<Book> bySpecificity = Comparator.comparingInt((Book book) -> -matches(book, wanted).size())
				.thenComparing(Book::title);

		List<BookPick> picks = new ArrayList<>();
		fits.stream().filter(book -> !matches(book, wanted).isEmpty()).sorted(bySpecificity).limit(MAX_BOOKS)
				.forEach(book -> picks.add(toPick(book, matches(book, wanted), null)));
		if (picks.size() < MIN_MATCHED_BEFORE_TOP_UP) {
			Set<String> already = new java.util.HashSet<>();
			picks.forEach(pick -> already.add(pick.id()));
			fits.stream().filter(book -> !already.contains(book.id())).sorted(Comparator.comparing(Book::title))
					.limit(MAX_BOOKS - picks.size()).forEach(book -> picks.add(toPick(book, List.of(), null)));
		}
		return picks;
	}

	private static List<String> matches(Book book, Set<String> wanted) {
		return book.tags().stream().filter(wanted::contains).toList();
	}

	private static BookPick toPick(Book book, List<String> matched, String reason) {
		String cover = book.isbn13() == null ? null : "https://covers.openlibrary.org/b/isbn/" + book.isbn13() + "-M.jpg?default=false";
		return new BookPick(book.id(), book.title(), book.author(), book.minAgeMonths(), book.maxAgeMonths(),
				book.description(), cover, book.buyUrl(), book.store(), matched, reason);
	}
}
