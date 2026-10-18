import { useEffect, useMemo, useState } from 'react';
import { getChannels, getRecommendations, getTags } from '../../api/aramApi';
import { formatAgeRange, iconForCategory } from '../../lib/categoryStyle';
import { useAgent } from '../../lib/AgentContext';
import { useParent } from '../../lib/ParentContext';
import { useSearch } from '../../lib/SearchContext';
import BooksSection from '../../components/parent/BooksSection';
import Loader from '../../components/Loader';

const MAX_RECOMMENDED = 12;

function ChannelCover({ channel, thumbnailUrl, children }) {
	return (
		<div className="relative aspect-video w-full bg-aram-surface-3 overflow-hidden">
			{thumbnailUrl ? (
				<img
					alt=""
					className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
					loading="lazy"
					src={thumbnailUrl}
				/>
			) : (
				<div className="w-full h-full flex items-center justify-center text-aram-faint">
					<span className="material-symbols-outlined text-[48px]">
					{channel.sourceType === 'PODCAST_FEED' ? 'podcasts' : iconForCategory(channel.category)}
				</span>
				</div>
			)}
			<div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/20 to-transparent" />
			{children}
		</div>
	);
}

function Pill({ children, className = '' }) {
	return (
		<div
			className={`flex items-center gap-1.5 px-3 py-1 rounded-full bg-black/80 border border-aram-border backdrop-blur-md text-[11px] font-bold uppercase tracking-wide ${className}`}
		>
			{children}
		</div>
	);
}

function SectionHeader({ icon, title, subtitle }) {
	return (
		<div className="flex items-center gap-2.5">
			<div className="w-9 h-9 rounded-full bg-aram-surface border border-aram-border flex items-center justify-center text-aram-blue shrink-0">
				<span className="material-symbols-outlined text-[20px]">{icon}</span>
			</div>
			<div>
				<h2 className="text-xl font-semibold text-white">{title}</h2>
				<p className="text-[13px] text-aram-muted">{subtitle}</p>
			</div>
		</div>
	);
}

const RULES = [
	{
		icon: 'verified_user',
		badge: 'ON',
		title: 'Curated-only feed',
		body: "Only channels you approve appear in your child's space. Nothing else is searchable or recommended to them.",
		status: 'Enabled',
	},
	{
		icon: 'comments_disabled',
		badge: 'ON',
		title: 'No comments or live chat',
		body: 'Aram never shows YouTube comments, live chat or clickable external links next to a video.',
		status: 'Enabled',
	},
	{
		icon: 'bedtime',
		badge: 'SOON',
		title: 'Bedtime cut-off',
		body: 'Switch to calm audio-only content after a set time. Coming with screen-time controls.',
		status: 'Not available yet',
	},
];

export default function ManageChannelsPage() {
	const { profile, saveChannels } = useParent();
	const { searchTerm } = useSearch();
	const agent = useAgent();
	const [addText, setAddText] = useState('');

	const [channels, setChannels] = useState([]);
	const [tags, setTags] = useState([]);
	const [recommendations, setRecommendations] = useState([]);
	const [loadState, setLoadState] = useState('loading');
	const [recsLoading, setRecsLoading] = useState(true);
	const [pendingId, setPendingId] = useState(null);
	const [saveError, setSaveError] = useState(null);

	const tagKey = profile.tags.join(',');

	useEffect(() => {
		let cancelled = false;
		// The page opens as soon as the channel list and tags are in; the suggestions (the slowest call) fill in afterwards.
		setRecsLoading(true);
		Promise.all([getChannels(), getTags()])
			.then(([loadedChannels, loadedTags]) => {
				if (cancelled) return;
				setChannels(loadedChannels);
				setTags(loadedTags);
				setLoadState('ready');
			})
			.catch(() => {
				if (!cancelled) setLoadState('error');
			});
		getRecommendations()
			.then((loaded) => {
				if (!cancelled) setRecommendations(loaded);
			})
			.catch(() => {
				if (!cancelled) setRecommendations([]);
			})
			.finally(() => {
				if (!cancelled) setRecsLoading(false);
			});
		return () => {
			cancelled = true;
		};
		// eslint-disable-next-line react-hooks/exhaustive-deps -- tags and the child's birth month are the only profile inputs
	}, [tagKey, profile.birthYearMonth]);

	const approvedIds = profile.channelIds ?? [];
	const tagLabel = useMemo(() => Object.fromEntries(tags.map((tag) => [tag.id, tag.label])), [tags]);

	const term = searchTerm.trim().toLowerCase();
	const matchesSearch = (channel) =>
		!term ||
		channel.name.toLowerCase().includes(term) ||
		(channel.description ?? '').toLowerCase().includes(term) ||
		(channel.category ?? '').toLowerCase().includes(term);

	const approvedChannels = approvedIds
		.map((id) => channels.find((channel) => channel.id === id))
		.filter(Boolean);
	const shownApproved = approvedChannels.filter(matchesSearch);
	const shownRecommendations = recommendations
		.filter((recommendation) => !approvedIds.includes(recommendation.channel.id))
		.filter((recommendation) => matchesSearch(recommendation.channel))
		.slice(0, MAX_RECOMMENDED);

	async function updateApproved(nextIds, channelId) {
		setPendingId(channelId);
		setSaveError(null);
		try {
			await saveChannels(nextIds);
			// Suggestions depend on what is approved ("Similar to ..."), so refresh them; keep the old list if this fails.
			getRecommendations().then(setRecommendations, () => {});
		} catch {
			setSaveError("We couldn't save that change. Please try again.");
		} finally {
			setPendingId(null);
		}
	}

	const emptyTags = tags.filter((tag) => profile.tags.includes(tag.id) && tag.channelCount === 0);
	const childName = profile.childName;

	if (loadState === 'loading') {
		return <Loader size="lg" />;
	}
	if (loadState === 'error') {
		return <p className="p-6 text-sm text-aram-danger">We couldn&rsquo;t load channels right now. Please refresh.</p>;
	}

	return (
		<div className="p-4 flex flex-col gap-8 max-w-7xl mx-auto w-full pb-12">
			<div className="flex flex-col sm:flex-row sm:items-end justify-between gap-3 pb-2 border-b border-aram-border">
				<div className="flex flex-col gap-1">
					<div className="flex items-center gap-2 text-aram-blue">
						<span className="material-symbols-outlined text-[20px] filled">verified</span>
						<span className="text-[12px] uppercase tracking-wider font-semibold">Your choices. Your child&rsquo;s little world.</span>
					</div>
					<h1 className="text-2xl font-semibold text-white tracking-tight">Build Your Child&rsquo;s Learning Library</h1>
					<p className="text-sm text-aram-muted">
						Discover channels and podcasts, review them for your child&rsquo;s age, and choose what belongs in their space.
					</p>
				</div>
				<div className="flex items-center gap-2 shrink-0">
					<span className="text-[13px] text-aram-muted bg-aram-surface border border-aram-border px-3.5 py-1.5 rounded-full flex items-center gap-1.5">
						<span className="w-2 h-2 rounded-full bg-aram-blue animate-pulse" />
						<span>
							{approvedChannels.length} Active {approvedChannels.length === 1 ? 'Channel' : 'Channels'}
						</span>
					</span>
					<span className="text-[13px] text-aram-muted bg-aram-surface border border-aram-border px-3.5 py-1.5 rounded-full">
						Curated for Age {profile.childAge}
					</span>
				</div>
			</div>

			<div className="flex items-start gap-3 rounded-2xl bg-aram-surface border border-aram-border px-4 py-3">
				<span className="material-symbols-outlined text-[22px] text-aram-blue mt-0.5">shield</span>
				<div className="flex flex-col">
					<span className="text-[13px] font-semibold text-white">Reviewed for age and suitability</span>
					<span className="text-[13px] text-aram-muted">
						Channels and podcasts are assessed for your child&rsquo;s age and suitability before you approve them.
					</span>
				</div>
			</div>

			{saveError && <p className="text-[13px] text-aram-danger">{saveError}</p>}

			<section className="flex flex-col gap-4 w-full">
				<SectionHeader
					icon="smart_display"
					title={`Active Channels for ${childName}`}
					subtitle="These appear on the home feed in Kid Space."
				/>
				<div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
					<form
						className="flex flex-col justify-center items-center rounded-2xl border-2 border-dashed border-aram-border-strong hover:border-aram-blue bg-aram-surface p-6 text-center min-h-[260px] transition-colors"
						onSubmit={(event) => {
							event.preventDefault();
							const text = addText.trim();
							agent.open(text || undefined);
							setAddText('');
						}}
					>
						<div className="w-14 h-14 rounded-full bg-aram-surface-3 border border-aram-border flex items-center justify-center text-aram-blue mb-3">
							<span className="material-symbols-outlined text-[28px]">add</span>
						</div>
						<h3 className="text-lg font-semibold text-white mb-1">Add a new channel</h3>
						<p className="text-[13px] text-aram-muted max-w-[250px] mb-3">
							Paste a YouTube link, @handle, Apple Podcasts link or a channel or podcast name. Aram checks it is child-safe before anything is added.
						</p>
						<div className="w-full max-w-xs flex items-center bg-aram-surface-3 border border-aram-border focus-within:border-aram-blue rounded-full pl-3 pr-1 py-1 transition-colors">
							<span className="material-symbols-outlined text-aram-faint text-[18px] mr-1.5">link</span>
							<input
								className="flex-1 min-w-0 bg-transparent outline-none text-[12px] text-white placeholder:text-aram-faint"
								maxLength={500}
								placeholder="https://youtube.com/@..."
								type="text"
								value={addText}
								onChange={(event) => setAddText(event.target.value)}
							/>
							<button
								className="bg-aram-blue hover:bg-aram-blue-hover text-white text-[11px] font-semibold px-3 py-1 rounded-full transition-colors"
								type="submit"
							>
								Chat to Aram
							</button>
						</div>
					</form>

					{shownApproved.map((channel) => {
						const firstTag = channel.tags.find((id) => profile.tags.includes(id)) ?? channel.tags[0];
						return (
							<div
								key={channel.id}
								className="group flex flex-col bg-aram-surface border border-aram-border rounded-2xl overflow-hidden hover:border-[#383838] transition-all duration-300 shadow-md"
							>
								<ChannelCover channel={channel} thumbnailUrl={channel.thumbnailUrl}>
									<Pill className="absolute top-3 left-3 max-w-[calc(100%-5.5rem)] text-aram-blue">
										<span className="material-symbols-outlined text-[14px] shrink-0">smart_display</span>
										<span className="truncate">
											{[channel.sourceType === 'PODCAST_FEED' && 'Podcast', tagLabel[firstTag], formatAgeRange(channel.minAge, channel.maxAge)]
												.filter(Boolean)
												.join(' • ')}
										</span>
									</Pill>
									<Pill className="absolute top-3 right-3 shrink-0 text-white font-medium normal-case">Active</Pill>
								</ChannelCover>
								<div className="p-4 flex flex-col justify-between flex-1 gap-3 bg-aram-surface-2">
									<div className="flex flex-col gap-1">
										<span className="text-[12px] text-aram-muted">{channel.category}</span>
										<h3 className="text-lg font-semibold text-white line-clamp-1">{channel.name}</h3>
										<p className="text-[12px] text-aram-faint line-clamp-2">{channel.description}</p>
									</div>
									<div className="pt-2 border-t border-aram-border flex items-center justify-between text-[12px]">
										<span className="text-aram-muted">{formatAgeRange(channel.minAge, channel.maxAge) ?? ''}</span>
										<button
											className="flex items-center gap-1 text-aram-muted hover:text-aram-danger disabled:opacity-40 transition-colors"
											disabled={pendingId === channel.id}
											type="button"
											onClick={() =>
												updateApproved(
													approvedIds.filter((id) => id !== channel.id),
													channel.id,
												)
											}
										>
											<span className="material-symbols-outlined text-[16px]">remove_circle</span>
											<span>{pendingId === channel.id ? 'Removing...' : 'Remove'}</span>
										</button>
									</div>
								</div>
							</div>
						);
					})}
				</div>
				{approvedChannels.length === 0 && (
					<div className="flex flex-col items-center gap-2 rounded-2xl border border-dashed border-aram-border-strong bg-aram-surface px-6 py-8 text-center">
						<h2 className="text-lg font-semibold text-white">Every little library starts somewhere! 📚</h2>
						<p className="text-sm text-aram-muted max-w-xl">
							Find wonderful channels and podcasts to fill your child&rsquo;s world with stories, science, music, and discovery.
						</p>
						<button className="mt-1 text-sm text-aram-blue font-semibold hover:underline" type="button" onClick={() => agent.open()}>
							Ready to explore?
						</button>
					</div>
				)}
				{approvedChannels.length > 0 && shownApproved.length === 0 && (
					<p className="text-sm text-aram-muted">No approved channels match &ldquo;{searchTerm}&rdquo;.</p>
				)}
			</section>

			<section className="flex flex-col gap-4 w-full pt-2">
				<SectionHeader
					icon="auto_awesome"
					title="Recommended for you"
					subtitle={`Matched to your interests: ${profile.tags.map((id) => tagLabel[id]).filter(Boolean).join(', ')}`}
				/>
				{recsLoading && recommendations.length === 0 ? (
					<Loader size="md" label="Finding channels for you" />
				) : shownRecommendations.length > 0 ? (
					<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
						{shownRecommendations.map(({ channel, matchedTags, ageFit, reason }) => (
							<div
								key={channel.id}
								className="group flex flex-col bg-aram-surface border border-aram-border rounded-2xl overflow-hidden hover:border-[#383838] transition-all duration-300 shadow-sm"
							>
								<div className="relative aspect-[16/10] w-full bg-aram-surface-3 overflow-hidden">
									{channel.thumbnailUrl ? (
										<img
											alt=""
											className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
											loading="lazy"
											src={channel.thumbnailUrl}
										/>
									) : (
										<div className="w-full h-full flex items-center justify-center text-aram-faint">
											<span className="material-symbols-outlined text-[40px]">{iconForCategory(channel.category)}</span>
										</div>
									)}
									<div className="absolute inset-0 bg-gradient-to-t from-black/80 via-transparent to-transparent" />
									<div className="absolute top-2.5 left-2.5 px-2.5 py-0.5 rounded-full bg-aram-blue text-white text-[11px] font-bold">
										{matchedTags.length > 0 ? tagLabel[matchedTags[0]] : 'Similar'}
										{matchedTags.length > 1 ? ` +${matchedTags.length - 1}` : ''}
									</div>
									<div className="absolute bottom-2.5 right-2.5 px-2 py-0.5 rounded bg-black/80 border border-aram-border text-[11px] text-aram-blue">
										{formatAgeRange(channel.minAge, channel.maxAge)}
									</div>
								</div>
								<div className="p-3 flex flex-col justify-between flex-1 gap-2 bg-aram-surface-2">
									<div className="flex flex-col gap-1">
										<h3 className="text-[15px] font-semibold text-white line-clamp-1">
											{channel.name}
											{channel.sourceType === 'PODCAST_FEED' && (
												<span className="ml-2 align-middle text-[10px] font-semibold text-aram-blue bg-aram-blue/10 px-2 py-0.5 rounded-full">
													Podcast
												</span>
											)}
										</h3>
										<p className="text-[12px] text-aram-faint line-clamp-2">{channel.description}</p>
										{reason && (
											<p className="flex items-center gap-1 text-[11px] text-aram-muted">
												<span className="material-symbols-outlined text-[14px]">join_inner</span>
												{reason}
											</p>
										)}
										{!ageFit && (
											<p className="flex items-center gap-1 text-[11px] text-aram-muted">
												<span className="material-symbols-outlined text-[14px]">info</span>
												Aimed a little older than {childName}
											</p>
										)}
									</div>
									<button
										className="mt-1 w-full py-2 px-3 rounded-xl bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-50 text-white text-[12px] font-semibold flex items-center justify-center gap-1.5 transition-colors active:scale-95"
										disabled={pendingId === channel.id}
										type="button"
										onClick={() => updateApproved([...approvedIds, channel.id], channel.id)}
									>
										<span className="material-symbols-outlined text-[16px]">add</span>
										<span>{pendingId === channel.id ? 'Adding...' : `Add to ${childName}'s Learning`}</span>
									</button>
								</div>
							</div>
						))}
					</div>
				) : (
					<p className="text-sm text-aram-muted">
						{term
							? `No suggestions match "${searchTerm}".`
							: 'Every channel that matches your interests is already approved.'}
					</p>
				)}
				{emptyTags.length > 0 && (
					<p className="flex items-start gap-2 text-[13px] text-aram-muted bg-aram-surface border border-aram-border rounded-xl px-4 py-3">
						<span className="material-symbols-outlined text-[18px] text-aram-blue shrink-0">info</span>
						<span>
							We don&rsquo;t have channels for {emptyTags.map((tag) => tag.label).join(', ')} yet.{' '}
							<button className="text-aram-blue font-semibold hover:underline" type="button" onClick={() => agent.open()}>
								Ask the Aram Agent
							</button>{' '}
							to find and check some.
						</span>
					</p>
				)}
			</section>

			<BooksSection childName={childName} refreshKey={`${tagKey}|${profile.birthYearMonth}`} tagLabel={tagLabel} />

			<section className="rounded-2xl bg-aram-surface border border-aram-border p-6 flex flex-col gap-4 shadow-xl">
				<SectionHeader
					icon="verified_user"
					title="Channel rules & safe controls"
					subtitle="What is enforced in Kid Space today"
				/>
				<div className="grid grid-cols-1 md:grid-cols-3 gap-3">
					{RULES.map((rule) => (
						<div
							key={rule.title}
							className="p-4 rounded-xl bg-aram-surface-2 border border-aram-border flex flex-col justify-between gap-3"
						>
							<div className="flex items-start justify-between">
								<div className="w-10 h-10 rounded-xl bg-aram-border flex items-center justify-center text-aram-blue">
									<span className="material-symbols-outlined text-[22px]">{rule.icon}</span>
								</div>
								<span
									className={`text-[11px] font-bold px-2.5 py-0.5 rounded-full ${
										rule.badge === 'ON' ? 'text-aram-blue bg-aram-blue/10' : 'text-white bg-aram-border'
									}`}
								>
									{rule.badge}
								</span>
							</div>
							<div className="flex flex-col">
								<h4 className="text-base text-white font-semibold">{rule.title}</h4>
								<p className="text-[12px] text-aram-muted mt-1">{rule.body}</p>
							</div>
							<div className="pt-2 border-t border-aram-border text-[12px] text-aram-faint">Status: {rule.status}</div>
						</div>
					))}
				</div>
				<p className="text-[12px] text-aram-faint">
					Videos play in YouTube&rsquo;s privacy-enhanced player. YouTube may still show ads on some videos; Aram can&rsquo;t
					remove them.
				</p>
			</section>
		</div>
	);
}
