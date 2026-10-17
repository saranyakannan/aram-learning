package com.aram.backend.analytics;

/** One column of an analytics table. Arrays are {@code repeated}. The set of columns IS the privacy allowlist. */
public record Column(String name, Type type, boolean repeated) {

	public enum Type { STRING, INT64, FLOAT64, BOOL, DATE, TIMESTAMP }

	public static Column string(String name) {
		return new Column(name, Type.STRING, false);
	}

	public static Column strings(String name) {
		return new Column(name, Type.STRING, true);
	}

	public static Column integer(String name) {
		return new Column(name, Type.INT64, false);
	}

	public static Column decimal(String name) {
		return new Column(name, Type.FLOAT64, false);
	}

	public static Column bool(String name) {
		return new Column(name, Type.BOOL, false);
	}

	public static Column date(String name) {
		return new Column(name, Type.DATE, false);
	}

	public static Column timestamp(String name) {
		return new Column(name, Type.TIMESTAMP, false);
	}
}
