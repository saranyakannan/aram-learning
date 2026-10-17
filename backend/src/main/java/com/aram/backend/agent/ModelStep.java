package com.aram.backend.agent;

import java.util.List;

/**
 * One model response: either final text or tool calls (or both). {@code raw} is the provider's
 * own message object; it is passed back untouched on the next step because some models require
 * their opaque reasoning signatures to accompany tool calls.
 */
public record ModelStep(String text, List<ToolCall> toolCalls, Object raw) {

	public boolean hasToolCalls() {
		return !toolCalls.isEmpty();
	}
}
