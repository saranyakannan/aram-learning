package com.aram.backend.catalog;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Firestore access for a family: the parent, the child, the child's interests and the child's catalog entries. */
@Repository
public class FamilyRepository {

	private final FirestoreOps firestore;

	public FamilyRepository(FirestoreOps firestore) {
		this.firestore = firestore;
	}

	public Optional<ParentAccount> findParent(String parentId) {
		return firestore.get(Collections.PARENTS, parentId).map(doc -> ParentAccount.fromMap(doc.id(), doc.data()));
	}

	public void saveParent(ParentAccount parent) {
		firestore.set(Collections.PARENTS, parent.id(), parent.toMap());
	}

	public Optional<ChildProfile> findChild(String childId) {
		return firestore.get(Collections.CHILDREN, childId).map(doc -> ChildProfile.fromMap(doc.id(), doc.data()));
	}

	public void saveChild(ChildProfile child) {
		firestore.set(Collections.CHILDREN, child.id(), child.toMap());
	}

	/** The tag ids this child's parent selected. */
	public List<String> findInterestTagIds(String childId) {
		return firestore.where(Collections.PARENT_INTERESTS, Map.of("childId", childId)).stream()
				.map(doc -> FirestoreOps.string(doc.data(), "tagId"))
				.filter(java.util.Objects::nonNull)
				.sorted()
				.toList();
	}

	/** Makes the stored selections exactly {@code tagIds}: adds new ones, removes the rest (id {@code childId_tagId}). */
	public void replaceInterests(String parentId, String childId, List<String> tagIds, Instant now) {
		Set<String> wanted = Set.copyOf(tagIds);
		Map<String, FirestoreOps.Doc> existing = firestore.where(Collections.PARENT_INTERESTS, Map.of("childId", childId))
				.stream().collect(Collectors.toMap(FirestoreOps.Doc::id, doc -> doc));

		for (String tagId : tagIds) {
			String id = childId + "_" + tagId;
			FirestoreOps.Doc current = existing.get(id);
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("parentId", parentId);
			map.put("childId", childId);
			map.put("tagId", tagId);
			map.put("preferenceSource", "PARENT_SELECTED");
			map.put("weight", 1.0);
			map.put("createdAt", current != null ? current.data().get("createdAt") : FirestoreOps.timestamp(now));
			map.put("updatedAt", FirestoreOps.timestamp(now));
			firestore.set(Collections.PARENT_INTERESTS, id, map);
		}
		existing.forEach((id, doc) -> {
			if (!wanted.contains(FirestoreOps.string(doc.data(), "tagId"))) {
				firestore.delete(Collections.PARENT_INTERESTS, id);
			}
		});
	}

	public Optional<ChildCatalogEntry> findEntry(String childId, String sourceId) {
		return firestore.get(Collections.CHILD_CATALOG_ENTRIES, ChildCatalogEntry.idFor(childId, sourceId))
				.map(doc -> ChildCatalogEntry.fromMap(doc.id(), doc.data()));
	}

	public void saveEntry(ChildCatalogEntry entry) {
		firestore.set(Collections.CHILD_CATALOG_ENTRIES, entry.id(), entry.toMap());
	}

	public List<ChildCatalogEntry> findEntries(String childId, Statuses.Entry status) {
		Map<String, Object> filters = new LinkedHashMap<>();
		filters.put("childId", childId);
		filters.put("status", status.name());
		return firestore.where(Collections.CHILD_CATALOG_ENTRIES, filters).stream()
				.map(doc -> ChildCatalogEntry.fromMap(doc.id(), doc.data())).toList();
	}
}
