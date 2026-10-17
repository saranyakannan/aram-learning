package com.aram.backend.model;

import java.util.List;

/**
 * The outcome of the child-safety check for one channel and one child age. Only {@link #stageable()}
 * assessments can ever be offered to a parent for saving; every failure path produces a blocked one.
 */
public record SafetyAssessment(
		Status status,
		Verdict verdict,
		boolean ageAppropriate,
		Confidence confidence,
		List<String> concerns,
		String reason,
		Integer estimatedMinAge,
		Integer estimatedMaxAge,
		String category,
		List<String> tags,
		String model,
		long assessedAtEpochMilli
) {

	public enum Status { CHECKED, UNAVAILABLE }

	public enum Verdict { SAFE, NOT_CLEARLY_SAFE }

	public enum Confidence { HIGH, MEDIUM, LOW }

	/** Strict policy: only an explicit, confident SAFE for this child's age passes. */
	public boolean stageable() {
		return status == Status.CHECKED && verdict == Verdict.SAFE && ageAppropriate && confidence == Confidence.HIGH;
	}

	public static SafetyAssessment unavailable(String reason, String model, long now) {
		return new SafetyAssessment(Status.UNAVAILABLE, Verdict.NOT_CLEARLY_SAFE, false, Confidence.LOW,
				List.of(), reason, null, null, null, List.of(), model, now);
	}
}
