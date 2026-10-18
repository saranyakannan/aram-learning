package com.aram.backend.service;

import com.aram.backend.config.AramProperties;
import com.aram.backend.model.Tag;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The reviewed list of picture books shown to parents (never to the child), loaded from {@code books.json}. It is plain
 * reviewed data like the taxonomy: nothing is stored in Firestore and nothing about a family is attached to it. A bad
 * entry stops the application from starting, so a typo cannot reach a parent.
 */
@Service
public class CuratedBookCatalog {

	/** Stores the buy links may point to, with the name shown on the button. */
	static final java.util.Map<String, String> STORES = java.util.Map.of(
			"amazon.in", "Amazon", "amazon.com", "Amazon", "amzn.in", "Amazon", "amzn.to", "Amazon", "a.co", "Amazon",
			"firstcry.com", "FirstCry");
	static final int MAX_DESCRIPTION = 220;

	/** One book. Ages are in months so books for newborns work; {@code isbn13} (optional) is only used for the cover. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Book(String id, String title, String author, int minAgeMonths, int maxAgeMonths, List<String> tags,
			String description, String isbn13, String buyUrl) {
		public Book {
			tags = tags == null ? List.of() : List.copyOf(tags);
		}

		public boolean fitsAge(int ageMonths) {
			return ageMonths >= minAgeMonths && ageMonths <= maxAgeMonths;
		}

		/** "Amazon" or "FirstCry", from the link's host. */
		public String store() {
			return storeFor(buyUrl);
		}
	}

	private final ResourceLoader resourceLoader;
	private final AramProperties properties;
	private final ObjectMapper objectMapper;
	private final TagCatalogService tagCatalog;

	private List<Book> books = List.of();

	public CuratedBookCatalog(ResourceLoader resourceLoader, AramProperties properties, ObjectMapper objectMapper,
			TagCatalogService tagCatalog) {
		this.resourceLoader = resourceLoader;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.tagCatalog = tagCatalog;
	}

	@PostConstruct
	void load() {
		List<Book> loaded;
		try (var input = resourceLoader.getResource(properties.getBooksFile()).getInputStream()) {
			loaded = List.of(objectMapper.readValue(input, Book[].class));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to load books from " + properties.getBooksFile(), e);
		}
		validate(loaded, tagCatalog.getTags().stream().map(Tag::id).collect(java.util.stream.Collectors.toSet()));
		this.books = loaded;
	}

	/** Throws {@link IllegalStateException} naming the first bad entry. */
	static void validate(List<Book> list, Set<String> knownTags) {
		Set<String> ids = new HashSet<>();
		for (Book book : list) {
			String where = "books.json entry '" + book.id() + "': ";
			require(book.id() != null && !book.id().isBlank() && ids.add(book.id()), where + "missing or duplicate id");
			require(book.title() != null && !book.title().isBlank(), where + "missing title");
			require(book.minAgeMonths() >= 0 && book.maxAgeMonths() <= 216 && book.minAgeMonths() <= book.maxAgeMonths(),
					where + "invalid age range");
			require(!book.tags().isEmpty() && knownTags.containsAll(book.tags()), where + "needs one or more known interest tags");
			require(book.description() != null && !book.description().isBlank() && book.description().length() <= MAX_DESCRIPTION,
					where + "description must be 1 to " + MAX_DESCRIPTION + " characters");
			require(book.isbn13() == null || book.isbn13().matches("\\d{13}"), where + "isbn13 must be 13 digits");
			require(storeFor(book.buyUrl()) != null, where + "buy link must be https on an allowed store");
		}
	}

	private static void require(boolean ok, String message) {
		if (!ok) {
			throw new IllegalStateException(message);
		}
	}

	/** The store name for an https link on an allowed host, or null when the link is not acceptable. */
	static String storeFor(String url) {
		try {
			URI uri = URI.create(url == null ? "" : url);
			if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
				return null;
			}
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			for (var store : STORES.entrySet()) {
				if (host.equals(store.getKey()) || host.endsWith("." + store.getKey())) {
					return store.getValue();
				}
			}
			return null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public List<Book> getBooks() {
		return books;
	}
}
