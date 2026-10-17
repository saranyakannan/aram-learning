import { Link } from 'react-router-dom';
import { formatRelativeDate } from '../lib/categoryStyle';
import { formatMinutes } from '../lib/format';
import InfoTip from './InfoTip';
import Loader from './Loader';

/** One approved podcast show and its latest episodes; each opens the episode's player page. */
export default function PodcastShelf({ show, episodes, isLoading }) {
	return (
		<section className="flex flex-col gap-space-md w-full">
			<div className="flex items-center gap-1.5">
				<h2 className="font-headline text-headline-md text-on-surface">{show.name}</h2>
				<InfoTip label={`About ${show.name}`} text={show.description} />
			</div>

			{isLoading && <Loader inline size="sm" label="Loading episodes" />}

			{!isLoading && episodes.length === 0 && (
				<p className="font-body text-body-sm text-on-surface-variant">No episodes found yet.</p>
			)}

			{!isLoading && episodes.length > 0 && (
				<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-gutter">
					{episodes.map((episode) => {
						const cover = episode.imageUrl ?? show.thumbnailUrl;
						const duration = formatMinutes(episode.durationSeconds);
						return (
							<Link
								key={`${show.id}:${episode.id}`}
								className="group flex gap-space-sm p-space-sm bg-surface-container hover:bg-surface-container-high rounded-2xl transition-colors"
								to={`/listen/${encodeURIComponent(show.id)}/${encodeURIComponent(episode.id)}`}
							>
								{cover ? (
									<img alt="" className="w-20 h-20 rounded-xl object-cover shrink-0" loading="lazy" src={cover} />
								) : (
									<div className="w-20 h-20 rounded-xl bg-surface-container-highest shrink-0" />
								)}
								<div className="flex flex-col gap-1 min-w-0 flex-1">
									<h3 className="font-headline text-label-lg text-on-surface line-clamp-2 group-hover:text-primary transition-colors">
										{episode.title}
									</h3>
									<p className="font-headline text-label-sm text-outline">
										{[formatRelativeDate(episode.publishedAt), duration].filter(Boolean).join(' • ')}
									</p>
									<span className="mt-1 w-fit flex items-center gap-1 px-3 py-1 rounded-full bg-primary/15 text-primary font-headline text-label-sm">
										<span className="material-symbols-outlined text-base">play_arrow</span>
										Listen
									</span>
								</div>
							</Link>
						);
					})}
				</div>
			)}
		</section>
	);
}
