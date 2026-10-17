package com.aram.backend.analytics;

/** Coarse age bands for analytics: the exact birth month never leaves Firestore. */
public final class AgeBands {

	private AgeBands() {
	}

	public static String of(int ageMonths) {
		if (ageMonths < 0) {
			return "unknown";
		}
		int years = ageMonths / 12;
		if (years < 1) {
			return "<1y";
		}
		if (years < 5) {
			return years + "-" + (years + 1) + "y";
		}
		if (years == 5) {
			return "5-6y";
		}
		if (years < 8) {
			return "6-8y";
		}
		if (years < 12) {
			return "8-12y";
		}
		return years < 18 ? "12-18y" : "18y+";
	}
}
