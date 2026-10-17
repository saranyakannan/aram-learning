package com.aram.backend.service;

import com.aram.backend.exception.InvalidRequestException;

import java.util.regex.Pattern;

/** Parent ids become Firestore document ids, so they must stay a strict, slash-free shape. */
public final class ParentIds {

	private static final Pattern PARENT_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private ParentIds() {
	}

	public static void requireValid(String parentId) {
		if (parentId == null || !PARENT_ID.matcher(parentId).matches()) {
			throw new InvalidRequestException("Invalid parent id");
		}
	}
}
