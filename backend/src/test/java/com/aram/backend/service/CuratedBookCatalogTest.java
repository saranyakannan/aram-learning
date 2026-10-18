package com.aram.backend.service;

import com.aram.backend.config.AramProperties;
import com.aram.backend.service.CuratedBookCatalog.Book;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CuratedBookCatalogTest {

	private static final Set<String> TAGS = Set.of("math", "nature", "bedtime-stories");

	private static Book book(String id, int min, int max, List<String> tags, String url) {
		return new Book(id, "Title " + id, "Author", min, max, tags, "A short description.", null, url);
	}

	@Test
	void theRealReviewedListLoadsAndEveryEntryIsWellFormed() {
		AramProperties properties = new AramProperties();
		ObjectMapper mapper = new ObjectMapper();
		DefaultResourceLoader loader = new DefaultResourceLoader();
		TagCatalogService tags = new TagCatalogService(loader, properties, mapper);
		tags.loadTags();
		CuratedBookCatalog catalog = new CuratedBookCatalog(loader, properties, mapper, tags);

		catalog.load(); // throws on any bad entry

		List<Book> books = catalog.getBooks();
		assertThat(books).hasSizeGreaterThanOrEqualTo(25);
		Set<String> links = new HashSet<>();
		for (Book book : books) {
			assertThat(links.add(book.buyUrl())).as("unique buy link " + book.id()).isTrue();
			assertThat(book.store()).as(book.id()).isIn("Amazon", "FirstCry");
		}
		assertThat(books.stream().filter(book -> book.fitsAge(3)).count()).as("something for a newborn").isPositive();
		assertThat(books.stream().filter(book -> book.fitsAge(54)).count()).as("something for a 4 and a half year old").isPositive();
	}

	@Test
	void aGoodEntryPasses() {
		CuratedBookCatalog.validate(List.of(book("a", 0, 12, List.of("nature"), "https://www.amazon.in/dp/0241003008")), TAGS);
	}

	@Test
	void badEntriesStopTheApplicationFromStarting() {
		String ok = "https://www.amazon.in/dp/0241003008";
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(book("a", 0, 12, List.of("nature"), ok), book("a", 0, 12, List.of("nature"), ok)), TAGS))
				.hasMessageContaining("duplicate id");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(book("a", 0, 12, List.of("not-a-tag"), ok)), TAGS))
				.hasMessageContaining("known interest tags");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(book("a", 0, 12, List.of(), ok)), TAGS))
				.hasMessageContaining("known interest tags");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(book("a", 30, 12, List.of("nature"), ok)), TAGS))
				.hasMessageContaining("age range");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(book("a", -1, 12, List.of("nature"), ok)), TAGS))
				.hasMessageContaining("age range");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(new Book("a", "T", null, 0, 12, List.of("nature"), "x".repeat(300), null, ok)), TAGS))
				.hasMessageContaining("description");
		assertThatThrownBy(() -> CuratedBookCatalog.validate(List.of(new Book("a", "T", null, 0, 12, List.of("nature"), "d", "12345", ok)), TAGS))
				.hasMessageContaining("isbn13");
	}

	@Test
	void onlyHttpsLinksOnTheAllowedStoresAreAccepted() {
		assertThat(CuratedBookCatalog.storeFor("https://www.amazon.in/dp/0241003008")).isEqualTo("Amazon");
		assertThat(CuratedBookCatalog.storeFor("https://amzn.in/d/en9xAcG")).isEqualTo("Amazon");
		assertThat(CuratedBookCatalog.storeFor("https://www.firstcry.com/intellibaby/x/1/product-detail")).isEqualTo("FirstCry");

		assertThat(CuratedBookCatalog.storeFor("http://www.amazon.in/dp/0241003008")).as("plain http").isNull();
		assertThat(CuratedBookCatalog.storeFor("https://evilamazon.in/dp/1")).as("look-alike host").isNull();
		assertThat(CuratedBookCatalog.storeFor("https://amazon.in.evil.com/dp/1")).as("allowed name inside another domain").isNull();
		assertThat(CuratedBookCatalog.storeFor("https://user@www.amazon.in/dp/1")).as("credentials in the link").isNull();
		assertThat(CuratedBookCatalog.storeFor("javascript:alert(1)")).isNull();
		assertThat(CuratedBookCatalog.storeFor("https://example.com/book")).isNull();
		assertThat(CuratedBookCatalog.storeFor(null)).isNull();
		assertThat(CuratedBookCatalog.storeFor("not a url")).isNull();
	}
}
