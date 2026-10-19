package com.aram.backend.service;

import com.aram.backend.catalog.CatalogService;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ContentSource;
import com.aram.backend.catalog.EligibilityPolicy;
import com.aram.backend.catalog.SourceSignal;
import com.aram.backend.catalog.SourceSignalRepository;
import com.aram.backend.config.AramProperties;
import com.aram.backend.model.ChannelRecommendation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Global-discovery suggestions (spec section 4): candidates whose tags overlap the child's interests, then the shared
 * eligibility filter (live catalog, human-cleared, age in months, not already approved), and only then a deterministic
 * ranking: sources inside the child's age range first (a source admitted only by the one-year age stretch is marked
 * {@code ageFit = false} and listed after them), then more matching tags, with focused sources winning ties.
 * Ranking never decides eligibility.
 *
 * <p>With {@code aram.ml.blend-enabled} (off by default, decision record 0008) the precomputed {@code source_signals} add
 * candidates that are nearest neighbours of sources the child already has (or, until it has {@code seed-count} of them, of its best tag matches), and rank by
 * {@code tagWeight * matchedTags + similarityWeight * similarity + curationBonus}. The eligibility filter is applied to every
 * candidate first, so a model score can never surface a source the policy refuses.
 */
@Service
public class RecommendationService {

	private final CatalogService catalog;
	private final ChildCatalogService childCatalog;
	private final ParentProfileService profileService;
	private final EligibilityPolicy policy;
	private final SourceSignalRepository signals;
	private final AramProperties.Ml settings;

	/** Tag-overlap ranking only (the blend is off). */
	public RecommendationService(CatalogService catalog, ChildCatalogService childCatalog,
			ParentProfileService profileService, EligibilityPolicy policy) {
		this(catalog, childCatalog, profileService, policy, null, new AramProperties.Ml());
	}

	@Autowired
	public RecommendationService(CatalogService catalog, ChildCatalogService childCatalog,
			ParentProfileService profileService, EligibilityPolicy policy, SourceSignalRepository signals,
			AramProperties properties) {
		this(catalog, childCatalog, profileService, policy, signals, properties.getMl());
	}

	public RecommendationService(CatalogService catalog, ChildCatalogService childCatalog,
			ParentProfileService profileService, EligibilityPolicy policy, SourceSignalRepository signals,
			AramProperties.Ml settings) {
		this.catalog = catalog;
		this.childCatalog = childCatalog;
		this.profileService = profileService;
		this.policy = policy;
		this.signals = signals;
		this.settings = settings;
	}

	public List<ChannelRecommendation> recommend(String parentId) {
		return recommend(parentId, Set.copyOf(profileService.getProfile(parentId).getTags()), false);
	}

	/**
	 * The same ranking for tags the parent just asked about, rather than the interests picked at sign-up. Every result
	 * matches at least one of the tags (the look-alikes of what the child already has, which suit the shelf, are left out),
	 * passes the eligibility filter and is not already on the child's list.
	 */
	public List<ChannelRecommendation> recommendForTags(String parentId, Set<String> wanted) {
		return wanted.isEmpty() ? List.of() : recommend(parentId, wanted, true);
	}

	private List<ChannelRecommendation> recommend(String parentId, Set<String> wanted, boolean onTopicOnly) {
		Optional<ParentProfileService.ChildContext> child = profileService.findChild(parentId);
		if (child.isEmpty()) {
			return List.of();
		}
		Set<String> approved = Set.copyOf(childCatalog.approvedSourceIds(child.get().childId()));
		if (settings.isBlendEnabled() && signals != null) {
			List<ChannelRecommendation> blended = blend(catalog.recommendable(), wanted, child.get().ageMonths(), approved);
			return onTopicOnly ? blended.stream().filter(r -> !r.matchedTags().isEmpty()).toList() : blended;
		}
		return rank(catalog.recommendable(), wanted, child.get().ageMonths(), approved);
	}

	/** Best similarity of a candidate to something the child already has, and what that something is called. */
	private record Similarity(double score, String to) {
	}

	List<ChannelRecommendation> blend(List<ContentSource> candidates, Set<String> wanted, int ageMonths, Set<String> approved) {
		// Seeds: what the child already has, then (cold start) the best tag matches until there are seedCount of them.
		Map<String, String> seeds = new java.util.LinkedHashMap<>();
		Set<String> soft = new java.util.HashSet<>();
		for (ContentSource source : catalog.findSources(approved)) {
			seeds.put(source.id(), source.name());
		}
		int missing = settings.getSeedCount() - seeds.size();
		if (missing > 0) {
			rank(candidates, wanted, ageMonths, approved).stream().limit(missing)
					.forEach(r -> {
						if (seeds.putIfAbsent(r.channel().id(), r.channel().name()) == null) {
							soft.add(r.channel().id());
						}
					});
		}
		Map<String, Similarity> similar = new HashMap<>();
		Map<String, SourceSignal> seedSignals = signals.findByIds(seeds.keySet());
		for (Map.Entry<String, String> seed : seeds.entrySet()) {
			SourceSignal signal = seedSignals.get(seed.getKey());
			if (signal == null) {
				continue;
			}
			for (SourceSignal.Neighbor neighbor : signal.neighbors()) {
				if (neighbor.similarity() >= settings.getMinSimilarity() && !neighbor.sourceId().equals(seed.getKey())) {
					// A neighbour of a mere tag match counts for less (seedDamping), so it never outranks the match it came from.
					double score = soft.contains(seed.getKey()) ? neighbor.similarity() * settings.getSeedDamping() : neighbor.similarity();
					similar.merge(neighbor.sourceId(), new Similarity(score, seed.getValue()),
							(a, b) -> a.score() >= b.score() ? a : b);
				}
			}
		}
		record Scored(ChannelRecommendation recommendation, double score) {
		}
		return candidates.stream()
				.filter(source -> !approved.contains(source.id()))
				.filter(source -> policy.recommendable(source, ageMonths))
				.map(source -> {
					List<String> matched = matchedTags(source, wanted);
					Similarity similarity = similar.get(source.id());
					if (matched.isEmpty() && similarity == null) {
						return null;
					}
					double score = settings.getTagWeight() * matched.size()
							+ (similarity == null ? 0 : settings.getSimilarityWeight() * similarity.score())
							+ (source.originTypes().contains(ContentSource.PREPOPULATED) ? settings.getCurationBonus() : 0);
					return new Scored(new ChannelRecommendation(catalog.toChannel(source), matched,
							policy.withinRange(source, ageMonths), similarity == null ? null : "Similar to " + similarity.to()), score);
				})
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator
						.comparing((Scored s) -> s.recommendation().ageFit()).reversed()
						.thenComparing(Comparator.comparingDouble(Scored::score).reversed())
						.thenComparing(s -> s.recommendation().channel().name()))
				.map(Scored::recommendation)
				.toList();
	}

	List<ChannelRecommendation> rank(List<ContentSource> candidates, Set<String> wanted, int ageMonths, Set<String> approved) {
		return candidates.stream()
				.filter(source -> !approved.contains(source.id()))
				.filter(source -> policy.recommendable(source, ageMonths))
				.map(source -> new ChannelRecommendation(catalog.toChannel(source), matchedTags(source, wanted),
						policy.withinRange(source, ageMonths)))
				.filter(recommendation -> !recommendation.matchedTags().isEmpty())
				.sorted(Comparator
						.comparing(ChannelRecommendation::ageFit).reversed()
						.thenComparing(Comparator.comparingInt((ChannelRecommendation r) -> r.matchedTags().size()).reversed())
						.thenComparing(Comparator.comparingDouble(RecommendationService::focus).reversed())
						.thenComparing(r -> r.channel().name()))
				.toList();
	}

	private static List<String> matchedTags(ContentSource source, Set<String> wanted) {
		return source.tagIds().stream().filter(wanted::contains).toList();
	}

	private static double focus(ChannelRecommendation recommendation) {
		return (double) recommendation.matchedTags().size() / recommendation.channel().tags().size();
	}
}
