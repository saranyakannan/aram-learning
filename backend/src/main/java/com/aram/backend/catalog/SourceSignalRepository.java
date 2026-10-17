package com.aram.backend.catalog;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Firestore access for {@code source_signals}: read by the recommender, written only by the ML job. */
@Repository
public class SourceSignalRepository {

	private final FirestoreOps firestore;

	public SourceSignalRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	public Optional<SourceSignal> find(String sourceId) {
		return firestore.get(Collections.SOURCE_SIGNALS, sourceId).map(doc -> SourceSignal.fromMap(doc.id(), doc.data()));
	}

	/** The signals of several sources in one round trip, keyed by source id (sources without signals are missing). */
	public java.util.Map<String, SourceSignal> findByIds(java.util.Collection<String> sourceIds) {
		java.util.Map<String, SourceSignal> found = new java.util.LinkedHashMap<>();
		for (FirestoreOps.Doc doc : firestore.getAll(Collections.SOURCE_SIGNALS, sourceIds)) {
			found.put(doc.id(), SourceSignal.fromMap(doc.id(), doc.data()));
		}
		return found;
	}

	public List<SourceSignal> findAll() {
		return firestore.all(Collections.SOURCE_SIGNALS).stream().map(doc -> SourceSignal.fromMap(doc.id(), doc.data())).toList();
	}

	public void save(SourceSignal signal) {
		firestore.set(Collections.SOURCE_SIGNALS, signal.sourceId(), signal.toMap());
	}

	public void delete(String sourceId) {
		firestore.delete(Collections.SOURCE_SIGNALS, sourceId);
	}
}
