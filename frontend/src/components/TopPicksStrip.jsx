import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatRelativeDate } from '../lib/categoryStyle';
import { formatMinutes } from '../lib/format';
import Loader from './Loader';

const ROWS_AT_FIRST = 2;

// Columns follow the grid classes below (1 / 2 / 3 / 4 / 5), so "two rows" really is two rows at any width.
const BREAKPOINTS = [
	['(min-width: 1536px)', 5],
	['(min-width: 1280px)', 4],
	['(min-width: 1024px)', 3],
	['(min-width: 640px)', 2],
];

function currentColumns() {
	if (typeof window === 'undefined') return 4;
	return BREAKPOINTS.find(([query]) => window.matchMedia(query).matches)?.[1] ?? 1;
}

function useColumns() {
	const [columns, setColumns] = useState(currentColumns);
	useEffect(() => {
		const lists = BREAKPOINTS.map(([query]) => window.matchMedia(query));
		const update = () => setColumns(currentColumns());
		lists.forEach((list) => list.addEventListener('change', update));
		return () => lists.forEach((list) => list.removeEventListener('change', update));
	}, []);
	return columns;
}

/**
 * The first block of Kid Space: popular videos and fresh podcast episodes from approved sources, two rows at a time
 * with "Show more" (like YouTube). The server re-samples the picks on every request, and the shuffle button asks for new ones.
 */
export default function TopPicksStrip({ childName, picks, isLoading, isShuffling, onShuffle }) {
	const columns = useColumns();
	const [rows, setRows] = useState(ROWS_AT_FIRST);

	if (isLoading) return <Loader size="lg" />;
	if (picks.length === 0) return null;

	const visible = columns * rows;
	const shown = picks.slice(0, visible);
	const hasMore = visible < picks.length;

	return (
		<section className="flex flex-col gap-space-md w-full">
			<div className="flex items-end justify-between gap-space-md">
				<div className="flex flex-col gap-1">
					<div className="flex items-center gap-space-xs text-secondary">
						<span className="material-symbols-outlined text-lg filled">local_fire_department</span>
						<span className="font-headline text-label-sm uppercase tracking-wider">Top picks</span>
					</div>
					<h2 className="font-headline text-headline-md text-on-surface">
						{childName ? `Popular with kids like ${childName}` : 'Popular right now'}
					</h2>
				</div>
				<button
					className="shrink-0 flex items-center gap-1.5 px-3.5 py-2 rounded-full bg-surface-container-high hover:bg-surface-container-highest text-on-surface-variant hover:text-on-surface font-headline text-label-md transition-colors disabled:opacity-50"
					disabled={isShuffling}
					type="button"
					onClick={() => {
						setRows(ROWS_AT_FIRST);
						onShuffle();
					}}
				>
					{isShuffling ? (
						<Loader inline size="sm" />
					) : (
						<span className="material-symbols-outlined text-[18px]">shuffle</span>
					)}
					Shuffle
				</button>
			</div>

			<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-gutter">
				{shown.map((pick) =>
					pick.type === 'EPISODE' ? (
						<EpisodeTile key={`e:${pick.sourceId}:${pick.id}`} pick={pick} />
					) : (
						<VideoTile key={`v:${pick.id}`} pick={pick} />
					),
				)}
			</div>

			{(hasMore || rows > ROWS_AT_FIRST) && (
				<div className="flex justify-center gap-space-sm">
					{hasMore && (
						<button
							className="px-5 py-2 rounded-full bg-surface-container-high hover:bg-surface-container-highest text-on-surface font-headline text-label-md transition-colors"
							type="button"
							onClick={() => setRows((current) => current + ROWS_AT_FIRST)}
						>
							Show more
						</button>
					)}
					{rows > ROWS_AT_FIRST && (
						<button
							className="px-5 py-2 rounded-full text-on-surface-variant hover:text-on-surface font-headline text-label-md transition-colors"
							type="button"
							onClick={() => setRows(ROWS_AT_FIRST)}
						>
							Show less
						</button>
					)}
				</div>
			)}
		</section>
	);
}

const TILE = 'flex flex-col bg-surface-container-low rounded-2xl overflow-hidden';

function Cover({ pick, children }) {
	return (
		<div className="relative aspect-[16/10] w-full bg-surface-container-highest overflow-hidden">
			{pick.thumbnailUrl && (
				<img
					alt=""
					className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
					loading="lazy"
					src={pick.thumbnailUrl}
				/>
			)}
			<div className="absolute inset-0 bg-gradient-to-t from-black/70 via-transparent to-transparent" />
			{children}
		</div>
	);
}

function Badge({ icon, label }) {
	return (
		<div className="absolute top-2.5 left-2.5 flex items-center gap-1.5 px-2.5 py-0.5 rounded-full bg-black/70 backdrop-blur-md text-primary">
			<span className="material-symbols-outlined text-xs">{icon}</span>
			<span className="font-headline text-label-sm uppercase">{label}</span>
		</div>
	);
}

function VideoTile({ pick }) {
	const length = formatMinutes(pick.durationSeconds);
	return (
		<Link
			className={`group ${TILE} hover:shadow-lg transition-shadow`}
			to={`/watch/${encodeURIComponent(pick.sourceId)}/${encodeURIComponent(pick.id)}`}
		>
			<Cover pick={pick}>
				<Badge icon="smart_display" label="Video" />
				{length && (
					<div className="absolute bottom-2.5 right-2.5 px-2 py-0.5 rounded bg-black/80 font-headline text-label-sm text-on-surface">
						{length}
					</div>
				)}
			</Cover>
			<div className="p-space-sm flex flex-col gap-1 bg-surface-container flex-1">
				<h3 className="font-headline text-label-lg text-on-surface line-clamp-2 group-hover:text-primary transition-colors">
					{pick.title}
				</h3>
				<p className="font-headline text-label-sm text-outline truncate">{pick.sourceName}</p>
			</div>
		</Link>
	);
}

function EpisodeTile({ pick }) {
	const length = formatMinutes(pick.durationSeconds);
	return (
		<Link
			className={`group ${TILE} hover:shadow-lg transition-shadow`}
			to={`/listen/${encodeURIComponent(pick.sourceId)}/${encodeURIComponent(pick.id)}`}
		>
			<Cover pick={pick}>
				<Badge icon="podcasts" label="Podcast" />
				{length && (
					<div className="absolute bottom-2.5 right-2.5 px-2 py-0.5 rounded bg-black/80 font-headline text-label-sm text-on-surface">
						{length}
					</div>
				)}
			</Cover>
			<div className="p-space-sm flex flex-col gap-1 bg-surface-container flex-1">
				<h3 className="font-headline text-label-lg text-on-surface line-clamp-2 group-hover:text-primary transition-colors">
					{pick.title}
				</h3>
				<p className="font-headline text-label-sm text-outline truncate">
					{[pick.sourceName, formatRelativeDate(pick.publishedAt)].filter(Boolean).join(' • ')}
				</p>
			</div>
		</Link>
	);
}
