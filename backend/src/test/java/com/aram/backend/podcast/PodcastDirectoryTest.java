package com.aram.backend.podcast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PodcastDirectoryTest {

	private static final String BASE = "https://itunes.apple.com";
	private static final String RESULTS = """
			{"resultCount": 4, "results": [
			  {"collectionId": 1233834541, "collectionName": "Wow in the World", "artistName": "Tinkercast",
			   "feedUrl": "https://rss.art19.com/wow-in-the-world", "artworkUrl600": "https://img.example.com/a.jpg",
			   "trackExplicitness": "cleaned", "primaryGenreName": "Education for Kids"},
			  {"collectionId": 6814253776, "collectionName": "Wow in the World", "artistName": "Martin",
			   "feedUrl": "https://www.spreaker.com/show/6848478/episodes/feed", "trackExplicitness": "explicit",
			   "primaryGenreName": "Education for Kids"},
			  {"collectionId": 111, "collectionName": "No feed show", "artistName": "X"},
			  {"collectionId": 222, "collectionName": "Bad feed show", "artistName": "X", "feedUrl": "ftp://example.com/feed"}
			]}""";

	private final FakeHttp http = new FakeHttp();
	private PodcastDirectory directory;

	@BeforeEach
	void setUp() {
		directory = new PodcastDirectory(http, new ObjectMapper(), new AramProperties());
	}

	@Test
	void aSearchReturnsShowsWithAUsableFeedAndKeepsLookAlikesApart() {
		http.answer(BASE + "/search?media=podcast&entity=podcast&limit=5&term=wow+in+the+world", RESULTS);

		List<PodcastFacts> shows = directory.search("wow in the world");

		assertThat(shows).extracting(PodcastFacts::collectionId).containsExactly(1233834541L, 6814253776L);
		assertThat(shows.get(0).author()).isEqualTo("Tinkercast");
		assertThat(shows.get(0).feedUrl()).isEqualTo("https://rss.art19.com/wow-in-the-world");
		assertThat(shows.get(0).explicit()).isFalse();
		assertThat(shows.get(1).explicit()).as("the directory's explicit flag is kept").isTrue();
		assertThat(shows.get(0).sourceId()).isEqualTo("podcast_1233834541");
	}

	@Test
	void searchTermsAreEncodedSoTheyCannotChangeTheRequest() {
		http.answer(BASE + "/search?media=podcast&entity=podcast&limit=5&term=a%26limit%3D100+b", "{\"results\":[]}");

		assertThat(directory.search("a&limit=100 b")).isEmpty();
	}

	@Test
	void aLookupByIdReturnsOnlyThatShow() {
		http.answer(BASE + "/lookup?entity=podcast&id=1233834541", RESULTS);

		Optional<PodcastFacts> show = directory.findById(1233834541L);

		assertThat(show).map(PodcastFacts::title).contains("Wow in the World");
		http.answer(BASE + "/lookup?entity=podcast&id=999", RESULTS);
		assertThat(directory.findById(999L)).isEmpty();
	}

	@Test
	void aFailingDirectoryOrUnreadableAnswerIsAPodcastLookupError() {
		http.answer(BASE + "/lookup?entity=podcast&id=1", 503, "down");
		http.answer(BASE + "/lookup?entity=podcast&id=2", "<html>not json</html>");

		assertThatThrownBy(() -> directory.findById(1L)).isInstanceOf(PodcastLookupException.class);
		assertThatThrownBy(() -> directory.findById(2L)).isInstanceOf(PodcastLookupException.class);
	}

	@Test
	void aShowBecomesACandidateCardAndACatalogDescriptor() {
		PodcastFacts show = new PodcastFacts(1233834541L, "Wow in the World", "Tinkercast", "Science.",
				"https://rss.art19.com/wow-in-the-world", "https://img.example.com/a.jpg", false, "Education for Kids");

		assertThat(show.toCandidate().kind()).isEqualTo("PODCAST");
		assertThat(show.toCandidate().channelId()).isEqualTo("1233834541");
		var descriptor = show.toDescriptor();
		assertThat(descriptor.sourceId()).isEqualTo("podcast_1233834541");
		assertThat(descriptor.sourceType()).isEqualTo("PODCAST_FEED");
		assertThat(descriptor.feedUrl()).isEqualTo("https://rss.art19.com/wow-in-the-world");
		assertThat(descriptor.limitation()).contains("no audio was reviewed");
		assertThat(descriptor.submissionType()).isEqualTo("PODCAST");
		assertThat(show.withDescription("New", true).explicit()).isTrue();
	}
}
