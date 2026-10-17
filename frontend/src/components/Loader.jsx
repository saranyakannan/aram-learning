import AramMark from './AramMark';

const SIZES = { sm: 16, md: 30, lg: 44 };

/**
 * The one loader used everywhere: the Aram mark with its o and x turning in low-frame steps.
 * `fullScreen` centres it on an empty page (session check, first load); otherwise it centres within its container.
 */
export default function Loader({ size = 'md', label, fullScreen = false, inline = false }) {
	const mark = <AramMark size={SIZES[size] ?? SIZES.md} spin />;

	if (inline) {
		return (
			<span aria-live="polite" className="inline-flex items-center gap-2" role="status">
				{mark}
				{label && <span>{label}</span>}
			</span>
		);
	}
	return (
		<div
			aria-live="polite"
			className={`flex flex-col items-center justify-center gap-3 text-aram-muted font-body text-sm ${
				fullScreen ? 'min-h-screen bg-black' : 'py-16 w-full'
			}`}
			role="status"
		>
			{mark}
			{label && <span>{label}</span>}
		</div>
	);
}
