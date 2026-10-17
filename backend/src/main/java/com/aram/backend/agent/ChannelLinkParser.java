package com.aram.backend.agent;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Turns whatever a parent pastes (link, @handle, channel id or plain name) into a {@link ChannelQuery}. */
@Component
public class ChannelLinkParser {

	static final int MAX_NAME_LENGTH = 100;

	private static final Pattern CHANNEL_ID = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
	private static final Pattern HANDLE = Pattern.compile("^@[\\p{L}\\p{N}._-]{3,30}$");
	private static final Pattern VIDEO_ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");
	private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com");
	private static final String UNSUPPORTED_SITE =
			"Only YouTube channels can be added this way. For a podcast, send an Apple Podcasts link or the show's name.";

	public ChannelQuery parse(String input) {
		String text = input == null ? "" : input.strip();
		if (text.isEmpty()) {
			return new ChannelQuery.Unsupported("Please tell me which channel to add.");
		}
		if (looksLikeUrl(text)) {
			return parseUrl(text);
		}
		if (CHANNEL_ID.matcher(text).matches()) {
			return new ChannelQuery.ById(text);
		}
		if (text.startsWith("@") && !text.contains(" ")) {
			return HANDLE.matcher(text).matches()
					? new ChannelQuery.ByHandle(text)
					: new ChannelQuery.Unsupported("That doesn't look like a valid @handle.");
		}
		return text.length() > MAX_NAME_LENGTH
				? new ChannelQuery.Unsupported("That is too long to be a channel name.")
				: new ChannelQuery.ByName(text);
	}

	private static boolean looksLikeUrl(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		return !lower.contains(" ") && (lower.startsWith("http://") || lower.startsWith("https://")
				|| lower.startsWith("www.") || lower.contains(".com/") || lower.contains("youtu.be/")
				|| lower.matches("^[a-z0-9-]+(\\.[a-z0-9-]+)+(/.*)?$"));
	}

	private ChannelQuery parseUrl(String text) {
		URI uri;
		try {
			uri = new URI(text.contains("://") ? text : "https://" + text);
		} catch (URISyntaxException e) {
			return new ChannelQuery.Unsupported("I couldn't read that link.");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

		if (host.equals("youtu.be")) {
			String id = firstSegment(uri.getPath());
			return VIDEO_ID.matcher(id).matches()
					? new ChannelQuery.ByVideoId(id)
					: new ChannelQuery.Unsupported("I couldn't find a video in that link.");
		}
		if (!YOUTUBE_HOSTS.contains(host)) {
			return new ChannelQuery.Unsupported(UNSUPPORTED_SITE);
		}

		String path = decode(uri.getPath() == null ? "" : uri.getPath());
		String[] segments = path.replaceAll("^/+", "").split("/");
		String first = segments.length > 0 ? segments[0] : "";
		String second = segments.length > 1 ? segments[1] : "";

		if (first.startsWith("@")) {
			return HANDLE.matcher(first).matches()
					? new ChannelQuery.ByHandle(first)
					: new ChannelQuery.Unsupported("That doesn't look like a valid channel link.");
		}
		switch (first) {
			case "channel":
				return CHANNEL_ID.matcher(second).matches()
						? new ChannelQuery.ById(second)
						: new ChannelQuery.Unsupported("That channel link looks incomplete.");
			case "c":
			case "user":
				return second.isBlank()
						? new ChannelQuery.Unsupported("That channel link looks incomplete.")
						: new ChannelQuery.ByName(second);
			case "shorts":
			case "embed":
			case "live":
				return VIDEO_ID.matcher(second).matches()
						? new ChannelQuery.ByVideoId(second)
						: new ChannelQuery.Unsupported("I couldn't find a video in that link.");
			case "watch": {
				String videoId = queryParam(uri.getRawQuery(), "v");
				return videoId != null && VIDEO_ID.matcher(videoId).matches()
						? new ChannelQuery.ByVideoId(videoId)
						: new ChannelQuery.Unsupported("I couldn't find a video in that link.");
			}
			case "playlist":
				return new ChannelQuery.Unsupported("Playlist links aren't supported. Send the channel's link instead.");
			default:
				return new ChannelQuery.Unsupported("I couldn't tell which channel that link points to.");
		}
	}

	private static String firstSegment(String path) {
		String[] segments = (path == null ? "" : path).replaceAll("^/+", "").split("/");
		return segments.length > 0 ? segments[0] : "";
	}

	private static String queryParam(String rawQuery, String name) {
		if (rawQuery == null) {
			return null;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0 && pair.substring(0, eq).equals(name)) {
				return decode(pair.substring(eq + 1));
			}
		}
		return null;
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
