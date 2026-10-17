/**
 * The Aram logo: a blue disc with an o and an x in a 2x2 pattern. With `spin` the pattern turns in steps
 * (a few frames per second, on purpose) while the disc stays still. Used by the sidebars, sign-in and the loader.
 */
export default function AramMark({ size = 40, spin = false, className = '' }) {
	return (
		<svg
			aria-hidden="true"
			className={className}
			fill="none"
			height={size}
			viewBox="0 0 48 48"
			width={size}
		>
			<circle cx="24" cy="24" fill="#0095f6" r="24" />
			<g className={spin ? 'aram-mark-spin' : undefined} stroke="#fff" strokeLinecap="round" strokeWidth="3.4">
				<circle cx="16" cy="16" r="5" />
				<path d="M28 12l8 8M36 12l-8 8" />
				<path d="M12 28l8 8M20 28l-8 8" />
				<circle cx="32" cy="32" r="5" />
			</g>
		</svg>
	);
}
