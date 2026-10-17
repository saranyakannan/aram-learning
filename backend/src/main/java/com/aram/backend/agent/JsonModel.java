package com.aram.backend.agent;

import java.util.Map;

/** Model call that must answer with JSON matching a JSON Schema. */
public interface JsonModel {

	/** The raw JSON text plus the model that actually produced it (it may be a fallback). */
	record JsonResponse(String json, String model) {
	}

	/** Callers must still validate the JSON. */
	JsonResponse generateJson(String systemInstruction, String userPrompt, Map<String, Object> jsonSchema);
}
