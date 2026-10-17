import { Outlet } from 'react-router-dom';
import { useAuth } from '../../lib/AuthContext';
import { useParent } from '../../lib/ParentContext';
import ProfileLoadError from './ProfileLoadError';
import Loader from '../Loader';

export default function OnboardingLayout() {
	const { status } = useParent();
	const { signOut } = useAuth();

	return (
		<div className="min-h-screen flex flex-col bg-aram-bg text-white font-body antialiased selection:bg-aram-blue selection:text-white">
			<header className="w-full bg-aram-bg border-b border-aram-border px-4 sm:px-6 py-3.5">
				<div className="max-w-3xl mx-auto flex items-center justify-between">
					<span className="text-xl font-bold tracking-tight">Aram</span>
					<button className="text-xs font-medium text-neutral-400 hover:text-white transition-colors" type="button" onClick={signOut}>
						Sign out
					</button>
				</div>
			</header>

			<main className="flex-1 flex flex-col items-center justify-center px-4 py-12">
				{status === 'loading' && <Loader />}
				{status === 'error' && <ProfileLoadError />}
				{status === 'ready' && <Outlet />}
			</main>

			<footer className="w-full bg-aram-bg border-t border-[#1a1a1a] py-4">
				<div className="w-full max-w-3xl mx-auto px-4 text-xs text-neutral-500 flex items-center gap-2">
					<span className="font-semibold text-neutral-300">Aram</span>
					<span>&bull; Handcrafted by a parent to another for child learning</span>
				</div>
			</footer>
		</div>
	);
}
