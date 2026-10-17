import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import {
	getParentProfile,
	saveParentBasics,
	saveParentChannels,
	saveParentTags,
} from '../api/aramApi';
import { useAuth } from './AuthContext';

const ParentContext = createContext(null);

/**
 * Holds the signed-in parent's profile for both the parent portal and the child space.
 * status: 'loading' | 'ready' | 'error'
 */
export function ParentProvider({ children }) {
	const { user } = useAuth();
	const uid = user?.uid ?? null;
	// Keyed by uid so signing out or switching accounts starts from a clean slate.
	return (
		<ParentLoader key={uid ?? 'signed-out'} uid={uid}>
			{children}
		</ParentLoader>
	);
}

function ParentLoader({ uid, children }) {
	const [profile, setProfile] = useState(null);
	const [status, setStatus] = useState('loading');
	const [error, setError] = useState(null);
	const [attempt, setAttempt] = useState(0);

	useEffect(() => {
		if (!uid) return undefined;
		let cancelled = false;
		getParentProfile()
			.then((loaded) => {
				if (cancelled) return;
				setProfile(loaded);
				setStatus('ready');
			})
			.catch((failure) => {
				console.error('Could not load the parent profile', failure);
				if (cancelled) return;
				setError(failure);
				setStatus('error');
			});
		return () => {
			cancelled = true;
		};
	}, [uid, attempt]);

	const reload = useCallback(() => {
		setError(null);
		setStatus('loading');
		setAttempt((count) => count + 1);
	}, []);

	const apply = useCallback(async (request) => {
		const saved = await request;
		setProfile(saved);
		return saved;
	}, []);

	const value = useMemo(
		() => ({
			profile,
			status,
			error,
			reload,
			saveBasics: (basics) => apply(saveParentBasics(basics)),
			saveTags: (tags) => apply(saveParentTags(tags)),
			saveChannels: (channelIds) => apply(saveParentChannels(channelIds)),
			replaceProfile: setProfile,
		}),
		[profile, status, error, reload, apply],
	);

	return <ParentContext.Provider value={value}>{children}</ParentContext.Provider>;
}

export function useParent() {
	const context = useContext(ParentContext);
	if (!context) {
		throw new Error('useParent must be used within a ParentProvider');
	}
	return context;
}

/** Where a parent should land: finish onboarding first, then the channel manager. */
export function onboardingPath(profile) {
	if (!profile?.childName) return '/parent/welcome';
	if (!profile.tags?.length) return '/parent/interests';
	return '/parent/channels';
}
