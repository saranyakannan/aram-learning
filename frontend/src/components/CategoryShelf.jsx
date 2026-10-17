import VideoCard from './VideoCard';
import InfoTip from './InfoTip';
import Loader from './Loader';

export default function CategoryShelf({ channel, videos, isLoading }) {
	return (
		<section className="flex flex-col gap-space-md w-full">
			<div className="flex items-center gap-1.5">
				<h2 className="font-headline text-headline-md text-on-surface">{channel.name}</h2>
				<InfoTip label={`About ${channel.name}`} text={channel.description} />
			</div>

			{isLoading && <Loader inline size="sm" label="Loading videos" />}

			{!isLoading && videos.length === 0 && (
				<p className="font-body text-body-sm text-on-surface-variant">No videos found yet.</p>
			)}

			{!isLoading && videos.length > 0 && (
				<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-gutter">
					{videos.map((video) => (
						<VideoCard key={video.videoId} video={video} category={channel.category} />
					))}
				</div>
			)}
		</section>
	);
}
