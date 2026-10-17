import {
	createUserWithEmailAndPassword,
	GoogleAuthProvider,
	onAuthStateChanged,
	signInWithEmailAndPassword,
	signInWithPopup,
	signOut as firebaseSignOut,
} from 'firebase/auth';
import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { auth, isFirebaseConfigured } from './firebase';

const AuthContext = createContext(null);

/**
 * The signed-in parent. `user` is undefined while the saved session is still being checked,
 * null when nobody is signed in, and a Firebase user otherwise.
 */
export function AuthProvider({ children }) {
	const [user, setUser] = useState(isFirebaseConfigured ? undefined : null);

	useEffect(() => {
		if (!auth) return undefined;
		return onAuthStateChanged(auth, setUser);
	}, []);

	const value = useMemo(
		() => ({
			user,
			isConfigured: isFirebaseConfigured,
			signIn: () => signInWithPopup(auth, new GoogleAuthProvider()),
			signInWithEmail: (email, password) => signInWithEmailAndPassword(auth, email, password),
			signUpWithEmail: (email, password) => createUserWithEmailAndPassword(auth, email, password),
			signOut: () => firebaseSignOut(auth),
		}),
		[user],
	);

	return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
	const context = useContext(AuthContext);
	if (!context) {
		throw new Error('useAuth must be used within an AuthProvider');
	}
	return context;
}
