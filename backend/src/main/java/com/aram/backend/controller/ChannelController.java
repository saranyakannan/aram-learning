package com.aram.backend.controller;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.model.Channel;
import com.aram.backend.service.ParentChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/channels")
public class ChannelController {

	private final ParentChannelService parentChannelService;

	public ChannelController(ParentChannelService parentChannelService) {
		this.parentChannelService = parentChannelService;
	}

	/** Channels approved for the signed-in parent's child, then the others they can choose from. */
	@GetMapping
	public List<Channel> getChannels(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return parentChannelService.visibleChannels(parent.uid());
	}

	@GetMapping("/{channelId}")
	public Channel getChannel(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@PathVariable String channelId) {
		return parentChannelService.getVisibleChannel(parent.uid(), channelId);
	}
}
