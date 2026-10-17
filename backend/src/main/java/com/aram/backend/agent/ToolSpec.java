package com.aram.backend.agent;

import java.util.Map;

/** A function the model may call. {@code parametersSchema} is a JSON Schema object. */
public record ToolSpec(String name, String description, Map<String, Object> parametersSchema) {
}
