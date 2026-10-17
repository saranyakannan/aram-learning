package com.aram.backend.catalog;

import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Firestore access for {@code content_submissions}. */
@Repository
public class SubmissionRepository {

	private final FirestoreOps firestore;

	public SubmissionRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	public void save(ContentSubmission submission) {
		firestore.set(Collections.CONTENT_SUBMISSIONS, submission.id(), submission.toMap());
	}

	public Optional<ContentSubmission> find(String submissionId) {
		return firestore.get(Collections.CONTENT_SUBMISSIONS, submissionId)
				.map(doc -> ContentSubmission.fromMap(doc.id(), doc.data()));
	}

	public List<ContentSubmission> findByParent(String parentId, Statuses.Submission status) {
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("submittedByParentId", parentId);
		filters.put("status", status.name());
		return firestore.where(Collections.CONTENT_SUBMISSIONS, filters).stream()
				.map(doc -> ContentSubmission.fromMap(doc.id(), doc.data())).toList();
	}
}
