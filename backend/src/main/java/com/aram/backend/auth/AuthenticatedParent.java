package com.aram.backend.auth;

/** The signed-in parent, taken from a verified Firebase ID token (never from anything the client claims). */
public record AuthenticatedParent(String uid, String name, String email) {
}
