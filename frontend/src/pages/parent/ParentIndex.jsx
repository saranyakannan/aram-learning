import { Navigate } from 'react-router-dom';
import ProfileLoadError from '../../components/parent/ProfileLoadError';
import { onboardingPath, useParent } from '../../lib/ParentContext';
import Loader from '../../components/Loader';

export default function ParentIndex() {
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
	return <Navigate to={onboardingPath(profile)} replace />;
}
