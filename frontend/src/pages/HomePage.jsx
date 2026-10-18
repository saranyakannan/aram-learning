import { useCallback, useEffect, useMemo, useState } from 'react';
import { getChannels, getFeed, getPodcastFeed, getTopPicks } from '../api/aramApi';
import CategoryShelf from '../components/CategoryShelf';
import PodcastShelf from '../components/PodcastShelf';
import Loader from '../components/Loader';
import TopPicksStrip from '../components/TopPicksStrip';
import { PODCAST } from '../lib/format';
import { useParent } from '../lib/ParentContext';
import { useSearch } from '../lib/SearchContext';

export default function HomePage() {
	const [channels, setChannels] = useState([]);
	const [feed, setFeed] = useState({});
	const [podcastFeed, setPodcastFeed] = useState({});
	const [topPicks, setTopPicks] = useState([]);
	const [isShuffling, setIsShuffling] = useState(false);
	// The page waits only for the channel list; videos, podcasts and top picks fill in as each one arrives.
	const [channelsLoading, setChannelsLoading] = useState(true);
	const [feedLoading, setFeedLoading] = useState(true);
	const [podcastLoading, setPodcastLoading] = useState(true);
	const [picksLoading, setPicksLoading] = useState(true);
	const [channelsError, setChannelsError] = useState(null);
	const [feedError, setFeedError] = useState(null);
	const { searchTerm } = useSearch();
	const { profile, status: profileStatus } = useParent();

	// Only channels the parent approved are shown; no profile or no approvals means nothing is shown.
	const approvedKey = (profile?.channelIds ?? []).join(',');

	useEffect(() => {
		if (profileStatus === 'loading') return undefined;
		let cancelled = false;
		const ids = approvedKey === '' ? [] : approvedKey.split(',');
		setChannelsError(null);
		setFeedError(null);

		if (ids.length === 0) {
			setChannels([]);
			setFeed({});
			setPodcastFeed({});
			setTopPicks([]);
			setChannelsLoading(false);
			setFeedLoading(false);
			setPodcastLoading(false);
			setPicksLoading(false);
			return undefined;
		}

		setChannelsLoading(true);
		setFeedLoading(true);
		setPodcastLoading(true);
		setPicksLoading(true);

		// Four independent requests, each updating the page when it arrives. A video-feed hiccup (e.g. YouTube quota or API key
		// issues) must not hide the channel list, a podcast hiccup only empties the podcast shelves, and the top picks are a nicety.
		getChannels()
			.then((all) => {
				if (!cancelled) setChannels(ids.map((id) => all.find((c) => c.id === id)).filter(Boolean));
			})
			.catch((error) => {
				if (!cancelled) setChannelsError(error.message);
			})
			.finally(() => {
				if (!cancelled) setChannelsLoading(false);
			});
		getFeed(ids)
			.then((value) => {
				if (!cancelled) setFeed(value);
			})
			.catch((error) => {
				if (!cancelled) setFeedError(error.message);
			})
			.finally(() => {
				if (!cancelled) setFeedLoading(false);
			});
		getPodcastFeed()
			.then((value) => {
				if (!cancelled) setPodcastFeed(value);
			})
			.catch(() => {
				if (!cancelled) setPodcastFeed({});
			})
			.finally(() => {
				if (!cancelled) setPodcastLoading(false);
			});
		getTopPicks()
			.then((value) => {
				if (!cancelled) setTopPicks(value);
			})
			.catch(() => {
				if (!cancelled) setTopPicks([]);
			})
			.finally(() => {
				if (!cancelled) setPicksLoading(false);
			});

		return () => {
			cancelled = true;
		};
	}, [profileStatus, approvedKey]);

	const shuffle = useCallback(async () => {
		setIsShuffling(true);
		try {
			setTopPicks(await getTopPicks());
		} catch {
			// keep the current strip
		} finally {
			setIsShuffling(false);
		}
	}, []);

	const videoChannels = useMemo(() => channels.filter((channel) => channel.sourceType !== PODCAST), [channels]);
	const shows = useMemo(() => channels.filter((channel) => channel.sourceType === PODCAST), [channels]);

	const visibleShows = useMemo(() => {
		const term = searchTerm.trim().toLowerCase();
		return shows
			.map((show) => {
				const episodes = podcastFeed[show.id] ?? [];
				if (!term) return { show, episodes };
				if (show.name.toLowerCase().includes(term)) return { show, episodes };
				const matching = episodes.filter((episode) => episode.title.toLowerCase().includes(term));
				return matching.length > 0 ? { show, episodes: matching } : null;
			})
			.filter(Boolean);
	}, [shows, podcastFeed, searchTerm]);

	const visibleShelves = useMemo(() => {
		const term = searchTerm.trim().toLowerCase();

		return videoChannels
			.map((channel) => {
				const videos = feed[channel.id] ?? [];
				if (!term) return { channel, videos };

				const channelMatches =
					channel.name.toLowerCase().includes(term) || (channel.category ?? '').toLowerCase().includes(term);
				const matchingVideos = videos.filter((video) => video.title.toLowerCase().includes(term));

				if (channelMatches) return { channel, videos };
				if (matchingVideos.length > 0) return { channel, videos: matchingVideos };
				return null;
			})
			.filter(Boolean);
	}, [videoChannels, feed, searchTerm]);

	if (channelsLoading || profileStatus === 'loading') {
		return <Loader size="lg" label="Getting your videos ready" />;
	}

	return (
		<div className="flex flex-col w-full gap-space-xl pb-16">
			{channelsError && (
				<p className="font-body text-body-sm text-error">Couldn't load channels: {channelsError}</p>
			)}
			{feedError && !channelsError && (
				<p className="font-body text-body-sm text-error">Couldn't load videos right now: {feedError}</p>
			)}

			{approvedKey === '' && (
				<div className="flex flex-col gap-1">
					<h1 className="font-headline text-headline-lg text-on-surface tracking-tight">Your little world is waiting!</h1>
					<p className="font-body text-body-md text-on-surface-variant">
						Stories to discover, wonders to explore, and new things to learn.
					</p>
					<p className="font-headline text-label-lg text-primary">
						Your grown-up will help fill your space with wonderful videos and podcasts picked just for you!
					</p>
				</div>
			)}

			{!searchTerm && approvedKey !== '' && (
				<header className="flex flex-col gap-1">
					<h1 className="font-headline text-headline-lg text-on-surface tracking-tight">A World of Wonder Awaits!</h1>
					<p className="font-body text-body-md text-on-surface-variant">
						Stories to hear, things to discover, and new adventures to explore.
					</p>
				</header>
			)}

			{searchTerm && !feedLoading && !podcastLoading && visibleShelves.length === 0 && visibleShows.length === 0 && (
				<p className="font-body text-body-sm text-on-surface-variant">
					Nothing matches "{searchTerm}".
				</p>
			)}

			{!searchTerm && approvedKey !== '' && (
				<>
					<TopPicksStrip
						isLoading={picksLoading}
						isShuffling={isShuffling}
						picks={topPicks}
						onShuffle={shuffle}
					/>
					<hr className="h-px border-0 bg-surface-container-highest" />
				</>
			)}

			{visibleShelves.map(
				({ channel, videos }) => (
					<CategoryShelf
						key={channel.id}
						channel={channel}
						videos={videos}
						isLoading={feedLoading}
					/>
				),
			)}

			{visibleShows.map(({ show, episodes }) => (
				<PodcastShelf
					key={show.id}
					show={show}
					episodes={episodes}
					isLoading={podcastLoading}
				/>
			))}
		</div>
	);
}
