package com.aram.backend.service;

import com.aram.backend.catalog.AgeMath;
import com.aram.backend.catalog.ChildCatalogService;
import com.aram.backend.catalog.ChildProfile;
import com.aram.backend.catalog.FamilyRepository;
import com.aram.backend.catalog.ParentAccount;
import com.aram.backend.exception.InvalidRequestException;
import com.aram.backend.model.ParentProfile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** A parent's account, their child, the child's interests and the channels approved for the child. */
@Service
public class ParentProfileService {

	/** The child an action applies to, with the age used for every eligibility decision. */
	public record ChildContext(String parentId, String childId, String childName, int ageMonths) {
	}

	private static final Pattern HANDLE = Pattern.compile("^[a-z0-9._]{3,30}$");

	private final FamilyRepository family;
	private final TagService tagService;
	private final ChildCatalogService childCatalog;
	private final Clock clock;

	public ParentProfileService(FamilyRepository family, TagService tagService, ChildCatalogService childCatalog, Clock clock) {
		this.family = family;
		this.tagService = tagService;
		this.childCatalog = childCatalog;
		this.clock = clock;
	}

	public ParentProfile getProfile(String parentId) {
		ParentIds.requireValid(parentId);
		ParentProfile profile = new ParentProfile();
		family.findParent(parentId).ifPresent(parent -> {
			profile.setParentName(parent.displayName());
			profile.setHandle(parent.handle());
		});
		family.findChild(ChildProfile.firstChildId(parentId)).ifPresent(child -> {
			int ageMonths = AgeMath.ageMonths(YearMonth.parse(child.birthYearMonth()), clock);
			profile.setChildName(child.displayName());
			profile.setBirthYearMonth(child.birthYearMonth());
			profile.setChildAgeMonths(ageMonths);
			profile.setChildAge(AgeMath.wholeYears(ageMonths));
			profile.setTags(family.findInterestTagIds(child.id()));
			profile.setChannelIds(childCatalog.approvedSourceIds(child.id()));
		});
		return profile;
	}

	/** The child this parent is setting up; refuses until onboarding step 1 has been completed. */
	public ChildContext requireChild(String parentId) {
		ParentIds.requireValid(parentId);
		ChildProfile child = family.findChild(ChildProfile.firstChildId(parentId))
				.orElseThrow(() -> new InvalidRequestException("Please finish setting up your child's profile first."));
		return new ChildContext(parentId, child.id(), child.displayName(),
				AgeMath.ageMonths(YearMonth.parse(child.birthYearMonth()), clock));
	}

	public Optional<ChildContext> findChild(String parentId) {
		try {
			return Optional.of(requireChild(parentId));
		} catch (InvalidRequestException e) {
			return Optional.empty();
		}
	}

	/** Onboarding step 1: who the parent and child are. Keeps any interests and approvals already chosen. */
	public ParentProfile saveBasics(String parentId, String parentName, String handle, String childName, String birthYearMonth) {
		ParentIds.requireValid(parentId);
		String cleanParent = requireText(parentName, "Parent name", 60);
		String cleanChild = requireText(childName, "Child name", 40);
		if (handle == null || !HANDLE.matcher(handle).matches()) {
			throw new InvalidRequestException("Handle must be 3-30 lowercase letters, numbers, dots or underscores");
		}
		YearMonth birth = AgeMath.parseBirth(birthYearMonth, clock);

		Instant now = clock.instant();
		Instant parentCreated = family.findParent(parentId).map(ParentAccount::createdAt).orElse(now);
		family.saveParent(new ParentAccount(parentId, cleanParent, handle, "en", "ACTIVE", false, parentCreated, now));

		String childId = ChildProfile.firstChildId(parentId);
		Instant childCreated = family.findChild(childId).map(ChildProfile::createdAt).orElse(now);
		family.saveChild(new ChildProfile(childId, parentId, cleanChild, birth.toString(), "en", "ACTIVE", childCreated, now));
		return getProfile(parentId);
	}

	/** Onboarding step 2: interests. Tags only shape what is recommended; they never approve a channel. */
	public ParentProfile saveTags(String parentId, List<String> tags) {
		List<String> validTags = tagService.validate(tags);
		ChildContext child = requireChild(parentId);
		family.replaceInterests(parentId, child.childId(), validTags, clock.instant());
		return getProfile(parentId);
	}

	/** Replaces the set of channels approved for the child (adds are checked by the shared eligibility policy). */
	public ParentProfile saveChannels(String parentId, List<String> sourceIds) {
		if (sourceIds == null) {
			throw new InvalidRequestException("channelIds must be provided");
		}
		ChildContext child = requireChild(parentId);
		childCatalog.setApproved(parentId, child.childId(), child.ageMonths(), sourceIds);
		return getProfile(parentId);
	}

	private static String requireText(String value, String label, int maxLength) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty() || trimmed.length() > maxLength || trimmed.chars().anyMatch(Character::isISOControl)) {
			throw new InvalidRequestException(label + " is required (up to " + maxLength + " characters)");
		}
		return trimmed;
	}
}
