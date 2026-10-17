package com.aram.backend.catalog;

import com.aram.backend.exception.InvalidRequestException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The parent's approvals for a child (spec 3.13). Approving needs the shared {@link EligibilityPolicy} to agree;
 * removing a channel marks the entry REVOKED rather than deleting it, so the decision stays on record.
 */
@Service
public class ChildCatalogService {

	private final FamilyRepository family;
	private final CatalogRepository catalog;
	private final EligibilityPolicy policy;
	private final Clock clock;

	public ChildCatalogService(FamilyRepository family, CatalogRepository catalog, EligibilityPolicy policy, Clock clock) {
		this.family = family;
		this.catalog = catalog;
		this.policy = policy;
		this.clock = clock;
	}

	public boolean isApproved(String childId, String sourceId) {
		return family.findEntry(childId, sourceId).filter(entry -> entry.status() == Statuses.Entry.APPROVED).isPresent();
	}

	/** Approved sources that may still be played, in the order they were approved. */
	public List<ContentSource> approvedSources(String childId) {
		List<ChildCatalogEntry> entries = new ArrayList<>(family.findEntries(childId, Statuses.Entry.APPROVED));
		entries.sort(Comparator.comparing(ChildCatalogEntry::approvedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(ChildCatalogEntry::sourceId));
		// One batched read for all of the child's sources, then the same "still playable" check as before.
		List<ContentSource> sources = new ArrayList<>();
		for (ContentSource source : catalog.findSourcesByIds(entries.stream().map(ChildCatalogEntry::sourceId).toList())) {
			if (policy.stillPlayable(source)) {
				sources.add(source);
			}
		}
		return sources;
	}

	public List<String> approvedSourceIds(String childId) {
		return approvedSources(childId).stream().map(ContentSource::id).toList();
	}

	/** Approves sources for the child, refusing any the shared policy does not allow for this child's age. */
	public void approve(String parentId, String childId, int childAgeMonths, List<String> sourceIds) {
		Instant now = clock.instant();
		for (String sourceId : new LinkedHashSet<>(sourceIds)) {
			ContentSource source = requireKnownSource(sourceId);
			Optional<ChildCatalogEntry> existing = family.findEntry(childId, sourceId);
			if (existing.filter(entry -> entry.status() == Statuses.Entry.APPROVED).isPresent()) {
				continue;
			}
			EligibilityPolicy.Decision decision = policy.forChild(source, childAgeMonths);
			if (!decision.eligible()) {
				throw new InvalidRequestException(decision.reason());
			}
			family.saveEntry(new ChildCatalogEntry(ChildCatalogEntry.idFor(childId, sourceId), parentId, childId, sourceId,
					null, Statuses.Entry.APPROVED, parentId, now, existing.map(ChildCatalogEntry::restrictions).orElse(List.of()),
					existing.map(ChildCatalogEntry::createdAt).orElse(now), now));
		}
	}

	public void revoke(String childId, String sourceId) {
		Instant now = clock.instant();
		family.findEntry(childId, sourceId).filter(entry -> entry.status() == Statuses.Entry.APPROVED)
				.ifPresent(entry -> family.saveEntry(new ChildCatalogEntry(entry.id(), entry.parentId(), entry.childId(),
						entry.sourceId(), entry.contentItemId(), Statuses.Entry.REVOKED, entry.approvedBy(), entry.approvedAt(),
						entry.restrictions(), entry.createdAt(), now)));
	}

	/** Makes the approved set exactly {@code desired}: revokes what is missing, approves what is new. */
	public void setApproved(String parentId, String childId, int childAgeMonths, List<String> desired) {
		Set<String> wanted = new LinkedHashSet<>(desired);
		Set<String> current = new LinkedHashSet<>(approvedSourceIds(childId));
		// Check every newly requested source first, so a refusal leaves the approved list untouched.
		for (String sourceId : wanted) {
			ContentSource source = requireKnownSource(sourceId);
			if (!current.contains(sourceId)) {
				EligibilityPolicy.Decision decision = policy.forChild(source, childAgeMonths);
				if (!decision.eligible()) {
					throw new InvalidRequestException(decision.reason());
				}
			}
		}
		for (String sourceId : current) {
			if (!wanted.contains(sourceId)) {
				revoke(childId, sourceId);
			}
		}
		approve(parentId, childId, childAgeMonths, List.copyOf(wanted));
	}

	private ContentSource requireKnownSource(String sourceId) {
		if (!ContentSource.isValidId(sourceId)) {
			throw new InvalidRequestException("Unknown channel: '" + sourceId + "'");
		}
		return catalog.findSource(sourceId)
				.orElseThrow(() -> new InvalidRequestException("Unknown channel: '" + sourceId + "'"));
	}
}
