import { useEffect, useId, useRef, useState } from 'react';

const PREVIEW_CHARS = 180;

/**
 * An (i) icon that shows a long text in a small floating box on hover, keyboard focus or tap. The box has a fixed
 * maximum size and scrolls, and starts with a short preview that "Show more" expands. Plain text only.
 */
export default function InfoTip({ text, label = 'More information' }) {
	const [open, setOpen] = useState(false);
	const [expanded, setExpanded] = useState(false);
	const root = useRef(null);
	const closeTimer = useRef(null);
	const boxId = useId();

	useEffect(() => {
		if (!open) return undefined;
		const close = (event) => {
			if (event.type === 'keydown' ? event.key === 'Escape' : !root.current?.contains(event.target)) setOpen(false);
		};
		document.addEventListener('mousedown', close);
		document.addEventListener('keydown', close);
		return () => {
			document.removeEventListener('mousedown', close);
			document.removeEventListener('keydown', close);
		};
	}, [open]);

	useEffect(() => () => clearTimeout(closeTimer.current), []);

	const clean = (text ?? '').replace(/\s+/g, ' ').trim();
	if (!clean) return null;
	const isLong = clean.length > PREVIEW_CHARS;
	const shown = expanded || !isLong ? clean : `${clean.slice(0, PREVIEW_CHARS).trimEnd()}…`;

	const show = () => {
		clearTimeout(closeTimer.current);
		setOpen(true);
	};
	// A short delay lets the pointer travel from the icon into the box (to scroll it) without it closing.
	const hide = () => {
		closeTimer.current = setTimeout(() => {
			setOpen(false);
			setExpanded(false);
		}, 150);
	};

	return (
		<span ref={root} className="relative inline-flex" onMouseEnter={show} onMouseLeave={hide}>
			<button
				aria-describedby={open ? boxId : undefined}
				aria-expanded={open}
				aria-label={label}
				className="w-6 h-6 rounded-full flex items-center justify-center text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
				type="button"
				onBlur={hide}
				onClick={() => setOpen((value) => !value)}
				onFocus={show}
			>
				<span className="material-symbols-outlined text-[18px]">info</span>
			</button>
			{open && (
				<div
					className="absolute left-0 top-8 z-30 w-80 max-w-[80vw] max-h-40 overflow-y-auto rounded-xl border border-aram-border bg-aram-surface p-3 shadow-2xl"
					id={boxId}
					role="tooltip"
				>
					<p className="font-body text-body-sm text-on-surface-variant whitespace-pre-line break-words">{shown}</p>
					{isLong && (
						<button
							className="mt-1.5 font-headline text-label-sm text-primary hover:underline"
							type="button"
							onClick={() => setExpanded((value) => !value)}
						>
							{expanded ? 'Show less' : 'Show more'}
						</button>
					)}
				</div>
			)}
		</span>
	);
}
