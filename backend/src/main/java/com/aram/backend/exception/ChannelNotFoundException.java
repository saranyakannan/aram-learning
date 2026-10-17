package com.aram.backend.exception;

public class ChannelNotFoundException extends RuntimeException {
	public ChannelNotFoundException(String channelId) {
		super("No curated channel found with id '" + channelId + "'");
	}
}
