package com.aram.backend.podcast;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns what a parent typed for a podcast (an Apple Podcasts link, an id or a name) into something to look up. */
@Component
public class PodcastLinkParser {

	/** Exactly one of {@code appleId}, {@code name} or {@code error} is set. */
	public record Query(Long appleId, String name, String error) {
	}

	static final int MAX_NAME_LENGTH = 100;
	private static final Pattern APPLE_ID = Pattern.compile("/id(\\d{3,15})(?:[/?#]|$)");
	private static final Pattern BARE_ID = Pattern.compile("^\\d{5,15}$");

	public Query parse(String input) {
		String text = input == null ? "" : input.strip();
		if (text.isEmpty()) {
			return error("Please tell me which podcast to add.");
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (!lower.contains(" ") && (lower.startsWith("http://") || lower.startsWith("https://") || lower.contains("."))) {
			return parseUrl(text);
		}
		if (BARE_ID.matcher(text).matches()) {
			return new Query(Long.parseLong(text), null, null);
		}
		return text.length() > MAX_NAME_LENGTH ? error("That is too long to be a podcast name.") : new Query(null, text, null);
	}

	private Query parseUrl(String text) {
		URI uri;
		try {
			uri = new URI(text.contains("://") ? text : "https://" + text);
		} catch (URISyntaxException e) {
			return error("I couldn't read that link.");
		}
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (host.endsWith("podcasts.apple.com") || host.endsWith("itunes.apple.com")) {
			Matcher match = APPLE_ID.matcher(uri.getPath() + (uri.getQuery() == null ? "" : "?" + uri.getQuery()));
			Matcher fromQuery = Pattern.compile("[?&]id=(\\d{3,15})").matcher(uri.toString());
			if (match.find()) {
				return new Query(Long.parseLong(match.group(1)), null, null);
			}
			if (fromQuery.find()) {
				return new Query(Long.parseLong(fromQuery.group(1)), null, null);
			}
			return error("I couldn't find the podcast in that Apple Podcasts link.");
		}
		if (host.endsWith("spotify.com")) {
			return error("Spotify shows can't be added. Send an Apple Podcasts link, or just the podcast's name.");
		}
		return error("I can add a podcast from an Apple Podcasts link or by its name.");
	}

	private static Query error(String message) {
		return new Query(null, null, message);
	}
}
