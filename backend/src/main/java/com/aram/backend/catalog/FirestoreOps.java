package com.aram.backend.catalog;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.aram.backend.exception.ProfileStorageException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Thin blocking wrapper over Firestore that turns every failure into a {@link ProfileStorageException}.
 * Repositories build their documents as plain maps with Firestore-native types (Timestamps, lists), which keeps
 * the stored shape exactly what the data-model spec describes and keeps the repositories easy to fake in tests.
 */
@Component
public class FirestoreOps {

	/** One stored document: its id and its fields. */
	public record Doc(String id, Map<String, Object> data) {
	}

	private final Firestore firestore;

	public FirestoreOps(Firestore firestore) {
		this.firestore = firestore;
	}

	public Optional<Doc> get(String collection, String id) {
		DocumentSnapshot snapshot = await(firestore.collection(collection).document(id).get());
		return snapshot.exists() ? Optional.of(new Doc(snapshot.getId(), snapshot.getData())) : Optional.empty();
	}

	/** Several documents of one collection in a single round trip; ids that do not exist are simply missing from the result. */
	public List<Doc> getAll(String collection, java.util.Collection<String> ids) {
		List<com.google.cloud.firestore.DocumentReference> references = ids.stream().distinct()
				.map(id -> firestore.collection(collection).document(id)).toList();
		if (references.isEmpty()) {
			return List.of();
		}
		List<Doc> docs = new ArrayList<>();
		for (DocumentSnapshot snapshot : await(firestore.getAll(references.toArray(new com.google.cloud.firestore.DocumentReference[0])))) {
			if (snapshot.exists()) {
				docs.add(new Doc(snapshot.getId(), snapshot.getData()));
			}
		}
		return docs;
	}

	/** Creates or fully replaces a document. */
	public void set(String collection, String id, Map<String, Object> data) {
		await(firestore.collection(collection).document(id).set(data));
	}

	/** Creates a document only if the id is free; returns false when it already exists. */
	public boolean create(String collection, String id, Map<String, Object> data) {
		try {
			firestore.collection(collection).document(id).create(data).get();
			return true;
		} catch (ExecutionException e) {
			if (e.getCause() instanceof AlreadyExistsException) {
				return false;
			}
			throw new ProfileStorageException("Unable to write to " + collection, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProfileStorageException("Interrupted while writing to " + collection, e);
		}
	}

	/** Adds a document under a generated id and returns that id. */
	public String add(String collection, Map<String, Object> data) {
		return await(firestore.collection(collection).add(data)).getId();
	}

	/** Changes only the given fields of an existing document. */
	public void update(String collection, String id, Map<String, Object> fields) {
		await(firestore.collection(collection).document(id).update(fields));
	}

	public void delete(String collection, String id) {
		await(firestore.collection(collection).document(id).delete());
	}

	/** Every document whose fields equal all of the given values (equality filters need no composite index). */
	public List<Doc> where(String collection, Map<String, Object> equalTo) {
		Query query = firestore.collection(collection);
		for (Map.Entry<String, Object> filter : equalTo.entrySet()) {
			query = query.whereEqualTo(filter.getKey(), filter.getValue());
		}
		List<Doc> docs = new ArrayList<>();
		for (QueryDocumentSnapshot snapshot : await(query.get()).getDocuments()) {
			docs.add(new Doc(snapshot.getId(), snapshot.getData()));
		}
		return docs;
	}

	/** Every document in a collection (only for small collections, such as the catalog and taxonomy). */
	public List<Doc> all(String collection) {
		List<Doc> docs = new ArrayList<>();
		for (QueryDocumentSnapshot snapshot : await(firestore.collection(collection).get()).getDocuments()) {
			docs.add(new Doc(snapshot.getId(), snapshot.getData()));
		}
		return docs;
	}

	/** Documents whose timestamp field is at or after the given instant (inclusive, so a re-read never skips a tie). */
	public List<Doc> whereAtLeast(String collection, String field, Instant atLeast) {
		Query query = firestore.collection(collection).whereGreaterThanOrEqualTo(field, timestamp(atLeast));
		List<Doc> docs = new ArrayList<>();
		for (QueryDocumentSnapshot snapshot : await(query.get()).getDocuments()) {
			docs.add(new Doc(snapshot.getId(), snapshot.getData()));
		}
		return docs;
	}

	private static <T> T await(ApiFuture<T> future) {
		try {
			return future.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ProfileStorageException("Interrupted while using storage", e);
		} catch (ExecutionException e) {
			throw new ProfileStorageException("Storage request failed", e);
		}
	}

	// ----- value helpers shared by the record mappers -----

	public static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
	}

	public static Instant instant(Object value) {
		if (value instanceof Timestamp timestamp) {
			return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
		}
		return null;
	}

	public static String string(Map<String, Object> data, String key) {
		Object value = data.get(key);
		return value instanceof String text ? text : null;
	}

	public static List<String> strings(Map<String, Object> data, String key) {
		Object value = data.get(key);
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof String text) {
				result.add(text);
			}
		}
		return List.copyOf(result);
	}

	public static Integer integer(Map<String, Object> data, String key) {
		return data.get(key) instanceof Number number ? number.intValue() : null;
	}

	public static Long longValue(Map<String, Object> data, String key) {
		return data.get(key) instanceof Number number ? number.longValue() : null;
	}

	public static boolean bool(Map<String, Object> data, String key) {
		return Boolean.TRUE.equals(data.get(key));
	}
}
