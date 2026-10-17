package com.aram.backend.catalog.builder;

import com.aram.backend.catalog.ContentSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, before any model call is spent, which candidates are worth assessing. Nothing here approves anything: a candidate is
 * dropped, held back for an explicit human decision, or kept (possibly flagged as a look-alike).
 */
public final class CandidateFilter {

	public record Held(Candidate candidate, String reason) {
	}

	/** {@code lookalikes} maps a kept candidate's source id to the name of the existing source it resembles. */
	public record Filtered(List<Candidate> keep, List<Held> held, Map<String, Integer> dropped, Map<String, String> lookalikes) {
	}

	/** Names that announce a regional language ("Hindi Rhymes", "Stories in Tamil"); whole words only, names only (descriptions mention languages in passing). */
	private static final java.util.regex.Pattern REGIONAL_LANGUAGE = java.util.regex.Pattern.compile(
			"\\b(hindi|hinglish|tamil|telugu|kannada|malayalam|marathi|bengali|bangla|gujarati|punjabi|urdu|odia|bhojpuri)\\b",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private static final List<String> DEFAULT_KID_GENRES = com.aram.backend.podcast.KidGenres.APPLE;

	private CandidateFilter() {
	}

	public static Filtered filter(List<Candidate> found, Collection<ContentSource> existing, long minSubscribers, Set<String> includeHeld) {
		return filter(found, existing, minSubscribers, includeHeld, DEFAULT_KID_GENRES);
	}

	/** {@code kidGenres} are the Apple Podcasts genres accepted as children's content. */
	public static Filtered filter(List<Candidate> found, Collection<ContentSource> existing, long minSubscribers, Set<String> includeHeld,
			Collection<String> kidGenres) {
		Set<String> knownIds = new HashSet<>();
		Map<String, String> namesToExisting = new HashMap<>();
		for (ContentSource source : existing) {
			knownIds.add(source.id());
			if (source.normalizedName() != null && !source.normalizedName().isBlank()) {
				namesToExisting.put(squash(source.normalizedName()), source.name());
			}
		}
		Set<String> seen = new HashSet<>();
		Map<String, String> namesInThisRun = new HashMap<>();
		List<Candidate> keep = new ArrayList<>();
		List<Held> held = new ArrayList<>();
		Map<String, Integer> dropped = new LinkedHashMap<>();
		Map<String, String> lookalikes = new LinkedHashMap<>();

		for (Candidate candidate : found) {
			if (knownIds.contains(candidate.sourceId())) {
				count(dropped, "already in the catalog");
			} else if (!seen.add(candidate.sourceId())) {
				count(dropped, "duplicate in this run");
			} else if (candidate.kind() == Candidate.Kind.PODCAST && candidate.explicit()) {
				count(dropped, "marked explicit");
			} else if (candidate.kind() == Candidate.Kind.PODCAST && (candidate.genre() == null || !kidGenres.contains(candidate.genre()))) {
				count(dropped, "not a children's genre");
			} else if (!LanguageHint.mostlyLatin(candidate.name())) {
				count(dropped, "not in the Latin script (English first)");
			} else if (isRegionalLanguage(candidate)) {
				count(dropped, "regional-language content (English first)");
			} else if (candidate.kind() == Candidate.Kind.YOUTUBE && candidate.name() != null && candidate.name().strip().toLowerCase(java.util.Locale.ROOT).endsWith("- topic")) {
				count(dropped, "auto-generated YouTube \"Topic\" channel");
			} else if (candidate.kind() == Candidate.Kind.YOUTUBE && candidate.subscribers() != null && candidate.subscribers() < minSubscribers) {
				count(dropped, "too small");
			} else if (candidate.kind() == Candidate.Kind.YOUTUBE && Boolean.FALSE.equals(candidate.madeForKids())
					&& !includeHeld.contains(candidate.youtube().channelId())) {
				held.add(new Held(candidate, "YouTube says this channel is not made for kids"));
			} else {
				keep.add(candidate);
				String key = squash(candidate.name());
				String same = namesToExisting.get(key);
				if (same == null) {
					same = namesInThisRun.get(key);
				}
				if (same != null) {
					lookalikes.put(candidate.sourceId(), same);
				}
				namesInThisRun.putIfAbsent(key, candidate.name());
			}
		}
		return new Filtered(interleave(keep), List.copyOf(held), dropped, lookalikes);
	}

	/** Alternates YouTube channels and podcasts (each keeps its own order) so a capped run assesses a mix of both. */
	static List<Candidate> interleave(List<Candidate> candidates) {
		List<Candidate> youtube = candidates.stream().filter(c -> c.kind() == Candidate.Kind.YOUTUBE).toList();
		List<Candidate> podcasts = candidates.stream().filter(c -> c.kind() == Candidate.Kind.PODCAST).toList();
		List<Candidate> mixed = new ArrayList<>();
		for (int i = 0; i < Math.max(youtube.size(), podcasts.size()); i++) {
			if (i < youtube.size()) {
				mixed.add(youtube.get(i));
			}
			if (i < podcasts.size()) {
				mixed.add(podcasts.get(i));
			}
		}
		return List.copyOf(mixed);
	}

	/** A name that says Hindi/Tamil/..., or a channel whose declared YouTube language is not English. */
	static boolean isRegionalLanguage(Candidate candidate) {
		if (candidate.name() != null && REGIONAL_LANGUAGE.matcher(candidate.name()).find()) {
			return true;
		}
		String declared = candidate.youtube() == null ? null : candidate.youtube().language();
		return declared != null && !declared.isBlank() && !declared.toLowerCase(java.util.Locale.ROOT).startsWith("en");
	}

	private static void count(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}

	/** Names are compared without case, punctuation or spaces, so "Bluey - Official" and "bluey official" collide. */
	static String squash(String name) {
		return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}
}
