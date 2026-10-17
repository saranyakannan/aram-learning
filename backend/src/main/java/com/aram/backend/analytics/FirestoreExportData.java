package com.aram.backend.analytics;

import com.aram.backend.catalog.FirestoreOps;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "aram.export", name = "enabled", havingValue = "true")
public class FirestoreExportData implements ExportData {

	private final FirestoreOps firestore;

	public FirestoreExportData(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	@Override
	public List<FirestoreOps.Doc> all(String collection) {
		return firestore.all(collection);
	}

	@Override
	public List<FirestoreOps.Doc> atLeast(String collection, String field, Instant atLeast) {
		return firestore.whereAtLeast(collection, field, atLeast);
	}
}
