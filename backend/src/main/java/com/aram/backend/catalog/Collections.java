package com.aram.backend.catalog;

/** Firestore collection names from the data-model spec. */
public final class Collections {

	public static final String PARENTS = "parents";
	public static final String CHILDREN = "children";
	public static final String CATEGORIES = "categories";
	public static final String TAGS = "tags";
	public static final String PARENT_INTERESTS = "parent_interests";
	public static final String CONTENT_SOURCES = "content_sources";
	public static final String CONTENT_ASSESSMENTS = "content_assessments";
	public static final String CONTENT_SUBMISSIONS = "content_submissions";
	public static final String CHILD_CATALOG_ENTRIES = "child_catalog_entries";
	/** Precomputed recommendation signals per source (neighbours, cluster); written by the ML job only (decision record 0008). */
	public static final String SOURCE_SIGNALS = "source_signals";
	/** Precomputed similarity of each curated book to catalog sources; written by the ML job only (decision record 0017). */
	public static final String BOOK_SIGNALS = "book_signals";

	private Collections() {
	}
}
