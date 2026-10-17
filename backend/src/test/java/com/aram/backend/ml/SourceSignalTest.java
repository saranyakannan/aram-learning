package com.aram.backend.ml;

import com.aram.backend.catalog.SourceSignal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceSignalTest {

	@Test
	void roundTripsThroughTheFirestoreShape() {
		var signal = new SourceSignal("youtube_x", List.of(new SourceSignal.Neighbor("youtube_y", 0.9)), 3, "Stories",
				"text-embedding-005", Instant.parse("2026-10-15T10:00:00Z"));

		var back = SourceSignal.fromMap("youtube_x", signal.toMap());

		assertThat(back.neighbors()).containsExactly(new SourceSignal.Neighbor("youtube_y", 0.9));
		assertThat(back.clusterId()).isEqualTo(3);
		assertThat(back.clusterLabel()).isEqualTo("Stories");
		assertThat(back.computedAt()).isEqualTo(Instant.parse("2026-10-15T10:00:00Z"));
	}

	@Test
	void aMissingOrMalformedDocumentReadsAsAnEmptySignal() {
		var back = SourceSignal.fromMap("youtube_x", java.util.Map.of("neighbors", List.of("junk")));

		assertThat(back.neighbors()).isEmpty();
		assertThat(back.clusterId()).isNull();
	}
}
