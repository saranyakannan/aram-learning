package com.aram.backend.agent;

import com.aram.backend.model.ChannelFacts;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Channel;
import com.google.api.services.youtube.model.ChannelListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.aram.backend.exception.YoutubeIntegrationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds YouTube channels for the agent. Quota: id/handle/video lookups cost 1 unit each; a name
 * search costs 100 (plus 1 to fetch details), so names are only searched when nothing more
 * precise was given.
 */
@Service
public class YoutubeChannelLookup {

	/** Result of a name search: {@code exact} is set only when exactly one result's title matches the query. */
	public record SearchOutcome(ChannelFacts exact, List<ChannelFacts> candidates) {
	}

	static final int MAX_CANDIDATES = 3;
	/** YouTube accepts up to 50 ids in one channels.list call. */
	public static final int MAX_IDS_PER_CALL = 50;
	private static final long SEARCH_RESULTS = 8;
	private static final List<String> DETAIL_PARTS = List.of("snippet", "statistics", "status");

	private final YouTube youTube;

	public YoutubeChannelLookup(YouTube youTube) {
		this.youTube = youTube;
	}

	public Optional<ChannelFacts> findById(String channelId) {
		return first(() -> youTube.channels().list(DETAIL_PARTS).setId(List.of(channelId)).execute());
	}

	public Optional<ChannelFacts> findByHandle(String handle) {
		return first(() -> youTube.channels().list(DETAIL_PARTS).setForHandle(handle).execute());
	}

	public Optional<ChannelFacts> findByVideoId(String videoId) {
		try {
			var response = youTube.videos().list(List.of("snippet")).setId(List.of(videoId)).execute();
			if (response.getItems() == null || response.getItems().isEmpty()) {
				return Optional.empty();
			}
			return findById(response.getItems().get(0).getSnippet().getChannelId());
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to look up video " + videoId, e);
		}
	}

	/** Details for up to 50 channels per call (1 quota unit per call), keyed by channel id; ids YouTube no longer knows are absent. */
	public java.util.Map<String, ChannelFacts> findByIds(List<String> channelIds) {
		java.util.Map<String, ChannelFacts> found = new java.util.LinkedHashMap<>();
		try {
			for (int from = 0; from < channelIds.size(); from += MAX_IDS_PER_CALL) {
				List<String> batch = channelIds.subList(from, Math.min(from + MAX_IDS_PER_CALL, channelIds.size()));
				ChannelListResponse response = youTube.channels().list(DETAIL_PARTS).setId(batch).setMaxResults((long) MAX_IDS_PER_CALL).execute();
				if (response.getItems() != null) {
					response.getItems().forEach(channel -> found.put(channel.getId(), toFacts(channel)));
				}
			}
			return found;
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to refresh channels from YouTube", e);
		}
	}

	public SearchOutcome searchByName(String name) {
		List<ChannelFacts> ranked = searchChannels(name);
		return ranked.isEmpty() ? new SearchOutcome(null, List.of()) : outcomeFor(name, ranked);
	}

	/**
	 * Channels YouTube ranks for a query (strict safe search, up to 8), with details. One search costs 100 quota units plus 1 for
	 * the details call; the catalog builder budgets for this.
	 */
	public List<ChannelFacts> searchChannels(String query) {
		try {
			var search = youTube.search().list(List.of("snippet"))
					.setQ(query)
					.setType(List.of("channel"))
					.setSafeSearch("strict")
					.setMaxResults(SEARCH_RESULTS)
					.execute();
			if (search.getItems() == null || search.getItems().isEmpty()) {
				return List.of();
			}
			List<String> ids = search.getItems().stream()
					.map(SearchResult::getId)
					.filter(id -> id != null && id.getChannelId() != null)
					.map(id -> id.getChannelId())
					.distinct()
					.toList();
			ChannelListResponse details = youTube.channels().list(DETAIL_PARTS).setId(ids).execute();

			// channels.list does not keep the search ranking, so restore it.
			List<ChannelFacts> ranked = new ArrayList<>();
			for (String id : ids) {
				if (details.getItems() != null) {
					details.getItems().stream().filter(channel -> id.equals(channel.getId())).findFirst()
							.map(YoutubeChannelLookup::toFacts).ifPresent(ranked::add);
				}
			}
			return ranked;
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to search YouTube for '" + query + "'", e);
		}
	}

	/** Exact only if one result has the same title (ignoring case and punctuation); otherwise the top candidates. */
	static SearchOutcome outcomeFor(String query, List<ChannelFacts> ranked) {
		String wanted = normalize(query);
		List<ChannelFacts> sameTitle = ranked.stream().filter(channel -> normalize(channel.title()).equals(wanted)).toList();
		if (sameTitle.size() == 1) {
			return new SearchOutcome(sameTitle.get(0), List.of(sameTitle.get(0)));
		}
		return new SearchOutcome(null, ranked.stream().limit(MAX_CANDIDATES).toList());
	}

	static String normalize(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	private interface Call {
		ChannelListResponse run() throws IOException;
	}

	private Optional<ChannelFacts> first(Call call) {
		try {
			ChannelListResponse response = call.run();
			if (response.getItems() == null || response.getItems().isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(toFacts(response.getItems().get(0)));
		} catch (IOException e) {
			throw new YoutubeIntegrationException("Failed to look up the channel on YouTube", e);
		}
	}

	private static ChannelFacts toFacts(Channel channel) {
		var snippet = channel.getSnippet();
		var thumbnails = snippet == null ? null : snippet.getThumbnails();
		String thumbnail = null;
		if (thumbnails != null) {
			var best = thumbnails.getHigh() != null ? thumbnails.getHigh()
					: thumbnails.getMedium() != null ? thumbnails.getMedium() : thumbnails.getDefault();
			thumbnail = best == null ? null : best.getUrl();
		}
		var statistics = channel.getStatistics();
		Long subscribers = statistics != null && statistics.getSubscriberCount() != null
				&& !Boolean.TRUE.equals(statistics.getHiddenSubscriberCount())
				? statistics.getSubscriberCount().longValue() : null;
		var status = channel.getStatus();

		return new ChannelFacts(
				channel.getId(),
				snippet == null ? "" : snippet.getTitle(),
				snippet == null ? null : snippet.getCustomUrl(),
				snippet == null ? "" : snippet.getDescription(),
				thumbnail,
				subscribers,
				status == null ? null : status.getMadeForKids(),
				null,
				snippet == null ? null : snippet.getDefaultLanguage());
	}
}
