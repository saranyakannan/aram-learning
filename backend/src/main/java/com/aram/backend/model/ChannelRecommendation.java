package com.aram.backend.model;

import java.util.List;

/**
 * A channel suggested for a set of tag ids. {@code matchedTags} are the tag ids that caused
 * the match; {@code ageFit} is false when the child's age is outside the channel's range.
 * {@code reason} is a short human-readable explanation when the suggestion came (partly) from the similarity engine,
 * for example "Similar to Bluey"; it is null for plain tag matches.
 */
public record ChannelRecommendation(Channel channel, List<String> matchedTags, boolean ageFit, String reason) {

	public ChannelRecommendation(Channel channel, List<String> matchedTags, boolean ageFit) {
		this(channel, matchedTags, ageFit, null);
	}
}
