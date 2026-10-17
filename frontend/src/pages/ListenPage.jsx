import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getChannel, getPodcastEpisodes } from '../api/aramApi';
import Loader from '../components/Loader';
import { formatAgeRange, formatRelativeDate } from '../lib/categoryStyle';
import { formatDuration } from '../lib/format';
import { useParent } from '../lib/ParentContext';

const SPEEDS = [1, 1.25, 1.5, 0.75];
const BACK_SECONDS = 10;
const FORWARD_SECONDS = 30;

function BackHome({ children }) {
	return (
		<div className="flex flex-col gap-space-md">
			<p className="font-body text-body-md text-on-surface-variant">{children}</p>
			<Link to="/" className="font-headline text-label-lg text-secondary">
				&larr; Back to Home
			</Link>
		</div>
	);
}

/** The inner view of a podcast episode: a player card (cover, title, description, seek bar and controls). The rest of the page is left empty for now. */
export default function ListenPage() {
	const { showId, episodeId } = useParams();
	const { profile, status: profileStatus } = useParent();
	const approvedKey = (profile?.channelIds ?? []).join(',');
	const isApproved = approvedKey.split(',').includes(showId);

	const [show, setShow] = useState(null);
	const [episodes, setEpisodes] = useState([]);
	const [isLoading, setIsLoading] = useState(true);
	const [error, setError] = useState(null);

	useEffect(() => {
		if (profileStatus === 'loading') return;
		if (!isApproved) {
			setIsLoading(false);
			return;
		}
		let cancelled = false;
		setIsLoading(true);
		setError(null);
		Promise.all([getChannel(showId), getPodcastEpisodes(showId)])
			.then(([showData, episodeList]) => {
				if (cancelled) return;
				setShow(showData);
				setEpisodes(episodeList);
			})
			.catch((err) => {
				if (!cancelled) setError(err.message);
			})
			.finally(() => {
				if (!cancelled) setIsLoading(false);
			});
		return () => {
			cancelled = true;
		};
	}, [showId, profileStatus, isApproved]);

	const episode = useMemo(() => episodes.find((entry) => entry.id === episodeId), [episodes, episodeId]);

	if (profileStatus !== 'loading' && !isApproved) {
		return <BackHome>This show isn't in your list. Ask a grown-up if you'd like to listen to it.</BackHome>;
	}
	if (isLoading || profileStatus === 'loading') return <Loader size="lg" />;
	if (error) return <BackHome>Couldn't load this episode: {error}</BackHome>;
	if (!episode || !show) {
		return <BackHome>That episode isn't in the latest list any more. Head back and pick another one.</BackHome>;
	}
	return <Player key={episode.id} show={show} episode={episode} />;
}

function Player({ show, episode }) {
	const audioRef = useRef(null);
	const [playing, setPlaying] = useState(false);
	const [time, setTime] = useState(0);
	const [length, setLength] = useState(episode.durationSeconds ?? 0);
	const [speedIndex, setSpeedIndex] = useState(0);
	const [failed, setFailed] = useState(false);
	const [coverFailed, setCoverFailed] = useState(false);

	const audio = () => audioRef.current;
	const toggle = () => {
		const el = audio();
		if (!el) return;
		if (el.paused) el.play().catch(() => setFailed(true));
		else el.pause();
	};
	const skip = (seconds) => {
		const el = audio();
		if (!el) return;
		el.currentTime = Math.max(0, Math.min(el.duration || length || 0, el.currentTime + seconds));
	};
	const cycleSpeed = () => {
		const next = (speedIndex + 1) % SPEEDS.length;
		setSpeedIndex(next);
		if (audio()) audio().playbackRate = SPEEDS[next];
	};

	const cover = episode.imageUrl ?? show.thumbnailUrl;
	const age = formatAgeRange(show.minAge, show.maxAge);
	const published = formatRelativeDate(episode.publishedAt);

	return (
		<div className="flex flex-col gap-space-md max-w-4xl pb-16">
			<div className="flex flex-col gap-space-lg rounded-3xl border border-surface-container-highest bg-surface-container-low p-space-md sm:p-space-lg shadow-2xl">
				<div className="flex flex-wrap items-center gap-space-sm">
					<span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-surface-container-high text-on-surface-variant font-headline text-label-sm uppercase tracking-wide">
						<span className="material-symbols-outlined text-[14px]">headphones</span>
						Now playing{age ? ` · ${age}` : ''}
					</span>
					<span className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-primary/15 text-primary font-headline text-label-sm uppercase tracking-wide">
						<span className="material-symbols-outlined text-[14px]">podcasts</span>
						Podcast
					</span>
				</div>

				<div className="grid gap-space-lg sm:grid-cols-[200px_1fr] items-start">
					<div className="relative aspect-square w-full max-w-[220px] rounded-2xl overflow-hidden bg-surface-container-highest border border-surface-container-highest">
						{cover && !coverFailed ? (
							<img alt="" className="w-full h-full object-cover" src={cover} onError={() => setCoverFailed(true)} />
						) : (
							<div className="w-full h-full flex items-center justify-center text-primary">
								<span className="material-symbols-outlined text-[64px]">podcasts</span>
							</div>
						)}
					</div>
					<div className="flex flex-col gap-space-sm min-w-0">
						<span className="font-headline text-label-sm uppercase tracking-wider text-primary truncate">{show.name}</span>
						<h1 className="font-headline text-headline-lg text-on-surface tracking-tight">{episode.title}</h1>
						{(published || episode.description) && (
							<p className="font-headline text-label-sm text-outline">{published}</p>
						)}
						{episode.description && (
							<p className="font-body text-body-md text-on-surface-variant whitespace-pre-line line-clamp-5">
								{episode.description}
							</p>
						)}
					</div>
				</div>

				<div className="flex flex-col gap-space-sm">
					<input
						aria-label="Seek"
						className="w-full accent-primary cursor-pointer"
						max={Math.max(length, 1)}
						min={0}
						step={1}
						type="range"
						value={Math.min(time, Math.max(length, 1))}
						onChange={(event) => {
							if (audio()) audio().currentTime = Number(event.target.value);
							setTime(Number(event.target.value));
						}}
					/>
					<div className="flex justify-between font-headline text-label-sm text-on-surface-variant">
						<span>{formatDuration(Math.floor(time)) ?? '0:00'}</span>
						<span>{length ? formatDuration(Math.floor(length)) : '--:--'}</span>
					</div>
				</div>

				<div className="grid grid-cols-3 items-center">
					<div>
						<button
							className="px-4 py-2 rounded-full bg-surface-container-high hover:bg-surface-container-highest text-on-surface-variant hover:text-on-surface font-headline text-label-md transition-colors"
							type="button"
							onClick={cycleSpeed}
						>
							{SPEEDS[speedIndex]}× Speed
						</button>
					</div>
					<div className="flex items-center justify-center gap-space-md">
						<button
							aria-label={`Back ${BACK_SECONDS} seconds`}
							className="w-11 h-11 rounded-full bg-surface-container-high hover:bg-surface-container-highest text-on-surface flex items-center justify-center transition-colors"
							type="button"
							onClick={() => skip(-BACK_SECONDS)}
						>
							<span className="material-symbols-outlined text-[24px]">replay_10</span>
						</button>
						<button
							aria-label={playing ? 'Pause' : 'Play'}
							className="w-16 h-16 rounded-full bg-primary hover:opacity-90 text-on-primary flex items-center justify-center shadow-[0_8px_24px_rgba(0,149,246,0.4)] transition-opacity"
							type="button"
							onClick={toggle}
						>
							<span className="material-symbols-outlined filled text-[36px]">{playing ? 'pause' : 'play_arrow'}</span>
						</button>
						<button
							aria-label={`Forward ${FORWARD_SECONDS} seconds`}
							className="w-11 h-11 rounded-full bg-surface-container-high hover:bg-surface-container-highest text-on-surface flex items-center justify-center transition-colors"
							type="button"
							onClick={() => skip(FORWARD_SECONDS)}
						>
							<span className="material-symbols-outlined text-[24px]">forward_30</span>
						</button>
					</div>
					<div />
				</div>

				{failed && (
					<p className="font-body text-body-sm text-error" role="alert">
						This episode can't be played right now. Try another one.
					</p>
				)}
			</div>

			<audio
				ref={audioRef}
				preload="metadata"
				src={episode.audioUrl}
				onEnded={() => setPlaying(false)}
				onError={() => setFailed(true)}
				onLoadedMetadata={(event) => {
					if (Number.isFinite(event.currentTarget.duration)) setLength(event.currentTarget.duration);
				}}
				onPause={() => setPlaying(false)}
				onPlay={() => setPlaying(true)}
				onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)}
			/>
		</div>
	);
}
