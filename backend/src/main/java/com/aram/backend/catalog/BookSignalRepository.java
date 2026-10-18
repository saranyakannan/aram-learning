package com.aram.backend.catalog;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Firestore access for {@code book_signals}: read by the book recommender, written only by the ML job. */
@Repository
public class BookSignalRepository {

	private final FirestoreOps firestore;

	public BookSignalRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	/** The signals of several books in one round trip, keyed by book id (books without a signal are missing). */
	public Map<String, BookSignal> findByIds(Collection<String> bookIds) {
		Map<String, BookSignal> found = new LinkedHashMap<>();
		for (FirestoreOps.Doc doc : firestore.getAll(Collections.BOOK_SIGNALS, bookIds)) {
			found.put(doc.id(), BookSignal.fromMap(doc.id(), doc.data()));
		}
		return found;
	}

	public List<BookSignal> findAll() {
		return firestore.all(Collections.BOOK_SIGNALS).stream().map(doc -> BookSignal.fromMap(doc.id(), doc.data())).toList();
	}

	public void save(BookSignal signal) {
		firestore.set(Collections.BOOK_SIGNALS, signal.bookId(), signal.toMap());
	}

	public void delete(String bookId) {
		firestore.delete(Collections.BOOK_SIGNALS, bookId);
	}
}
