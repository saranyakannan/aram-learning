import ChangesPanel from '../../components/parent/agent/ChangesPanel';
import ChatPane from '../../components/parent/agent/ChatPane';
import { useAgent } from '../../lib/AgentContext';
import { useParent } from '../../lib/ParentContext';

/** The agent at full size: the same conversation and pending changes as the floating chat, side by side. */
export default function AgentPage() {
	const { profile } = useParent();
	const agent = useAgent();

	return (
		<div className="p-4 max-w-7xl mx-auto w-full pb-6">
			<div className="flex flex-wrap items-center gap-3 py-3 mb-2">
				<h1 className="text-2xl font-semibold text-white tracking-tight">Chat to Aram</h1>
				<span className="px-2 py-0.5 rounded-full bg-aram-blue/15 text-aram-blue border border-aram-blue/30 text-[11px] uppercase tracking-wider font-semibold">
					Beta
				</span>
				<span className="w-1.5 h-1.5 rounded-full bg-aram-border-strong" />
				<span className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-aram-surface-2 text-[13px] text-white border border-aram-border">
					<span className="w-2 h-2 rounded-full bg-[#6adf5c]" />
					Managing for {profile.childName} (Age {profile.childAge})
				</span>
				{agent.messages.length > 0 && (
					<button
						className="ml-auto flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[13px] text-aram-blue border border-aram-border hover:bg-aram-surface-2 disabled:opacity-50"
						disabled={agent.isSending}
						type="button"
						onClick={agent.newChat}
					>
						<span className="material-symbols-outlined text-[16px]">add_comment</span>
						New chat
					</button>
				)}
			</div>

			<div className="grid grid-cols-1 xl:grid-cols-12 gap-4 items-start">
				<div className="xl:col-span-7 h-[calc(100vh-11rem)] min-h-[560px]">
					<ChatPane
						isSending={agent.isSending}
						messages={agent.conversation}
						onNoneFit={() => agent.send('None of these are right. Let me try a different name or link.', 'None of these')}
						onSelectCandidate={(channel) =>
							agent.send(
								`I choose ${channel.kind === 'PODCAST' ? 'podcast' : 'channel'} ${channel.channelId} (${channel.title})`,
								`I choose ${channel.title}`,
							)
						}
						onSend={agent.send}
					/>
				</div>
				<div className="xl:col-span-5 h-[calc(100vh-11rem)] min-h-[560px]">
					<ChangesPanel
						childAge={profile.childAge}
						childName={profile.childName}
						isSaving={agent.isSaving}
						notice={agent.notice}
						staged={agent.staged}
						tagLabels={agent.tagLabels}
						onDiscardAll={agent.discardAll}
						onRemove={agent.removeStaged}
						onSave={agent.save}
					/>
				</div>
			</div>
		</div>
	);
}
