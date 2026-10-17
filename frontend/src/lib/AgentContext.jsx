import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
	deleteAgentSession,
	discardStagedChannels,
	getAgentSession,
	getStagedChannels,
	getTags,
	saveStagedChannels,
	sendAgentMessage,
} from '../api/aramApi';
import { useAuth } from './AuthContext';
import { useParent } from './ParentContext';

const MAX_HISTORY_SENT = 10;
const GENERIC_ERROR = 'Something went wrong on my side. Please try again in a moment.';

const AgentContext = createContext(null);

// A pick is sent to the model as "I choose channel UC... (Name)" but shown as "I choose Name"; show it the same after a reload.
const PICK = /^I choose (?:channel|podcast) \S+ \((.*)\)$/;

function fromTranscript(transcript) {
	const messages = transcript.messages.map((message) => ({
		role: message.role,
		text: message.role === 'user' ? message.text.replace(PICK, 'I choose $1') : message.text,
		sent: message.text,
	}));
	const last = messages.findLastIndex((message) => message.role === 'assistant');
	if (last >= 0 && transcript.candidates?.length) {
		messages[last] = { ...messages[last], candidates: transcript.candidates };
	}
	return messages;
}

/**
 * The Aram Agent conversation and the channels it has staged, kept above the routes so the chat survives moving between
 * pages. The agent page and the corner chat button read it; "Add a new channel" on Manage Channels hands text to {@link open}.
 */
export function AgentProvider({ children }) {
	const { user } = useAuth();
	const { profile, status, replaceProfile } = useParent();
	const ready = Boolean(user) && status === 'ready' && Boolean(profile?.childName);

	const navigate = useNavigate();
	const [isOpen, setIsOpen] = useState(false);
	const [messages, setMessages] = useState([]);
	const [isSending, setIsSending] = useState(false);
	const [staged, setStaged] = useState([]);
	const [tagLabels, setTagLabels] = useState({});
	const [notice, setNotice] = useState(null);
	const [isSaving, setIsSaving] = useState(false);
	const [sessionId, setSessionId] = useState(null);

	useEffect(() => {
		if (!ready) return undefined;
		let cancelled = false;
		getStagedChannels()
			.then((loaded) => {
				if (!cancelled) setStaged(loaded);
			})
			.catch(() => {});
		// Resume the stored conversation (the server keeps it for a few days); nothing happens if there is none.
		getAgentSession()
			.then((transcript) => {
				if (cancelled || !transcript) return;
				setMessages((current) => (current.length ? current : fromTranscript(transcript)));
				setSessionId((current) => current ?? transcript.sessionId);
			})
			.catch(() => {});
		getTags()
			.then((tags) => {
				if (!cancelled) setTagLabels(Object.fromEntries(tags.map((tag) => [tag.id, tag.label])));
			})
			.catch(() => {});
		return () => {
			cancelled = true;
		};
	}, [ready]);

	// Only real exchanges go to the server; the greeting and error bubbles are UI-only.
	const history = useMemo(
		() =>
			messages
				.filter((message) => !message.greeting && (message.role === 'user' || message.role === 'assistant'))
				.map((message) => ({ role: message.role, text: message.sent ?? message.text }))
				.slice(-MAX_HISTORY_SENT),
		[messages],
	);

	const send = useCallback(
		async (text, shown = text) => {
			if (isSending) return;
			setNotice(null);
			setMessages((current) => [...current, { role: 'user', text: shown, sent: text }]);
			setIsSending(true);
			try {
				const reply = await sendAgentMessage(text, history, sessionId);
				setMessages((current) => [...current, { role: 'assistant', text: reply.reply, candidates: reply.candidates }]);
				setStaged(reply.staged);
				if (reply.sessionId) setSessionId(reply.sessionId);
			} catch (error) {
				// The stored conversation is gone (expired or deleted): the next message starts a fresh one.
				if (error.status === 404 && sessionId) setSessionId(null);
				setMessages((current) => [...current, { role: 'error', text: error.serverMessage ?? GENERIC_ERROR }]);
			} finally {
				setIsSending(false);
			}
		},
		[history, isSending, sessionId],
	);

	// "New chat": forget the conversation here and delete it on the server.
	const newChat = useCallback(() => {
		if (isSending) return;
		const old = sessionId;
		setMessages([]);
		setSessionId(null);
		setNotice(null);
		if (old) deleteAgentSession(old).catch(() => {});
	}, [isSending, sessionId]);

	// The agent lives on its own page: opening it goes there, sending along anything the parent already typed.
	const open = useCallback(
		(prefill) => {
			setIsOpen(false);
			navigate('/parent/agent');
			if (prefill) send(prefill);
		},
		[navigate, send],
	);

	const removeStaged = useCallback(async (channelId) => {
		try {
			setStaged(await discardStagedChannels([channelId]));
		} catch (error) {
			setNotice({ kind: 'error', text: error.serverMessage ?? GENERIC_ERROR });
		}
	}, []);

	const discardAll = useCallback(async () => {
		try {
			setStaged(await discardStagedChannels(staged.map((pending) => pending.id)));
			setNotice(null);
		} catch (error) {
			setNotice({ kind: 'error', text: error.serverMessage ?? GENERIC_ERROR });
		}
	}, [staged]);

	const save = useCallback(async () => {
		const names = staged.map((pending) => pending.facts.title);
		setIsSaving(true);
		setNotice(null);
		try {
			replaceProfile(await saveStagedChannels(staged.map((pending) => pending.id)));
			setStaged([]);
			setNotice({
				kind: 'success',
				text: `Saved! ${names.join(', ')} will now appear in ${profile.childName}'s Kid Space.`,
			});
		} catch (error) {
			setNotice({ kind: 'error', text: error.serverMessage ?? GENERIC_ERROR });
			getStagedChannels().then(setStaged).catch(() => {});
		} finally {
			setIsSaving(false);
		}
	}, [staged, replaceProfile, profile]);

	// What the chat shows: a greeting built from the profile, then the real exchanges.
	const firstName = profile?.parentName?.split(' ')[0];
	const conversation = useMemo(
		() => [
			{
				role: 'assistant',
				greeting: true,
				text: `Hi${firstName ? ` ${firstName}` : ''}! Chat to me with a YouTube link, an @handle, an Apple Podcasts link or a channel or podcast name and I'll check it for ${
					profile?.childName
				}, who is ${profile?.childAge}. Nothing is added until you press Save.`,
			},
			...messages,
		],
		[messages, firstName, profile],
	);

	const value = useMemo(
		() => ({
			ready, isOpen, setIsOpen, messages, conversation, isSending, staged, tagLabels, notice, isSaving,
			send, open, newChat, removeStaged, discardAll, save,
		}),
		[ready, isOpen, messages, conversation, isSending, staged, tagLabels, notice, isSaving, send, open, newChat, removeStaged, discardAll, save],
	);

	return <AgentContext.Provider value={value}>{children}</AgentContext.Provider>;
}

export function useAgent() {
	const context = useContext(AgentContext);
	if (!context) {
		throw new Error('useAgent must be used within an AgentProvider');
	}
	return context;
}
