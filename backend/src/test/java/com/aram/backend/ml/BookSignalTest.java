package com.aram.backend.ml;

import com.aram.backend.catalog.BookSignal;
import com.aram.backend.catalog.SourceSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BookSignalTest {

	@Test
	void aSignalSurvivesAFirestoreRoundTripAndCarriesNoPersonalFields() {
		BookSignal signal = new BookSignal("the-gruffalo", List.of(new SourceSignal.Neighbor("youtube_UCa", 0.91),
				new SourceSignal.Neighbor("podcast_1", 0.78)), "text-embedding-005", Instant.parse("2026-10-18T10:00:00Z"));

		Map<String, Object> stored = signal.toMap();
		BookSignal back = BookSignal.fromMap("the-gruffalo", stored);

		assertThat(stored.keySet()).containsExactlyInAnyOrder("bookId", "neighbors", "modelVersion", "computedAt");
		assertThat(back.neighbors()).containsExactlyElementsOf(signal.neighbors());
		assertThat(back.modelVersion()).isEqualTo("text-embedding-005");
		assertThat(back.computedAt()).isEqualTo(signal.computedAt());
	}

	@Test
	void unreadableNeighboursAreSkippedNotFatal() {
		BookSignal back = BookSignal.fromMap("b", Map.of("neighbors", List.of(Map.of("sourceId", "x"), "junk", Map.of("sourceId", "y", "similarity", 0.8))));

		assertThat(back.neighbors()).containsExactly(new SourceSignal.Neighbor("y", 0.8));
	}
}
