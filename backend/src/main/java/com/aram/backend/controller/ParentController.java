package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.model.ChannelRecommendation;
import com.aram.backend.model.ParentProfile;
import com.aram.backend.model.TagInfo;
import com.aram.backend.service.ParentProfileService;
import com.aram.backend.service.RecommendationService;
import com.aram.backend.service.TagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Everything under {@code /api/me} acts on the signed-in parent; the id comes from the verified token only. */
@RestController
public class ParentController {

	public record BasicsRequest(String parentName, String handle, String childName, String birthYearMonth) {
	}

	public record TagsRequest(List<String> tags) {
	}

	public record ChannelsRequest(List<String> channelIds) {
	}

	private final TagService tagService;
	private final ParentProfileService profileService;
	private final RecommendationService recommendationService;

	public ParentController(TagService tagService, ParentProfileService profileService,
			RecommendationService recommendationService) {
		this.tagService = tagService;
		this.profileService = profileService;
		this.recommendationService = recommendationService;
	}

	/** Interest tags offered during onboarding. Public: it is the same taxonomy for everyone. */
	@GetMapping("/api/tags")
	public List<TagInfo> getTags() {
		return tagService.getTags();
	}

	@GetMapping("/api/me/profile")
	public ParentProfile getProfile(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return profileService.getProfile(parent.uid());
	}

	@PutMapping("/api/me/profile/basics")
	public ParentProfile saveBasics(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody BasicsRequest request) {
		return profileService.saveBasics(parent.uid(), request.parentName(), request.handle(), request.childName(),
				request.birthYearMonth());
	}

	@PutMapping("/api/me/profile/tags")
	public ParentProfile saveTags(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody TagsRequest request) {
		return profileService.saveTags(parent.uid(), request.tags());
	}

	@PutMapping("/api/me/profile/channels")
	public ParentProfile saveChannels(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody ChannelsRequest request) {
		return profileService.saveChannels(parent.uid(), request.channelIds());
	}

	/** Suggestions for the signed-in parent's child, decided entirely from what the server has stored. */
	@GetMapping("/api/me/recommendations")
	public List<ChannelRecommendation> getRecommendations(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return recommendationService.recommend(parent.uid());
	}
}
