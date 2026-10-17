package com.aram.backend.podcast;

import com.aram.backend.exception.PodcastLookupException;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/**
 * Reads the first episodes of a podcast RSS feed without loading the whole thing (some feeds hold a thousand episodes).
 * Feeds are untrusted: DTDs and external entities are off, text is stripped of markup and shortened, only http(s) audio
 * links are kept, and episodes the publisher marks explicit are dropped. A feed cut off by the size cap still yields
 * the episodes read before the cut.
 */
public final class PodcastFeedParser {

	/** What the feed says about the show, plus the episodes that are safe to list. */
	public record Feed(String title, String description, boolean explicit, String imageUrl, List<PodcastEpisode> episodes, String language) {
		public Feed(String title, String description, boolean explicit, String imageUrl, List<PodcastEpisode> episodes) {
			this(title, description, explicit, imageUrl, episodes, null);
		}
	}

	private static final String ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd";
	private static final int MAX_TITLE = 200;
	private static final int MAX_DESCRIPTION = 500;
	private static final int MAX_URL = 2000;

	private PodcastFeedParser() {
	}

	public static Feed parse(byte[] xml, int maxEpisodes) {
		XMLInputFactory factory = XMLInputFactory.newFactory();
		factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
		factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
		factory.setProperty(XMLInputFactory.IS_COALESCING, true);

		String showTitle = null;
		String showDescription = null;
		String showImage = null;
		String showLanguage = null;
		boolean showExplicit = false;
		List<PodcastEpisode> episodes = new ArrayList<>();
		Episode current = null;
		Deque<String> path = new ArrayDeque<>();

		try {
			XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
			while (reader.hasNext() && episodes.size() < maxEpisodes) {
				int event = reader.next();
				if (event == XMLStreamConstants.END_ELEMENT) {
					String ended = path.isEmpty() ? "" : path.pop();
					if (ended.equals("item") && current != null) {
						PodcastEpisode episode = current.build();
						if (episode != null) {
							episodes.add(episode);
						}
						current = null;
					}
					continue;
				}
				if (event != XMLStreamConstants.START_ELEMENT) {
					continue;
				}
				String name = reader.getLocalName();
				boolean itunes = ITUNES.equals(reader.getNamespaceURI());
				String parent = path.peek();
				boolean inItem = current != null && "item".equals(parent);
				boolean inChannel = "channel".equals(parent);

				if (name.equals("item") && inChannel) {
					current = new Episode();
					path.push(name);
				} else if (inItem) {
					handleItemElement(reader, current, name, itunes);
					// handleItemElement consumed the whole element when it read its text.
					if (reader.getEventType() == XMLStreamConstants.END_ELEMENT) {
						continue;
					}
					path.push(name);
				} else if (inChannel && !itunes && name.equals("title")) {
					showTitle = clean(readText(reader), MAX_TITLE);
				} else if (inChannel && !itunes && name.equals("description")) {
					showDescription = clean(readText(reader), MAX_DESCRIPTION);
				} else if (inChannel && !itunes && name.equals("language")) {
					showLanguage = clean(readText(reader), 20);
				} else if (inChannel && itunes && name.equals("explicit")) {
					showExplicit = isTrue(readText(reader));
				} else if (inChannel && itunes && name.equals("image")) {
					showImage = httpUrlOrNull(reader.getAttributeValue(null, "href"));
					path.push(name);
				} else {
					path.push(name);
				}
			}
		} catch (XMLStreamException | RuntimeException e) {
			if (showTitle == null && episodes.isEmpty()) {
				throw new PodcastLookupException("That feed couldn't be read", e);
			}
		}
		if (showTitle == null && episodes.isEmpty()) {
			throw new PodcastLookupException("That feed has no episodes I can read");
		}
		return new Feed(showTitle, showDescription, showExplicit, showImage, List.copyOf(episodes), showLanguage);
	}

	private static void handleItemElement(XMLStreamReader reader, Episode episode, String name, boolean itunes)
			throws XMLStreamException {
		switch (name) {
			case "title" -> {
				if (!itunes) {
					episode.title = clean(readText(reader), MAX_TITLE);
				}
			}
			case "description" -> episode.description = clean(readText(reader), MAX_DESCRIPTION);
			case "summary" -> {
				String summary = clean(readText(reader), MAX_DESCRIPTION);
				if (episode.description == null) {
					episode.description = summary;
				}
			}
			case "guid" -> episode.guid = readText(reader).strip();
			case "pubDate" -> episode.publishedAt = parseDate(readText(reader));
			case "duration" -> episode.durationSeconds = parseDuration(readText(reader));
			case "explicit" -> {
				if (itunes) {
					episode.explicit = isTrue(readText(reader));
				}
			}
			case "enclosure" -> {
				episode.audioUrl = httpUrlOrNull(reader.getAttributeValue(null, "url"));
				episode.audioType = reader.getAttributeValue(null, "type");
			}
			case "image" -> {
				if (itunes) {
					episode.imageUrl = httpUrlOrNull(reader.getAttributeValue(null, "href"));
				}
			}
			default -> {
			}
		}
	}

	private static final class Episode {
		String title;
		String description;
		String guid;
		String publishedAt;
		String audioUrl;
		String audioType;
		Integer durationSeconds;
		boolean explicit;
		String imageUrl;

		PodcastEpisode build() {
			if (audioUrl == null || title == null || title.isBlank() || explicit) {
				return null;
			}
			return new PodcastEpisode(idFor(guid != null && !guid.isBlank() ? guid : audioUrl), title,
					description == null ? "" : description, publishedAt, audioUrl, audioType, durationSeconds, imageUrl);
		}
	}

	/**
	 * Reads the text of the current element, including text inside any child tags, and leaves the reader on its end tag.
	 * (The standard getElementText fails on a stray child tag, which would throw away a whole feed over one title.)
	 */
	private static String readText(XMLStreamReader reader) throws XMLStreamException {
		StringBuilder text = new StringBuilder();
		int depth = 1;
		while (depth > 0 && reader.hasNext()) {
			int event = reader.next();
			if (event == XMLStreamConstants.START_ELEMENT) {
				depth++;
			} else if (event == XMLStreamConstants.END_ELEMENT) {
				depth--;
			} else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
					|| event == XMLStreamConstants.SPACE) {
				if (text.length() < 20_000) {
					text.append(reader.getText());
				}
			}
		}
		return text.toString();
	}

	static String idFor(String key) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	static boolean isTrue(String value) {
		String text = value == null ? "" : value.strip().toLowerCase();
		return text.equals("yes") || text.equals("true") || text.equals("explicit");
	}

	static String httpUrlOrNull(String value) {
		if (value == null || value.isBlank() || value.length() > MAX_URL) {
			return null;
		}
		try {
			URI uri = URI.create(value.strip());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
			return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null ? uri.toString() : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	static String parseDate(String value) {
		try {
			return java.time.ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Accepts {@code 1:02:03}, {@code 12:30} and plain seconds. */
	static Integer parseDuration(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			String[] parts = value.strip().split(":");
			int seconds = 0;
			for (String part : parts) {
				seconds = seconds * 60 + (int) Double.parseDouble(part);
			}
			return parts.length <= 3 && seconds >= 0 ? seconds : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Feed text may contain HTML: drop the tags, decode the common entities and shorten it. */
	static String clean(String value, int max) {
		if (value == null) {
			return null;
		}
		String text = value.replaceAll("<[^>]*>", " ")
				.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
				.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("\\s+", " ").strip();
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}
}
