package com.aram.backend.agent;

import com.aram.backend.model.Channel;
import com.aram.backend.model.ChannelFacts;
import com.aram.backend.model.ChannelRecommendation;
import com.aram.backend.model.Tag;
import com.aram.backend.service.RecommendationService;
import com.aram.backend.service.TagCatalogService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers "suggest a phonics channel" from Aram's own trusted list, never from the open web. The parent's words are mapped
 * to one of the reviewed interest tags, and the picks come from the same ranking as the Manage Channels shelf
 * ({@link RecommendationService}): only reviewed and approved sources, only what the child's age allows, nothing the child
 * already has. The agent offers these as cards; choosing one stages it through the existing path, so the parent's Save is
 * still the only approval.
 */
@Component
public class CatalogSuggester {

	static final int MAX_PICKS = 5;

	/** Words a parent is likely to use, per tag id. The tag's own id and label words are matched as well. */
	private static final Map<String, List<String>> WORDS = Map.ofEntries(
			Map.entry("math", List.of("math", "maths", "mathematics", "number", "numbers", "counting", "arithmetic", "addition", "shapes")),
			Map.entry("bedtime-stories", List.of("bedtime", "story", "stories", "storytime", "sleep", "bedtime story", "tales", "fairy")),
			Map.entry("phonics-reading", List.of("phonics", "reading", "read", "letters", "alphabet", "abc", "words", "vocabulary",
					"spelling", "literacy", "english")),
			Map.entry("space", List.of("space", "planets", "planet", "rocket", "rockets", "stars", "astronomy", "solar system")),
			Map.entry("nature", List.of("nature", "animals", "animal", "wildlife", "plants", "birds", "outdoors", "garden", "zoo")),
			Map.entry("coding", List.of("coding", "code", "programming", "logic", "computer", "computers", "robots", "scratch")),
			Map.entry("art", List.of("art", "drawing", "draw", "painting", "paint", "colouring", "coloring", "sketch")),
			Map.entry("science", List.of("science", "experiment", "experiments", "stem", "how things work")),
			Map.entry("music", List.of("music", "songs", "song", "rhymes", "rhyme", "nursery", "singing", "dance", "rhythm")),
			Map.entry("emotions", List.of("emotions", "feelings", "friendship", "friends", "kindness", "manners", "social",
					"calm", "mindfulness", "yoga")),
			Map.entry("podcasts", List.of("curiosity", "curious", "audio")),
			Map.entry("crafts", List.of("crafts", "craft", "origami", "diy", "making", "cutting", "folding")));

	private final RecommendationService recommendations;
	private final TagCatalogService tags;

	public CatalogSuggester(RecommendationService recommendations, TagCatalogService tags) {
		this.recommendations = recommendations;
		this.tags = tags;
	}

	/** What a suggestion run found. {@code tag} is null when the topic is not one Aram lists. */
	public record Suggestions(Tag tag, boolean podcastsOnly, boolean channelsOnly, List<Pick> picks) {
	}

	/** One suggested source: the card the parent sees, why it was picked, and which topics it covers. */
	public record Pick(ChannelFacts card, String reason, List<String> matchedTags) {
	}

	public Suggestions suggest(String parentId, String topic) {
		String text = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
		boolean podcastsOnly = containsWord(text, "podcast", "podcasts", "audio", "listen");
		boolean channelsOnly = !podcastsOnly && containsWord(text, "channel", "channels", "video", "videos", "youtube", "watch");
		Optional<Tag> tag = match(text);
		if (tag.isEmpty() && podcastsOnly) {
			// "suggest a podcast" with no subject: the general audio-shows topic.
			tag = tags.getTags().stream().filter(t -> "podcasts".equals(t.id())).findFirst();
		}
		if (tag.isEmpty()) {
			return new Suggestions(null, podcastsOnly, channelsOnly, List.of());
		}
		List<Pick> picks = recommendations.recommendForTags(parentId, Set.of(tag.get().id())).stream()
				.filter(r -> !podcastsOnly || Channel.PODCAST_FEED.equals(r.channel().sourceType()))
				.filter(r -> !channelsOnly || !Channel.PODCAST_FEED.equals(r.channel().sourceType()))
				.limit(MAX_PICKS)
				.map(CatalogSuggester::toPick)
				.toList();
		return new Suggestions(tag.get(), podcastsOnly, channelsOnly, picks);
	}

	/** The labels of the topics Aram can suggest for, for the "we don't have that" message. */
	public List<String> topicLabels() {
		return tags.getTags().stream().map(Tag::label).toList();
	}

	/** Maps the parent's phrase to one reviewed tag: an id, the label, or the most keyword hits. */
	Optional<Tag> match(String text) {
		String normalised = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9& ]", " ").replaceAll("\\s+", " ").strip();
		if (normalised.isEmpty()) {
			return Optional.empty();
		}
		for (Tag tag : tags.getTags()) {
			if (normalised.equals(tag.id()) || normalised.equals(tag.label().toLowerCase(Locale.ROOT))) {
				return Optional.of(tag);
			}
		}
		Tag best = null;
		int bestHits = 0;
		for (Tag tag : tags.getTags()) {
			int hits = 0;
			for (String word : WORDS.getOrDefault(tag.id(), List.of())) {
				if (containsWord(normalised, word)) {
					hits++;
				}
			}
			if (hits > bestHits) {
				best = tag;
				bestHits = hits;
			}
		}
		return Optional.ofNullable(best);
	}

	private static boolean containsWord(String text, String... words) {
		String padded = " " + text.replaceAll("[^a-z0-9& ]", " ").replaceAll("\\s+", " ") + " ";
		for (String word : words) {
			if (padded.contains(" " + word + " ")) {
				return true;
			}
		}
		return false;
	}

	private static Pick toPick(ChannelRecommendation recommendation) {
		Channel channel = recommendation.channel();
		boolean podcast = Channel.PODCAST_FEED.equals(channel.sourceType());
		ChannelFacts card = new ChannelFacts(channel.youtubeChannelId(), channel.name(), channel.youtubeHandle(),
				channel.description(), channel.thumbnailUrl(), null, null, podcast ? ChannelFacts.PODCAST : null);
		return new Pick(card, recommendation.reason(), recommendation.matchedTags());
	}
}
