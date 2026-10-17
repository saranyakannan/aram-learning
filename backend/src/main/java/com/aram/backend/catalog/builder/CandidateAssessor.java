package com.aram.backend.catalog.builder;

import com.aram.backend.agent.ChannelSafetyService;
import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ContentAssessment;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.SourceDescriptor;
import com.aram.backend.config.AramProperties;
import com.aram.backend.exception.PodcastLookupException;
import com.aram.backend.exception.YoutubeIntegrationException;
import com.aram.backend.model.Channel;
import com.aram.backend.model.SafetyAssessment;
import com.aram.backend.model.Video;
import com.aram.backend.podcast.PodcastFacts;
import com.aram.backend.podcast.PodcastFeedParser;
import com.aram.backend.podcast.PodcastFeedService;
import com.aram.backend.service.YoutubeVideoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Has the model assess each kept candidate with the same strict check the agent uses, and records the result in the catalog with
 * provenance {@code CATALOG_BUILDER}. A confident SAFE becomes {@code ASSESSED}; anything else is stored {@code UNDER_REVIEW} so it is
 * never offered and never assessed again. This class cannot set {@code APPROVED_FOR_RECOMMENDATION}: only a person can, by applying a review.
 */
@Service
@ConditionalOnProperty(prefix = "aram.builder", name = "enabled", havingValue = "true")
public class CandidateAssessor {

	public enum State { ASSESSED, UNDER_REVIEW, EXISTING, FAILED, SKIPPED }

	/** {@code assessment} is null for EXISTING and FAILED. */
	public record Outcome(Candidate candidate, State state, ContentSource source, ContentAssessment assessment) {
	}

	private static final Logger log = LoggerFactory.getLogger(CandidateAssessor.class);

	private final ChannelSafetyService safety;
	private final CatalogService catalog;
	private final YoutubeVideoService videos;
	private final PodcastFeedService feeds;
	private final int targetAgeMonths;

	public CandidateAssessor(ChannelSafetyService safety, CatalogService catalog, YoutubeVideoService videos, PodcastFeedService feeds,
			AramProperties properties) {
		this.safety = safety;
		this.catalog = catalog;
		this.videos = videos;
		this.feeds = feeds;
		this.targetAgeMonths = properties.getBuilder().getTargetAgeMonths();
	}

	public List<Outcome> assess(List<Candidate> candidates, int max) {
		List<Outcome> outcomes = new ArrayList<>();
		for (Candidate candidate : candidates.stream().limit(max).toList()) {
			outcomes.add(assessOne(candidate));
		}
		return outcomes;
	}

	private Outcome assessOne(Candidate candidate) {
		try {
			SafetyAssessment assessment;
			SourceDescriptor descriptor;
			if (candidate.kind() == Candidate.Kind.YOUTUBE) {
				descriptor = SourceDescriptor.youtube(candidate.youtube());
				List<Video> recent = List.of();
				try {
					recent = videos.fetchLatestVideos(new Channel(candidate.youtube().channelId(), candidate.name(), null,
							candidate.youtube().description(), candidate.youtube().channelId(), null, null, null, List.of()));
				} catch (YoutubeIntegrationException e) {
					log.warn("Could not load recent videos for {}; judging from the description only", candidate.sourceId());
				}
				assessment = safety.assess(candidate.youtube(), recent, targetAgeMonths / 12);
			} else {
				PodcastFeedParser.Feed feed = feeds.fetchFeed(candidate.podcast().feedUrl());
				if (feed.language() != null && !feed.language().isBlank() && !feed.language().toLowerCase(java.util.Locale.ROOT).startsWith("en")) {
					return new Outcome(candidate, State.SKIPPED, null, null);
				}
				PodcastFacts vetted = candidate.podcast().withDescription(feed.description() == null ? "" : feed.description(), feed.explicit());
				descriptor = vetted.toDescriptor();
				assessment = safety.assessPodcast(vetted, feed.episodes(), targetAgeMonths / 12);
			}
			if (assessment.status() != SafetyAssessment.Status.CHECKED) {
				return new Outcome(candidate, State.FAILED, null, null);
			}
			CatalogService.Registered registered = catalog.registerAssessed(descriptor, assessment, targetAgeMonths, ContentSource.CATALOG_BUILDER);
			if (!registered.created()) {
				return new Outcome(candidate, State.EXISTING, registered.source(), null);
			}
			State state = registered.source().assessmentStatus() == com.aram.backend.catalog.Statuses.Assessment.ASSESSED
					? State.ASSESSED : State.UNDER_REVIEW;
			return new Outcome(candidate, state, registered.source(), registered.assessment());
		} catch (YoutubeIntegrationException | PodcastLookupException e) {
			log.warn("Could not assess {}: {}", candidate.sourceId(), e.getMessage());
			return new Outcome(candidate, State.FAILED, null, null);
		}
	}
}
