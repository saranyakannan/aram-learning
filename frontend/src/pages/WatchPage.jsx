import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { getChannel, getChannels, getFeed, getVideo, getVideosForChannel } from '../api/aramApi';
import CategoryShelf from '../components/CategoryShelf';
import { formatAgeRange, formatRelativeDate, iconForCategory } from '../lib/categoryStyle';
import { useParent } from '../lib/ParentContext';
import Loader from '../components/Loader';

export default function WatchPage() {
	const { channelId, videoId } = useParams();
	const { profile, status: profileStatus } = useParent();

	// Only channels the parent approved can be watched; no profile or no approvals means none.
	const approvedKey = (profile?.channelIds ?? []).join(',');
	const isApproved = approvedKey.split(',').includes(channelId);

	const [channel, setChannel] = useState(null);
	const [video, setVideo] = useState(null);
	const [channelVideos, setChannelVideos] = useState([]);
	const [otherChannels, setOtherChannels] = useState([]);
	const [feed, setFeed] = useState({});
	const [othersLoaded, setOthersLoaded] = useState(false);
	const [isLoading, setIsLoading] = useState(true);
	const [error, setError] = useState(null);

	useEffect(() => {
		if (profileStatus === 'loading') return;
		if (!isApproved) {
			setIsLoading(false);
			return;
		}
		let cancelled = false;

		async function load() {
			setIsLoading(true);
			setOthersLoaded(false);
			setError(null);
			const approvedIds = approvedKey.split(',');
			// The player needs only this channel and this one video (any video of the channel opens, not just the latest few);
			// the rest of the channel's videos and the other channels' shelves load afterwards.
			try {
				const [channelData, videoData] = await Promise.all([getChannel(channelId), getVideo(channelId, videoId)]);
				if (cancelled) return;
				setChannel(channelData);
				setVideo(videoData);
				setIsLoading(false);
			} catch (err) {
				if (!cancelled) {
					if (err.status === 404) {
						setVideo(null); // not this channel's video, or gone: the friendly "couldn't find that video" page
					} else {
						setError(err.message);
					}
					setIsLoading(false);
				}
				return;
			}
			try {
				setChannelVideos(await getVideosForChannel(channelId));
			} catch {
				// "More from this channel" is optional.
			}
			try {
				const [allChannels, feedData] = await Promise.all([getChannels(), getFeed(approvedIds)]);
				if (cancelled) return;
				setOtherChannels(allChannels.filter((entry) => entry.id !== channelId && approvedIds.includes(entry.id)));
				setFeed(feedData);
			} catch {
				// The shelves below are optional; the video itself is already playing.
			} finally {
				if (!cancelled) setOthersLoaded(true);
			}
		}

		load();
		return () => {
			cancelled = true;
		};
	}, [channelId, videoId, profileStatus, approvedKey, isApproved]);

	const moreFromChannel = channelVideos.filter((entry) => entry.videoId !== videoId);

	if (profileStatus !== 'loading' && !isApproved) {
		return (
			<div className="flex flex-col gap-space-md">
				<p className="font-body text-body-md text-on-surface-variant">
					This channel isn't in your list. Ask a grown-up if you'd like to watch it.
				</p>
				<Link to="/" className="font-headline text-label-lg text-secondary">
					&larr; Back to Home
				</Link>
			</div>
		);
	}

	if (isLoading || profileStatus === 'loading') {
		return <Loader size="lg" />;
	}

	if (error) {
		return (
			<div className="flex flex-col gap-space-md">
				<p className="font-body text-body-sm text-error">Couldn't load this video: {error}</p>
				<Link to="/" className="font-headline text-label-lg text-secondary">
					&larr; Back to Home
				</Link>
			</div>
		);
	}

	if (!video || !channel) {
		return (
			<div className="flex flex-col gap-space-md">
				<p className="font-body text-body-sm text-on-surface-variant">
					We couldn't find that video any more. It may have been removed or replaced by a newer one. Head back and pick
					another one.
				</p>
				<Link to="/" className="font-headline text-label-lg text-secondary">
					&larr; Back to Home
				</Link>
			</div>
		);
	}

	return (
		<div className="flex flex-col gap-10 pb-16">
			<div className="flex flex-col gap-space-lg w-full">
				<div className="w-full aspect-video rounded-2xl overflow-hidden bg-surface-container-lowest shadow-2xl border border-surface-container">
					<iframe
						className="w-full h-full"
						src={`https://www.youtube-nocookie.com/embed/${encodeURIComponent(video.videoId)}`}
						title={video.title}
						allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
						allowFullScreen
					/>
				</div>

				<div className="flex flex-col gap-space-md">
					<h1 className="font-headline text-headline-lg text-on-surface tracking-tight">{video.title}</h1>
					<div className="flex flex-wrap items-center justify-between gap-space-md border-b border-surface-container-low pb-space-md">
						<div className="flex items-center gap-space-md">
							<div className="w-12 h-12 rounded-xl bg-secondary-container/20 flex items-center justify-center text-secondary flex-shrink-0">
								<span className="material-symbols-outlined text-[24px]">{iconForCategory(channel.category)}</span>
							</div>
							<div className="flex flex-col min-w-0">
								<span className="font-headline text-label-lg text-on-surface font-bold truncate">
									{channel.name}
								</span>
								{channel.category && (
									<span className="font-headline text-label-sm text-outline truncate">{channel.category}</span>
								)}
							</div>
						</div>
						<div className="flex items-center gap-space-sm">
							{formatAgeRange(channel.minAge, channel.maxAge) && (
								<span className="font-headline text-label-sm px-3 py-1 rounded-full bg-tertiary/15 text-tertiary">
									{formatAgeRange(channel.minAge, channel.maxAge)}
								</span>
							)}
							{video.publishedAt && (
								<span className="font-headline text-label-sm text-on-surface-variant">
									Published {formatRelativeDate(video.publishedAt)}
								</span>
							)}
						</div>
					</div>
					{video.description && (
						<p className="font-body text-body-md text-on-surface-variant whitespace-pre-line line-clamp-6">
							{video.description}
						</p>
					)}
				</div>
			</div>

			{moreFromChannel.length > 0 && (
				<CategoryShelf channel={channel} videos={moreFromChannel} isLoading={false} />
			)}

			<div className="flex flex-col gap-10 pt-4 border-t border-surface-container/80">
				{otherChannels.map((entry) => (
					<CategoryShelf
						key={entry.id}
						channel={entry}
						videos={feed[entry.id] ?? []}
						isLoading={!othersLoaded}
					/>
				))}
			</div>
		</div>
	);
}
