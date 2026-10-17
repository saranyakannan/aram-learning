package com.aram.backend.agent;

import java.util.List;

/** One entry in a conversation as seen by the model. */
public sealed interface Turn {

	record UserText(String text) implements Turn {
	}

	record ModelText(String text) implements Turn {
	}

	record ModelStepTurn(ModelStep step) implements Turn {
	}

	record ToolResults(List<ToolResult> results) implements Turn {
	}
}
