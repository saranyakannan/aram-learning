import { Navigate, Outlet } from 'react-router-dom';
import { onboardingPath, useParent } from '../../lib/ParentContext';
import { SearchProvider } from '../../lib/SearchContext';
import ParentHeader from './ParentHeader';
import AgentDock from './AgentDock';
import ParentSidebar from './ParentSidebar';
import ProfileLoadError from './ProfileLoadError';
import Loader from '../Loader';

const HOME_PATH = '/parent/channels';

export default function ParentShell() {
	const { profile, status } = useParent();

	if (status === 'loading') {
		return (
			<Loader fullScreen />
		);
	}
	if (status === 'error') {
		return (
			<div className="min-h-screen bg-aram-bg text-white font-body">
				<ProfileLoadError />
			</div>
		);
	}
	if (onboardingPath(profile) !== HOME_PATH) {
		return <Navigate to={onboardingPath(profile)} replace />;
	}

	return (
		<SearchProvider>
			<div className="min-h-screen bg-aram-bg text-white font-body antialiased selection:bg-aram-blue selection:text-white">
				<ParentSidebar />
				<div className="pl-72">
					<ParentHeader />
					<main className="w-full pt-16 bg-aram-bg">
						<Outlet />
					</main>
					<AgentDock />
				</div>
			</div>
		</SearchProvider>
	);
}
