import { Link } from 'react-router-dom';

export default function StatusMessage({ children, isError = false }) {
	return (
		<div className="flex flex-col items-center gap-3 py-16 text-center">
			<p className={`text-sm ${isError ? 'text-aram-danger' : 'text-aram-muted'}`}>{children}</p>
			{isError && (
				<Link to="/" className="text-sm font-semibold text-aram-blue hover:underline">
					Back to Kid Space
				</Link>
			)}
		</div>
	);
}
