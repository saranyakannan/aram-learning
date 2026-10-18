import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/AuthContext';
import AramMark from '../components/AramMark';
import Loader from '../components/Loader';

const FRIENDLY_ERRORS = {
	'auth/popup-closed-by-user': 'The sign-in window was closed before it finished. Please try again.',
	'auth/cancelled-popup-request': 'Another sign-in window is already open.',
	'auth/popup-blocked': 'Your browser blocked the sign-in window. Allow pop-ups for this site and try again.',
	'auth/network-request-failed': "We couldn't reach Google. Check your connection and try again.",
	'auth/invalid-credential': 'That email and password do not match. Please check them and try again.',
	'auth/wrong-password': 'That email and password do not match. Please check them and try again.',
	'auth/user-not-found': 'That email and password do not match. Please check them and try again.',
	'auth/invalid-email': 'Please enter a valid email address.',
	'auth/email-already-in-use': 'There is already an account with that email. Try signing in instead.',
	'auth/weak-password': 'Please choose a password with at least 6 characters.',
	'auth/missing-password': 'Please enter your password.',
	'auth/too-many-requests': 'Too many attempts. Please wait a moment and try again.',
	'auth/user-disabled': 'This account has been disabled.',
	'auth/operation-not-allowed': 'Email sign-in is not switched on for this app yet.',
};

export default function SignInPage() {
	const { user, isConfigured, signIn, signInWithEmail, signUpWithEmail } = useAuth();
	const navigate = useNavigate();
	const [error, setError] = useState(null);
	const [isSigningIn, setIsSigningIn] = useState(false);
	const [mode, setMode] = useState('signin');
	const [email, setEmail] = useState('');
	const [password, setPassword] = useState('');

	// Signing in always lands in the Parent Space (onboarding first, then the library); Kid Space is one tap away from there.
	const destination = '/parent';

	if (user) {
		return <Navigate replace to={destination} />;
	}

	async function handleSignIn() {
		setIsSigningIn(true);
		setError(null);
		try {
			await signIn();
			navigate(destination, { replace: true });
		} catch (err) {
			setError(FRIENDLY_ERRORS[err.code] ?? "We couldn't sign you in. Please try again.");
			setIsSigningIn(false);
		}
	}

	async function handleEmailSubmit(event) {
		event.preventDefault();
		setIsSigningIn(true);
		setError(null);
		try {
			if (mode === 'signup') {
				await signUpWithEmail(email.trim(), password);
			} else {
				await signInWithEmail(email.trim(), password);
			}
			navigate(destination, { replace: true });
		} catch (err) {
			setError(FRIENDLY_ERRORS[err.code] ?? "We couldn't sign you in. Please try again.");
			setIsSigningIn(false);
		}
	}

	function switchMode() {
		setMode(mode === 'signin' ? 'signup' : 'signin');
		setError(null);
	}

	return (
		<div className="min-h-screen grid lg:grid-cols-2 bg-aram-bg text-white font-body antialiased selection:bg-aram-blue selection:text-white">
			<aside className="relative hidden lg:flex flex-col justify-center px-12 py-24 bg-gradient-to-br from-[#06213a] via-[#0a0a0a] to-black border-r border-aram-border">
				<div className="absolute left-12 top-12">
					<Brand />
				</div>
				<div className="flex flex-col gap-8 max-w-md lg:min-h-[560px]">
					<div className="flex flex-col gap-3">
						<h2 className="text-4xl font-bold tracking-tight leading-tight">A world of learning, thoughtfully chosen.</h2>
						<p className="text-base text-aram-muted">A calm, parent-led space for little minds to explore.</p>
					</div>
					<ul className="flex flex-col gap-5">
						{POINTS.map((point) => (
							<li key={point.title} className="flex gap-4">
								<span className="w-10 h-10 shrink-0 rounded-full bg-aram-blue/15 text-aram-blue flex items-center justify-center">
									<span className="material-symbols-outlined text-[22px]">{point.icon}</span>
								</span>
								<div>
									<p className="text-[15px] font-semibold">{point.title}</p>
									<p className="text-[13px] text-aram-muted">{point.body}</p>
								</div>
							</li>
						))}
					</ul>
				</div>
				<p className="absolute left-12 bottom-12 text-xs text-neutral-500">Handcrafted by a parent to another for child learning</p>
			</aside>

			<main className="flex flex-col items-center justify-center px-6 py-12">
				<div className="lg:hidden mb-10">
					<Brand />
				</div>
				<div className="w-full max-w-sm flex flex-col gap-6 lg:min-h-[560px]">
					<div className="flex flex-col gap-2">
						<h1 className="text-3xl font-bold tracking-tight">Welcome to Aram</h1>
						<p className="text-aram-muted text-sm">
							Sign in to set up your family&rsquo;s learning space. Only you, the parent, ever sign in.
						</p>
					</div>

					{isConfigured ? (
						<button
							className="w-full py-3 rounded-full bg-white hover:bg-neutral-200 disabled:opacity-50 text-black text-sm font-semibold transition-colors flex items-center justify-center gap-3"
							disabled={isSigningIn}
							type="button"
							onClick={handleSignIn}
						>
							{isSigningIn ? <Loader inline size="sm" label="Signing in..." /> : (<><GoogleMark />Continue with Google</>)}
						</button>
					) : (
						<p className="text-[13px] text-aram-danger bg-aram-surface border border-aram-border rounded-xl px-4 py-3">
							Sign-in isn&rsquo;t set up yet. Add the Firebase web settings (<code>VITE_FIREBASE_*</code>) to <code>frontend/.env</code> and restart the dev server.
						</p>
					)}

					{isConfigured && (
						<>
							<div className="flex items-center gap-3 text-xs text-neutral-500">
								<span className="flex-1 h-px bg-aram-border" />
								or use your email
								<span className="flex-1 h-px bg-aram-border" />
							</div>
							<form className="flex flex-col gap-3" onSubmit={handleEmailSubmit}>
								<input
									aria-label="Email"
									autoComplete="email"
									className="w-full px-4 py-3 rounded-xl bg-aram-surface border border-aram-border text-sm placeholder:text-neutral-500 focus:outline-none focus:border-aram-blue"
									name="email"
									placeholder="Email"
									required
									type="email"
									value={email}
									onChange={(event) => setEmail(event.target.value)}
								/>
								<input
									aria-label="Password"
									autoComplete={mode === 'signup' ? 'new-password' : 'current-password'}
									className="w-full px-4 py-3 rounded-xl bg-aram-surface border border-aram-border text-sm placeholder:text-neutral-500 focus:outline-none focus:border-aram-blue"
									minLength={mode === 'signup' ? 6 : undefined}
									name="password"
									placeholder={mode === 'signup' ? 'Choose a password (6+ characters)' : 'Password'}
									required
									type="password"
									value={password}
									onChange={(event) => setPassword(event.target.value)}
								/>
								<button
									className="w-full py-3 rounded-full bg-aram-blue hover:opacity-90 disabled:opacity-50 text-white text-sm font-semibold transition-opacity flex items-center justify-center"
									disabled={isSigningIn}
									type="submit"
								>
									{isSigningIn ? <Loader inline size="sm" label="Please wait..." /> : mode === 'signup' ? 'Create account' : 'Sign in'}
								</button>
								<button className="text-[13px] text-aram-blue hover:underline self-center" type="button" onClick={switchMode}>
									{mode === 'signup' ? 'Already have an account? Sign in' : 'New here? Create an account'}
								</button>
							</form>
						</>
					)}

					{error && (
						<p className="text-[13px] text-aram-danger bg-aram-surface border border-aram-border rounded-xl px-4 py-3" role="alert">
							{error}
						</p>
					)}

					<p className="text-xs text-neutral-500">
						We only store your name, your child&rsquo;s first name and birth month, and the interests you pick (plus your email if you sign in with one).
					</p>
				</div>
			</main>
		</div>
	);
}

const POINTS = [
	{ icon: 'verified_user', title: 'You choose what belongs', body: 'Only the channels and podcasts you approve are added to your child’s space.' },
	{ icon: 'shield', title: 'Reviewed with care by the Aram Agent', body: 'Each channel or podcast is assessed for age and suitability by the Aram Agent before you decide whether to add it.' },
	{ icon: 'explore', title: 'Discover with the Aram Agent', body: 'Tell Aram what your child loves, or simply ask it to find a channel or podcast.' },
];

function Brand() {
	return (
		<div className="flex items-center gap-3">
			<AramMark size={40} />
			<span className="text-xl font-bold tracking-tight">Aram</span>
		</div>
	);
}

function GoogleMark() {
	return (
		<svg aria-hidden="true" height="18" viewBox="0 0 48 48" width="18">
			<path d="M43.6 20.1H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.1 8 3l5.7-5.7C34 6.1 29.3 4 24 4 13 4 4 13 4 24s9 20 20 20 20-9 20-20c0-1.3-.1-2.6-.4-3.9z" fill="#FFC107" />
			<path d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.1 8 3l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z" fill="#FF3D00" />
			<path d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z" fill="#4CAF50" />
			<path d="M43.6 20.1H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.6-.4-3.9z" fill="#1976D2" />
		</svg>
	);
}
