import { auth } from '../lib/firebase';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

/** An API failure. `serverMessage` is the backend's own user-safe explanation when it sent one. */
export class ApiError extends Error {
	constructor(path, status, serverMessage) {
		super(`Request to ${path} failed with status ${status}`);
		this.status = status;
		this.serverMessage = serverMessage;
	}
}

// The backend identifies the parent from this token; the browser never states who it is.
async function authHeader(path) {
	const token = await auth?.currentUser?.getIdToken();
	if (!token) {
		throw new ApiError(path, 401, 'Please sign in to continue.');
	}
	return { Authorization: `Bearer ${token}` };
}

async function requestJson(path, options = {}) {
	const headers = { ...options.headers, ...(path === '/api/tags' ? {} : await authHeader(path)) };
	const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });
	if (!response.ok) {
		let serverMessage;
		try {
			serverMessage = (await response.json()).message;
		} catch {
			serverMessage = undefined;
		}
		throw new ApiError(path, response.status, serverMessage);
	}
	if (response.status === 204) {
		return null;
	}
	return response.json();
}

function sendJson(method, path, body) {
	return requestJson(path, {
		method,
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify(body),
	});
}

function queryList(name, values) {
	return values.map((value) => `${name}=${encodeURIComponent(value)}`).join('&');
}

/** @returns {Promise<Array<object>>} channels approved for the child, then others to choose from; each with id, name, category, minAge, maxAge, tags, thumbnailUrl */
export function getChannels() {
	return requestJson('/api/channels');
}

export function getChannel(channelId) {
	return requestJson(`/api/channels/${encodeURIComponent(channelId)}`);
}

/**
 * @param {string[]} [channelIds] restrict to these channels; omit for everything this parent can see
 * @returns {Promise<Record<string, Array<object>>>} channelId -> latest videos
 */
export function getFeed(channelIds) {
	return requestJson(`/api/feed${channelIds ? `?${queryList('channelIds', channelIds)}` : ''}`);
}

/**
 * Latest episodes of every podcast approved for the child, keyed by show id.
 * @returns {Promise<Record<string, Array<{id: string, title: string, description: string, publishedAt?: string, audioUrl: string, audioType?: string, durationSeconds?: number, imageUrl?: string}>>>}
 */
export function getPodcastFeed() {
	return requestJson('/api/podcast-feed');
}

/**
 * The first strip of Kid Space: about ten popular videos and fresh podcast episodes from approved sources, re-sampled on every call.
 * @returns {Promise<Array<{type: 'VIDEO'|'EPISODE', id: string, sourceId: string, sourceName: string, title: string, thumbnailUrl?: string, publishedAt?: string, viewCount?: number, audioUrl?: string, durationSeconds?: number}>>} first rows are the best picks; the rest is what "Show more" reveals
 */
export function getTopPicks(size = 30) {
	return requestJson(`/api/me/top-picks?size=${size}`);
}

/** Latest episodes of one approved podcast (404 if the show is not approved for the child). */
export function getPodcastEpisodes(sourceId) {
	return requestJson(`/api/podcasts/${encodeURIComponent(sourceId)}/episodes`);
}

/** One video of an approved channel (any of its videos, not just the latest); 404 if it is not that channel's. */
export function getVideo(channelId, videoId) {
	return requestJson(`/api/channels/${encodeURIComponent(channelId)}/videos/${encodeURIComponent(videoId)}`);
}

export function getVideosForChannel(channelId) {
	return requestJson(`/api/channels/${encodeURIComponent(channelId)}/videos`);
}

/** @returns {Promise<Array<{id: string, label: string, icon: string, channelCount: number}>>} */
export function getTags() {
	return requestJson('/api/tags');
}

/** @returns {Promise<{parentName?: string, handle?: string, childName?: string, birthYearMonth?: string, childAge?: number, childAgeMonths?: number, tags: string[], channelIds: string[]}>} */
export function getParentProfile() {
	return requestJson('/api/me/profile');
}

export function saveParentBasics(basics) {
	return sendJson('PUT', '/api/me/profile/basics', basics);
}

export function saveParentTags(tags) {
	return sendJson('PUT', '/api/me/profile/tags', { tags });
}

export function saveParentChannels(channelIds) {
	return sendJson('PUT', '/api/me/profile/channels', { channelIds });
}

/**
 * Suggestions for this parent's child, decided by the server from the saved interests and the child's age.
 * @returns {Promise<Array<{channel: object, matchedTags: string[], ageFit: boolean}>>}
 */
export function getRecommendations() {
	return requestJson('/api/me/recommendations');
}

/**
 * @param {Array<{role: 'user'|'assistant', text: string}>} history earlier turns of this chat (used only when the server keeps no conversation)
 * @param {string} [sessionId] the server-side conversation to continue; omit to start a new one
 * @returns {Promise<{reply: string, candidates: Array<object>, staged: Array<object>, sessionId?: string}>}
 */
export function sendAgentMessage(message, history, sessionId) {
	return sendJson('POST', '/api/me/agent/messages', { message, history, sessionId });
}

/** @returns {Promise<{sessionId: string, messages: Array<{role: 'user'|'assistant', text: string}>, candidates: Array<object>}|null>} the latest stored conversation, or null */
export function getAgentSession() {
	return requestJson('/api/me/agent/session');
}

/** "New chat": deletes the stored conversation. */
export function deleteAgentSession(sessionId) {
	return requestJson(`/api/me/agent/session/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
}

/** @returns {Promise<Array<object>>} channels the agent vetted that are waiting for the parent's Save */
export function getStagedChannels() {
	return requestJson('/api/me/agent/staged');
}

/** @returns {Promise<object>} the updated parent profile */
export function saveStagedChannels(channelIds) {
	return sendJson('POST', '/api/me/agent/save', { channelIds });
}

/** @returns {Promise<Array<object>>} what is still staged */
export function discardStagedChannels(channelIds) {
	return sendJson('POST', '/api/me/agent/discard', { channelIds });
}
