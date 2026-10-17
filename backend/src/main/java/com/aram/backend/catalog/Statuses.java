package com.aram.backend.catalog;

/** The status vocabularies from the data-model spec, stored in Firestore by name. */
public final class Statuses {

	private Statuses() {
	}

	/** Spec 3.8 assessment statuses; the same values are kept on the source as its latest-assessment pointer. */
	public enum Assessment { PENDING, ASSESSED, REVIEW_REQUIRED, APPROVED_FOR_RECOMMENDATION, REJECTED, STALE }

	/** Whether a source is part of the live catalog. UNDER_REVIEW sources exist for deduplication but are never offered. */
	public enum Catalog { ACTIVE, UNDER_REVIEW, RETIRED }

	/** Spec 3.9 processing states, plus COMPLETED and DISMISSED once the parent has acted. */
	public enum Submission {
		RECEIVED, RESOLVING, DUPLICATE_FOUND, AWAITING_EVIDENCE, ASSESSMENT_PENDING, REVIEW_REQUIRED, READY_FOR_PARENT,
		FAILED, COMPLETED, DISMISSED
	}

	/** Spec 3.13 child catalog statuses. */
	public enum Entry { SAVED_PENDING_APPROVAL, APPROVED, REJECTED, REVOKED }

	/** Who produced an assessment. */
	public enum Assessor { LLM, CURATED_SEED, HUMAN_REVIEW }

	public static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, value);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}
}
