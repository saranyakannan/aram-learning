package com.aram.backend.analytics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PseudonymizerTest {

	private static final String KEY = "k".repeat(40);

	@Test
	void theSameIdAlwaysGivesTheSamePseudonymAndItNeverContainsTheId() {
		Pseudonymizer pseudonyms = new Pseudonymizer(KEY);
		String uid = "EPG3F8s25nODuG2R2wbuc0Wan5n1";

		String first = pseudonyms.parent(uid);

		assertThat(first).isEqualTo(pseudonyms.parent(uid)).hasSize(32).matches("[0-9a-f]{32}");
		assertThat(first).doesNotContain(uid);
	}

	@Test
	void differentKeysAndDifferentEntityTypesGiveDifferentPseudonyms() {
		String id = "abcdefgh12345678";

		assertThat(new Pseudonymizer(KEY).parent(id)).isNotEqualTo(new Pseudonymizer("j".repeat(40)).parent(id));
		assertThat(new Pseudonymizer(KEY).parent(id)).isNotEqualTo(new Pseudonymizer(KEY).child(id));
		assertThat(new Pseudonymizer(KEY).parent("a-1")).isNotEqualTo(new Pseudonymizer(KEY).parent("a-2"));
	}

	@Test
	void aMissingOrShortKeyFailsClosed() {
		for (String bad : new String[] { null, "", "   ", "too-short" }) {
			assertThatThrownBy(() -> new Pseudonymizer(bad)).as(String.valueOf(bad)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("ANALYTICS_HMAC_KEY");
		}
	}

	@Test
	void anEmptyIdIsNeverPseudonymised() {
		Pseudonymizer pseudonyms = new Pseudonymizer(KEY);

		assertThatThrownBy(() -> pseudonyms.parent(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> pseudonyms.child(" ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theKeyAppearsInNoPseudonymAndSurroundingSpacesDoNotChangeThem() {
		String withSpaces = "  " + KEY + "  ";

		assertThat(new Pseudonymizer(withSpaces).parent("someone-12345")).isEqualTo(new Pseudonymizer(KEY).parent("someone-12345"));
		assertThat(new Pseudonymizer(KEY).parent("someone-12345")).doesNotContain(KEY);
	}
}
