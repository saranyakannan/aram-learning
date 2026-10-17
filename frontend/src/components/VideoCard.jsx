import { Link } from 'react-router-dom';
import { ACCENT, formatRelativeDate } from '../lib/categoryStyle';
import { formatMinutes } from '../lib/format';

export default function VideoCard({ video }) {
	const length = formatMinutes(video.durationSeconds);
	const when = formatRelativeDate(video.publishedAt);
	return (
		<Link
			to={`/watch/${encodeURIComponent(video.channelId)}/${encodeURIComponent(video.videoId)}`}
			className="group flex flex-col bg-surface-container-low rounded-2xl overflow-hidden shadow-sm hover:shadow-lg transition-all duration-300"
		>
			<div className="relative aspect-[16/10] w-full bg-surface-container-highest overflow-hidden">
				{video.thumbnailUrl && (
					<img
						className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
						src={video.thumbnailUrl}
						alt={video.title}
						loading="lazy"
					/>
				)}
				<div className="absolute inset-0 bg-gradient-to-t from-surface-container-lowest/80 via-transparent to-transparent" />
				{length && (
					<div className="absolute bottom-2.5 right-2.5 px-2 py-0.5 rounded bg-black/80 font-headline text-label-sm text-on-surface">
						{length}
					</div>
				)}
			</div>
			<div className="p-space-sm flex flex-col justify-between flex-1 gap-space-xs bg-surface-container">
				<h3
					className={`font-headline text-label-lg text-on-surface line-clamp-2 ${ACCENT.hoverText} transition-colors`}
				>
					{video.title}
				</h3>
				<div className="flex items-center justify-between text-outline font-headline text-label-sm">
					<span className="truncate">{[video.channelTitle, when].filter(Boolean).join(' • ')}</span>
				</div>
			</div>
		</Link>
	);
}
