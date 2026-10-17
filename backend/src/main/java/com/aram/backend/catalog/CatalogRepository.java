package com.aram.backend.catalog;

import com.aram.backend.model.Category;
import com.aram.backend.model.Tag;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Firestore access for the shared catalog: taxonomy, canonical sources and their append-only assessments.
 *
 * <p>Sources are small, shared by every family and change rarely, but every page used to re-read them one document at a
 * time. Reads now go through a short-lived in-memory copy (about a minute, dropped on any write made by this instance) and
 * the misses are fetched in one batch. Another instance (or a job) can be up to that long behind a change, which is fine
 * for browsing; approvals and writes always check Firestore through the same path within the same minute.
 */
@Repository
public class CatalogRepository {

	static final long TTL_NANOS = 60L * 1_000_000_000L;

	private record Timed<T>(T value, long loadedAtNanos) {
		boolean fresh() {
			return System.nanoTime() - loadedAtNanos < TTL_NANOS;
		}
	}

	private final FirestoreOps firestore;
	private final Map<String, Timed<ContentSource>> sourceCache = new ConcurrentHashMap<>();
	private final Map<String, Timed<List<ContentSource>>> queryCache = new ConcurrentHashMap<>();

	public CatalogRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	private void forget(String sourceId) {
		sourceCache.remove(sourceId);
		queryCache.clear();
	}

	public Optional<ContentSource> findSource(String sourceId) {
		Timed<ContentSource> cached = sourceCache.get(sourceId);
		if (cached != null && cached.fresh()) {
			return Optional.of(cached.value());
		}
		Optional<ContentSource> loaded = firestore.get(Collections.CONTENT_SOURCES, sourceId)
				.map(doc -> ContentSource.fromMap(doc.id(), doc.data()));
		loaded.ifPresent(source -> sourceCache.put(sourceId, new Timed<>(source, System.nanoTime())));
		return loaded;
	}

	/** The known sources among the given ids, in the order asked; unknown ids are left out. One round trip for everything not cached. */
	public List<ContentSource> findSourcesByIds(Collection<String> sourceIds) {
		Map<String, ContentSource> found = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (String id : sourceIds) {
			Timed<ContentSource> cached = sourceCache.get(id);
			if (cached != null && cached.fresh()) {
				found.put(id, cached.value());
			} else if (!missing.contains(id)) {
				missing.add(id);
			}
		}
		for (FirestoreOps.Doc doc : firestore.getAll(Collections.CONTENT_SOURCES, missing)) {
			ContentSource source = ContentSource.fromMap(doc.id(), doc.data());
			sourceCache.put(doc.id(), new Timed<>(source, System.nanoTime()));
			found.put(doc.id(), source);
		}
		List<ContentSource> ordered = new ArrayList<>();
		for (String id : sourceIds) {
			ContentSource source = found.get(id);
			if (source != null) {
				ordered.add(source);
			}
		}
		return ordered;
	}

	/** Creates the source only if no source with that id exists; returns false when someone else got there first. */
	public boolean createSource(ContentSource source) {
		forget(source.id());
		return firestore.create(Collections.CONTENT_SOURCES, source.id(), source.toMap());
	}

	public void saveSource(ContentSource source) {
		firestore.set(Collections.CONTENT_SOURCES, source.id(), source.toMap());
		forget(source.id());
	}

	public List<ContentSource> findAllSources() {
		return firestore.all(Collections.CONTENT_SOURCES).stream().map(doc -> ContentSource.fromMap(doc.id(), doc.data())).toList();
	}

	public List<ContentSource> findSources(Statuses.Catalog catalogStatus, Statuses.Assessment assessmentStatus) {
		String key = catalogStatus.name() + "|" + assessmentStatus.name();
		Timed<List<ContentSource>> cached = queryCache.get(key);
		if (cached != null && cached.fresh()) {
			return cached.value();
		}
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("catalogStatus", catalogStatus.name());
		filters.put("assessmentStatus", assessmentStatus.name());
		List<ContentSource> loaded = firestore.where(Collections.CONTENT_SOURCES, filters).stream()
				.map(doc -> ContentSource.fromMap(doc.id(), doc.data())).toList();
		queryCache.put(key, new Timed<>(loaded, System.nanoTime()));
		return loaded;
	}

	public String addAssessment(ContentAssessment assessment) {
		return firestore.add(Collections.CONTENT_ASSESSMENTS, assessment.toMap());
	}

	public Optional<ContentAssessment> findAssessment(String assessmentId) {
		return firestore.get(Collections.CONTENT_ASSESSMENTS, assessmentId)
				.map(doc -> ContentAssessment.fromMap(doc.id(), doc.data()));
	}

	public void saveCategory(Category category, Instant now) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", category.name());
		map.put("description", category.description());
		map.put("icon", category.icon());
		map.put("sortOrder", category.sortOrder());
		map.put("isActive", true);
		map.put("createdAt", FirestoreOps.timestamp(now));
		firestore.set(Collections.CATEGORIES, category.id(), map);
	}

	public void saveTag(Tag tag, Instant now) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", tag.label());
		map.put("normalizedName", tag.id());
		map.put("categoryId", tag.categoryId());
		map.put("description", tag.description());
		map.put("icon", tag.icon());
		map.put("minAgeMonths", tag.minAgeMonths());
		map.put("maxAgeMonths", tag.maxAgeMonths());
		map.put("isActive", true);
		map.put("createdAt", FirestoreOps.timestamp(now));
		firestore.set(Collections.TAGS, tag.id(), map);
	}
}
