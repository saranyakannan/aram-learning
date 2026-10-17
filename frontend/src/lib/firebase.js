import { initializeApp } from 'firebase/app';
import { connectAuthEmulator, getAuth } from 'firebase/auth';

// These values identify the Firebase project; they are not secrets (they ship in every browser bundle).
const config = {
	apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
	authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
	projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
	appId: import.meta.env.VITE_FIREBASE_APP_ID,
};

export const isFirebaseConfigured = Boolean(config.apiKey && config.projectId);

export const auth = isFirebaseConfigured ? getAuth(initializeApp(config)) : null;

// Local testing: point sign-in at the Firebase Auth emulator (e.g. http://localhost:9099) instead of Google.
const emulatorUrl = import.meta.env.VITE_FIREBASE_AUTH_EMULATOR;
if (auth && emulatorUrl) {
	connectAuthEmulator(auth, emulatorUrl, { disableWarnings: true });
}
