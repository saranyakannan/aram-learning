package com.aram.backend.catalog.builder;

import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.SafetyAssessment;
import com.aram.backend.podcast.PodcastFacts;

import java.util.List;

/** Small builders shared by the catalog-builder tests. */
final class BuilderFixtures {

	private BuilderFixtures() {
	}

	static String channelId(char letter) {
		return "UC" + String.valueOf(letter).repeat(22);
	}

	static ChannelFacts channel(char letter, String title, Long subscribers, Boolean madeForKids) {
		return new ChannelFacts(channelId(letter), title, "@" + title.replace(" ", ""), title + " description", "https://t/" + letter + ".jpg",
				subscribers, madeForKids);
	}

	static Candidate youtube(char letter, String title, Long subscribers, Boolean madeForKids) {
		return Candidate.youtube(channel(letter, title, subscribers, madeForKids), "science for kids", "science");
	}

	static PodcastFacts show(long id, String title, boolean explicit) {
		return new PodcastFacts(id, title, "Publisher", "About " + title, "https://feeds.example.com/" + id + ".xml", "https://img/" + id + ".jpg",
				explicit, "Kids & Family");
	}

	static SafetyAssessment cleared(int minAge, int maxAge, String... tags) {
		return new SafetyAssessment(SafetyAssessment.Status.CHECKED, SafetyAssessment.Verdict.SAFE, true, SafetyAssessment.Confidence.HIGH,
				List.of(), "Calm, age-appropriate science.", minAge, maxAge, "Science", List.of(tags), "gemini-test", 1L);
	}

	static SafetyAssessment refused() {
		return new SafetyAssessment(SafetyAssessment.Status.CHECKED, SafetyAssessment.Verdict.NOT_CLEARLY_SAFE, false, SafetyAssessment.Confidence.HIGH,
				List.of("Scary themes"), "Not clearly suitable.", 8, 12, "Other", List.of(), "gemini-test", 1L);
	}
}
