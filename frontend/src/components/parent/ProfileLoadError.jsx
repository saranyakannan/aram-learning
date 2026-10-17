import { Link } from 'react-router-dom';
import { useAuth } from '../../lib/AuthContext';
import { useParent } from '../../lib/ParentContext';

/** Shown when the parent's profile could not be loaded: says what kind of failure it was and offers a way out. */
export default function ProfileLoadError() {
	const { error, reload } = useParent();
	const { signOut } = useAuth();
	const sessionProblem = error?.status === 401;
	const detail = error?.status ? `Error ${error.status}` : 'Could not connect to the server';

	return (
		<div className="flex flex-col items-center gap-3 py-16 text-center">
			<p className="text-sm text-aram-danger">
				{sessionProblem
					? 'Your session has expired. Please sign in again.'
					: "We can't reach Aram right now. Please try again in a moment."}
			</p>
			{!sessionProblem && <p className="text-xs text-aram-faint">{detail}</p>}
			<div className="flex items-center gap-4">
				{sessionProblem ? (
					<button className="text-sm font-semibold text-aram-blue hover:underline" type="button" onClick={signOut}>
						Sign in again
					</button>
				) : (
					<button className="text-sm font-semibold text-aram-blue hover:underline" type="button" onClick={reload}>
						Try again
					</button>
				)}
				<Link to="/" className="text-sm text-aram-muted hover:text-white hover:underline">
					Back to Kid Space
				</Link>
			</div>
		</div>
	);
}
