package com.aram.backend.analytics;

import com.aram.backend.catalog.FirestoreOps;

import java.time.Instant;
import java.util.List;

/** Read access to the Firestore collections the export needs; faked in tests. */
public interface ExportData {

	List<FirestoreOps.Doc> all(String collection);

	/** Documents whose timestamp {@code field} is at or after {@code atLeast}. */
	List<FirestoreOps.Doc> atLeast(String collection, String field, Instant atLeast);
}
