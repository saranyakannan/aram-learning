import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/AuthContext';
import Loader from './Loader';

/** Everything inside needs a signed-in parent; anyone else is sent to the sign-in page and back afterwards. */
export default function RequireAuth() {
	const { user } = useAuth();
	const location = useLocation();

	if (user === undefined) {
		return <Loader fullScreen label="Checking your session..." />;
	}
	if (user === null) {
		return <Navigate replace state={{ from: location.pathname }} to="/signin" />;
	}
	return <Outlet />;
}
