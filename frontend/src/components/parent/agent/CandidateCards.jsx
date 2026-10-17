import { formatCount } from '../../../lib/format';

export default function CandidateCards({ candidates, disabled, onSelect, onNoneFit }) {
	return (
		<div className="flex flex-col gap-2 mt-2">
			{candidates.map((channel) => (
				<div
					key={channel.channelId}
					className="flex items-center gap-3 p-3 rounded-xl bg-aram-surface border border-aram-border"
				>
					{channel.thumbnailUrl ? (
						<img alt="" className="w-12 h-12 rounded-full object-cover shrink-0" src={channel.thumbnailUrl} />
					) : (
						<div className="w-12 h-12 rounded-full bg-aram-border shrink-0 flex items-center justify-center text-aram-faint">
							<span className="material-symbols-outlined">
								{channel.kind === 'PODCAST' ? 'podcasts' : 'smart_display'}
							</span>
						</div>
					)}
					<div className="flex flex-col min-w-0 flex-1">
						<span className="text-[14px] font-semibold text-white truncate">{channel.title}</span>
						<span className="text-[12px] text-aram-muted truncate">
							{[channel.handle, formatCount(channel.subscriberCount) && `${formatCount(channel.subscriberCount)} subscribers`]
								.filter(Boolean)
								.join(' • ')}
						</span>
						{channel.kind === 'PODCAST' && (
							<span className="mt-1 w-fit text-[10px] font-semibold text-aram-blue bg-aram-blue/10 px-2 py-0.5 rounded-full">
								Podcast
							</span>
						)}
						{channel.madeForKids && (
							<span className="mt-1 w-fit text-[10px] font-semibold text-aram-blue bg-aram-blue/10 px-2 py-0.5 rounded-full">
								Made for kids
							</span>
						)}
					</div>
					<button
						className="shrink-0 px-3 py-1.5 rounded-full bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 text-white text-[12px] font-semibold transition-colors"
						disabled={disabled}
						type="button"
						onClick={() => onSelect(channel)}
					>
						Select
					</button>
				</div>
			))}
			<button
				className="self-start text-[12px] font-medium text-aram-muted hover:text-white disabled:opacity-40 underline-offset-2 hover:underline"
				disabled={disabled}
				type="button"
				onClick={onNoneFit}
			>
				None of these
			</button>
		</div>
	);
}
