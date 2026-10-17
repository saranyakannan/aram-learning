package com.aram.backend.agent;

import java.util.Map;

public record ToolResult(String callId, String name, Map<String, Object> response) {
}
