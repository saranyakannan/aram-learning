package com.aram.backend.podcast;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PodcastAudioCheckTest {

	private static final String AUDIO = "https://cdn.example.com/ep1.mp3";

	private final FakeHttp http = new FakeHttp();
	private Instant now = Instant.parse("2026-10-17T12:00:00Z");
	private PodcastAudioCheck check;

	@BeforeEach
	void setUp() {
		Clock clock = new Clock() {
			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return now;
			}
		};
		check = new PodcastAudioCheck(http, clock);
	}

	private static PodcastEpisode episode(String audioUrl) {
		return new PodcastEpisode("e1", "Episode", "", null, audioUrl, "audio/mpeg", null, null);
	}

	private void answerAudio(int status, String contentType) {
		http.answers.put(AUDIO, new HttpFetcher.Response(status, new byte[0], false, contentType));
	}

	@Test
	void audioThatAnswersIsPlayable() {
		answerAudio(200, "audio/mpeg");

		assertThat(check.check(AUDIO)).isEqualTo(PodcastAudioCheck.Verdict.PLAYABLE);
	}

	@Test
	void aHostThatRefusesOrHasNoFileIsUnplayable() {
		for (int status : new int[] {401, 403, 404, 410}) {
			PodcastAudioCheck fresh = new PodcastAudioCheck(http, Clock.fixed(now, ZoneOffset.UTC));
			answerAudio(status, "application/xml");

			assertThat(fresh.check(AUDIO)).as("status " + status).isEqualTo(PodcastAudioCheck.Verdict.UNPLAYABLE);
		}
	}

	@Test
	void anErrorPageServedWithA200IsUnplayable() {
		answerAudio(200, "text/html; charset=utf-8");

		assertThat(check.check(AUDIO)).isEqualTo(PodcastAudioCheck.Verdict.UNPLAYABLE);
	}

	@Test
	void outagesAndRateLimitsAreUnknownSoAGoodShowIsNeverHiddenByABlip() {
		for (int status : new int[] {408, 429, 500, 503}) {
			answerAudio(status, "text/plain");
			assertThat(check.check(AUDIO)).as("status " + status).isEqualTo(PodcastAudioCheck.Verdict.UNKNOWN);
		}
		http.answers.clear(); // the fake then throws "could not reach"
		assertThat(check.check(AUDIO)).isEqualTo(PodcastAudioCheck.Verdict.UNKNOWN);
	}

	@Test
	void answersAreRememberedAndARefusalIsRecheckedSooner() {
		answerAudio(403, "application/xml");
		check.check(AUDIO);
		check.check(AUDIO);
		assertThat(http.requested).hasSize(1);

		now = now.plusSeconds(16 * 60);
		answerAudio(200, "audio/mpeg");

		assertThat(check.check(AUDIO)).isEqualTo(PodcastAudioCheck.Verdict.PLAYABLE);
		assertThat(http.requested).hasSize(2);
		now = now.plusSeconds(30 * 60);
		check.check(AUDIO);
		assertThat(http.requested).as("a working show is trusted for an hour").hasSize(2);
	}

	@Test
	void aShowIsJudgedByItsNewestEpisodeAndNoEpisodesMeansUnknown() {
		answerAudio(403, "application/xml");

		assertThat(check.forEpisodes(List.of(episode(AUDIO), episode("https://other/old.mp3")))).isEqualTo(PodcastAudioCheck.Verdict.UNPLAYABLE);
		assertThat(http.requested).containsExactly(AUDIO);
		assertThat(check.forEpisodes(List.of())).isEqualTo(PodcastAudioCheck.Verdict.UNKNOWN);
		assertThat(check.isDefinitelyUnplayable(List.of())).isFalse();
	}
}
