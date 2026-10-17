import { formatAgeRange } from '../../../lib/categoryStyle';
import { formatCount } from '../../../lib/format';

export default function ChangesPanel({ staged, tagLabels, childName, childAge, notice, isSaving, onRemove, onDiscardAll, onSave }) {
	return (
		<div className="flex flex-col h-full bg-black rounded-xl overflow-hidden border border-aram-border">
			<div className="px-5 py-3 bg-aram-surface-2 flex items-center gap-3 border-b border-aram-border">
				<div className="w-8 h-8 rounded-full bg-aram-blue/15 text-aram-blue flex items-center justify-center border border-aram-blue/30">
					<span className="material-symbols-outlined text-[18px]">sync</span>
				</div>
				<div className="flex flex-col">
					<span className="text-[13px] font-semibold text-white">Changes</span>
					<span className="text-[11px] text-aram-muted">
						{staged.length === 0
							? 'Nothing waiting for your approval'
							: `${staged.length} ${staged.length === 1 ? 'channel' : 'channels'} waiting for your Save`}
					</span>
				</div>
			</div>

			<div className="flex-1 overflow-y-auto px-5 py-4 flex flex-col gap-3">
				{notice && (
					<p
						className={`flex items-start gap-2 text-[13px] rounded-xl px-4 py-3 border ${
							notice.kind === 'success'
								? 'text-white bg-aram-blue/10 border-aram-blue/30'
								: 'text-aram-danger bg-aram-surface-2 border-aram-danger/40'
						}`}
					>
						<span className="material-symbols-outlined text-[18px] shrink-0">
							{notice.kind === 'success' ? 'check_circle' : 'error'}
						</span>
						<span>{notice.text}</span>
					</p>
				)}

				{staged.length === 0 && !notice && (
					<div className="flex flex-col items-center justify-center text-center gap-2 py-16 text-aram-faint">
						<span className="material-symbols-outlined text-[40px]">playlist_add</span>
						<p className="text-[13px] max-w-[260px]">
							Ask the co-pilot to add a channel. Once it has checked it, it shows up here for you to approve.
						</p>
					</div>
				)}

				{staged.map((pending) => {
					const { facts, safety } = pending;
					const meta = [safety.category, formatAgeRange(safety.estimatedMinAge, safety.estimatedMaxAge)].filter(Boolean);
					return (
						<div key={pending.id} className="rounded-2xl bg-aram-surface-2 border border-aram-border p-4 flex gap-3">
							{facts.thumbnailUrl ? (
								<img alt="" className="w-14 h-14 rounded-xl object-cover shrink-0" src={facts.thumbnailUrl} />
							) : (
								<div className="w-14 h-14 rounded-xl bg-aram-border shrink-0" />
							)}
							<div className="flex flex-col gap-1.5 min-w-0 flex-1">
								<div className="flex items-start justify-between gap-2">
									<div className="min-w-0">
										<h3 className="text-[16px] font-bold text-white truncate">
											{facts.title}
											{pending.sourceType === 'PODCAST_FEED' && (
												<span className="ml-2 align-middle text-[10px] font-semibold text-aram-blue bg-aram-blue/10 px-2 py-0.5 rounded-full">
													Podcast
												</span>
											)}
										</h3>
										<p className="text-[12px] text-aram-muted truncate">
											{[facts.handle, formatCount(facts.subscriberCount) && `${formatCount(facts.subscriberCount)} subscribers`]
												.filter(Boolean)
												.join(' • ')}
										</p>
									</div>
									<button
										aria-label={`Remove ${facts.title} from changes`}
										className="text-aram-faint hover:text-white shrink-0"
										type="button"
										onClick={() => onRemove(pending.id)}
									>
										<span className="material-symbols-outlined text-[18px]">close</span>
									</button>
								</div>
								<p className="flex items-center gap-1 text-[12px] font-semibold text-aram-blue">
									<span className="material-symbols-outlined text-[15px]">verified_user</span>
									{pending.alreadyInCatalog ? "In Aram's list" : `Checked for age ${childAge}`}
									{meta.length > 0 && <span className="text-aram-muted font-normal">&nbsp;&bull; {meta.join(' • ')}</span>}
								</p>
								<p className="text-[12px] text-aram-muted line-clamp-3">{safety.reason}</p>
								{safety.tags?.length > 0 && (
									<div className="flex flex-wrap gap-1.5 pt-0.5">
										{safety.tags.map((tag) => (
											<span key={tag} className="text-[11px] text-white bg-aram-border px-2 py-0.5 rounded-full">
												{tagLabels[tag] ?? tag}
											</span>
										))}
									</div>
								)}
							</div>
						</div>
					);
				})}
			</div>

			<div className="p-4 bg-black border-t border-aram-border flex flex-col gap-3">
				<p className="text-[11px] text-aram-faint">
					Aram reads each channel&rsquo;s description and recent video titles. It can&rsquo;t watch videos or read comments,
					so you make the final call{childName ? ` for ${childName}` : ''}.
				</p>
				<div className="flex items-center gap-3">
					<button
						className="px-4 py-2.5 rounded-xl border border-aram-border text-aram-muted hover:text-white hover:bg-aram-surface-2 disabled:opacity-40 text-[13px] font-medium transition-colors"
						disabled={staged.length === 0 || isSaving}
						type="button"
						onClick={onDiscardAll}
					>
						Discard
					</button>
					<button
						className="flex-1 py-2.5 px-5 rounded-xl bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 disabled:cursor-not-allowed text-white text-[13px] font-semibold flex items-center justify-center gap-2 transition-all active:scale-95"
						disabled={staged.length === 0 || isSaving}
						type="button"
						onClick={onSave}
					>
						<span className="material-symbols-outlined text-[20px]">{isSaving ? 'progress_activity' : 'save'}</span>
						<span>{isSaving ? 'Saving...' : staged.length > 1 ? `Save ${staged.length} channels` : 'Save changes'}</span>
					</button>
				</div>
			</div>
		</div>
	);
}
