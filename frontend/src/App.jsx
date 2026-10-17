import { Navigate, Route, Routes } from 'react-router-dom';
import ChildShell from './components/ChildShell';
import OnboardingLayout from './components/parent/OnboardingLayout';
import ParentShell from './components/parent/ParentShell';
import RequireAuth from './components/RequireAuth';
import { AgentProvider } from './lib/AgentContext';
import { AuthProvider } from './lib/AuthContext';
import { ParentProvider } from './lib/ParentContext';
import HomePage from './pages/HomePage';
import ListenPage from './pages/ListenPage';
import WatchPage from './pages/WatchPage';
import InterestsPage from './pages/parent/InterestsPage';
import AgentPage from './pages/parent/AgentPage';
import ManageChannelsPage from './pages/parent/ManageChannelsPage';
import ParentIndex from './pages/parent/ParentIndex';
import ProfilePage from './pages/parent/ProfilePage';
import SignInPage from './pages/SignInPage';
import WelcomePage from './pages/parent/WelcomePage';

export default function App() {
	return (
		<AuthProvider>
			<ParentProvider>
				<AgentProvider>
				<Routes>
					<Route path="/signin" element={<SignInPage />} />

					<Route element={<RequireAuth />}>
						<Route element={<ChildShell />}>
							<Route path="/" element={<HomePage />} />
							<Route path="/watch/:channelId/:videoId" element={<WatchPage />} />
							<Route path="/listen/:showId/:episodeId" element={<ListenPage />} />
						</Route>

						<Route path="/parent" element={<ParentIndex />} />
						<Route element={<OnboardingLayout />}>
							<Route path="/parent/welcome" element={<WelcomePage />} />
							<Route path="/parent/interests" element={<InterestsPage />} />
						</Route>
						<Route element={<ParentShell />}>
							<Route path="/parent/channels" element={<ManageChannelsPage />} />
							<Route path="/parent/agent" element={<AgentPage />} />
							<Route path="/parent/profile" element={<ProfilePage />} />
						</Route>
					</Route>

					<Route path="*" element={<Navigate to="/" replace />} />
				</Routes>
				</AgentProvider>
			</ParentProvider>
		</AuthProvider>
	);
}
