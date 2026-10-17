package com.aram.backend.catalog.builder;

/** A cheap check for text written mostly in a non-Latin script (Tamil, Telugu, Hindi, Arabic...), so it can be set aside before any model call. */
public final class LanguageHint {

	private LanguageHint() {
	}

	/** True when at least 70% of the letters are Latin; text with no letters at all gives no evidence, so it passes. */
	public static boolean mostlyLatin(String text) {
		if (text == null) {
			return true;
		}
		int letters = 0;
		int latin = 0;
		for (int i = 0; i < text.length(); ) {
			int codePoint = text.codePointAt(i);
			i += Character.charCount(codePoint);
			if (Character.isLetter(codePoint)) {
				letters++;
				if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) {
					latin++;
				}
			}
		}
		return letters == 0 || latin * 100 >= letters * 70;
	}
}
