package com.aram.backend.catalog;

import com.aram.backend.model.Category;
import com.aram.backend.model.Tag;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory stand-ins for the Firestore repositories, so tests can run whole flows without a database. */
public final class InMemoryStores {

	private InMemoryStores() {
	}

	public static class Catalog extends CatalogRepository {
		public final Map<String, ContentSource> sources = new LinkedHashMap<>();
		public final Map<String, ContentAssessment> assessments = new LinkedHashMap<>();
		public final Map<String, Category> categories = new LinkedHashMap<>();
		public final Map<String, Tag> tags = new LinkedHashMap<>();
		private final AtomicInteger counter = new AtomicInteger();

		public Catalog() {
			super(null);
		}

		@Override
		public Optional<ContentSource> findSource(String sourceId) {
			return Optional.ofNullable(sources.get(sourceId));
		}

		@Override
		public boolean createSource(ContentSource source) {
			return sources.putIfAbsent(source.id(), source) == null;
		}

		@Override
		public void saveSource(ContentSource source) {
			sources.put(source.id(), source);
		}

		@Override
		public List<ContentSource> findSourcesByIds(java.util.Collection<String> sourceIds) {
			return sourceIds.stream().map(sources::get).filter(java.util.Objects::nonNull).toList();
		}

		@Override
		public List<ContentSource> findAllSources() {
			return new ArrayList<>(sources.values());
		}

		@Override
		public List<ContentSource> findSources(Statuses.Catalog catalogStatus, Statuses.Assessment assessmentStatus) {
			return sources.values().stream()
					.filter(source -> source.catalogStatus() == catalogStatus && source.assessmentStatus() == assessmentStatus)
					.toList();
		}

		@Override
		public String addAssessment(ContentAssessment assessment) {
			String id = "assessment-" + counter.incrementAndGet();
			assessments.put(id, assessment.withId(id));
			return id;
		}

		@Override
		public Optional<ContentAssessment> findAssessment(String assessmentId) {
			return Optional.ofNullable(assessments.get(assessmentId));
		}

		@Override
		public void saveCategory(Category category, Instant now) {
			categories.put(category.id(), category);
		}

		@Override
		public void saveTag(Tag tag, Instant now) {
			tags.put(tag.id(), tag);
		}
	}

	public static class Family extends FamilyRepository {
		public final Map<String, ParentAccount> parents = new LinkedHashMap<>();
		public final Map<String, ChildProfile> children = new LinkedHashMap<>();
		public final Map<String, List<String>> interests = new LinkedHashMap<>();
		public final Map<String, ChildCatalogEntry> entries = new LinkedHashMap<>();

		public Family() {
			super(null);
		}

		@Override
		public Optional<ParentAccount> findParent(String parentId) {
			return Optional.ofNullable(parents.get(parentId));
		}

		@Override
		public void saveParent(ParentAccount parent) {
			parents.put(parent.id(), parent);
		}

		@Override
		public Optional<ChildProfile> findChild(String childId) {
			return Optional.ofNullable(children.get(childId));
		}

		@Override
		public void saveChild(ChildProfile child) {
			children.put(child.id(), child);
		}

		@Override
		public List<String> findInterestTagIds(String childId) {
			return interests.getOrDefault(childId, List.of());
		}

		@Override
		public void replaceInterests(String parentId, String childId, List<String> tagIds, Instant now) {
			interests.put(childId, List.copyOf(tagIds));
		}

		@Override
		public Optional<ChildCatalogEntry> findEntry(String childId, String sourceId) {
			return Optional.ofNullable(entries.get(ChildCatalogEntry.idFor(childId, sourceId)));
		}

		@Override
		public void saveEntry(ChildCatalogEntry entry) {
			entries.put(entry.id(), entry);
		}

		@Override
		public List<ChildCatalogEntry> findEntries(String childId, Statuses.Entry status) {
			return new ArrayList<>(entries.values().stream()
					.filter(entry -> entry.childId().equals(childId) && entry.status() == status).toList());
		}
	}

	public static class Submissions extends SubmissionRepository {
		public final Map<String, ContentSubmission> all = new LinkedHashMap<>();

		public Submissions() {
			super(null);
		}

		@Override
		public void save(ContentSubmission submission) {
			all.put(submission.id(), submission);
		}

		@Override
		public Optional<ContentSubmission> find(String submissionId) {
			return Optional.ofNullable(all.get(submissionId));
		}

		@Override
		public List<ContentSubmission> findByParent(String parentId, Statuses.Submission status) {
			return all.values().stream()
					.filter(submission -> submission.submittedByParentId().equals(parentId) && submission.status() == status)
					.toList();
		}
	}

	/** A source with sensible defaults; {@code assessmentStatus} and the age range are what tests usually vary. */
	public static ContentSource source(String youtubeId, String name, Statuses.Assessment assessment, int minMonths,
			int maxMonths, String... tags) {
		Instant now = Instant.parse("2026-10-14T12:00:00Z");
		return new ContentSource(ContentSource.idForYoutubeChannel(youtubeId), name, ContentSource.normalize(name),
				ContentSource.YOUTUBE_CHANNEL, ContentSource.YOUTUBE, "https://www.youtube.com/channel/" + youtubeId,
				youtubeId, name + " description", List.of(tags), List.of(ContentSource.PREPOPULATED), Statuses.Catalog.ACTIVE,
				assessment, null, minMonths, maxMonths, "@" + name.replace(" ", ""), "https://t/" + youtubeId + ".jpg", 10L,
				now, now, now);
	}

	public static String youtubeId(char letter) {
		return "UC" + String.valueOf(letter).repeat(22);
	}

	public static YearMonth monthsAgo(java.time.Clock clock, int months) {
		return YearMonth.now(clock).minusMonths(months);
	}
}
