package com.aram.backend.agent;

import java.util.List;

/** Chat model with tool calling. Implemented by {@link GeminiClient}; tests use fakes. */
public interface AgentModel {

	ModelStep nextStep(String systemInstruction, List<Turn> turns, List<ToolSpec> tools);
}
