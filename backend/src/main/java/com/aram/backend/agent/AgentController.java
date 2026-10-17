package com.aram.backend.agent;

import com.aram.backend.auth.AuthFilter;
import com.aram.backend.auth.AuthenticatedParent;
import com.aram.backend.model.ParentProfile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/me/agent")
public class AgentController {

	public record MessageRequest(String message, List<AgentService.ChatMessage> history, String sessionId) {
	}

	public record ChannelIdsRequest(List<String> channelIds) {
	}

	private final AgentService agentService;
	private final StagedChannelService stagedChannels;

	public AgentController(AgentService agentService, StagedChannelService stagedChannels) {
		this.agentService = agentService;
		this.stagedChannels = stagedChannels;
	}

	@PostMapping("/messages")
	public AgentService.AgentReply send(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody MessageRequest request) {
		return agentService.respond(parent.uid(), request.message(), request.history(), request.sessionId());
	}

	/** The parent's latest conversation (text and last cards) so the page can resume it; 204 when there is none or sessions are off. */
	@GetMapping("/session")
	public ResponseEntity<AgentSessionStore.Transcript> session(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return agentService.latestConversation(parent.uid()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	/** "New chat": deletes the stored conversation (404 for an id that is not the signed-in parent's). */
	@DeleteMapping("/session/{sessionId}")
	public ResponseEntity<Void> deleteSession(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@PathVariable String sessionId) {
		agentService.deleteConversation(parent.uid(), sessionId);
		return ResponseEntity.noContent().build();
	}

	/** Channels waiting for the parent's Save, so the Changes panel survives a page reload. */
	@GetMapping("/staged")
	public List<StagedChannelService.StagedChannel> staged(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent) {
		return stagedChannels.listStaged(parent.uid());
	}

	@PostMapping("/save")
	public ParentProfile save(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody ChannelIdsRequest request) {
		return stagedChannels.save(parent.uid(), request.channelIds());
	}

	@PostMapping("/discard")
	public List<StagedChannelService.StagedChannel> discard(@RequestAttribute(AuthFilter.ATTRIBUTE) AuthenticatedParent parent,
			@RequestBody ChannelIdsRequest request) {
		stagedChannels.discard(parent.uid(), request.channelIds());
		return stagedChannels.listStaged(parent.uid());
	}
}
