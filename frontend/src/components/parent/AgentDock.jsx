import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAgent } from '../../lib/AgentContext';
import ChatPane from './agent/ChatPane';

/**
 * The always-available chat button in the bottom-right corner of the parent screens. The conversation itself lives on the
 * agent page (left sidebar). With nothing started yet the button opens a small starter box, and sending the first message
 * takes the parent to the page, where the chat continues. Once a chat or pending channels exist, it goes straight to the page.
 */
export default function AgentDock() {
	const agent = useAgent();
	const navigate = useNavigate();
	const location = useLocation();
	const { isOpen, setIsOpen, staged, messages } = agent;
	const hasConversation = messages.length > 0 || staged.length > 0;

	useEffect(() => {
		if (!isOpen) return undefined;
		const onKey = (event) => {
			if (event.key === 'Escape') setIsOpen(false);
		};
		document.addEventListener('keydown', onKey);
		return () => document.removeEventListener('keydown', onKey);
	}, [isOpen, setIsOpen]);

	// The agent page is the agent, so the starter box and the button step aside there.
	if (!agent.ready || location.pathname === '/parent/agent') return null;

	const onButton = () => {
		if (hasConversation) {
			setIsOpen(false);
			navigate('/parent/agent');
		} else {
			setIsOpen(!isOpen);
		}
	};

	const start = (text) => {
		setIsOpen(false);
		navigate('/parent/agent');
		agent.send(text);
	};

	return (
		<>
			{isOpen && !hasConversation && (
				<section
					aria-label="Chat to Aram"
					className="fixed right-6 bottom-24 z-50 w-[380px] max-w-[calc(100vw-2rem)] h-[460px] max-h-[calc(100vh-8rem)] flex flex-col rounded-2xl bg-aram-surface border border-aram-border shadow-2xl overflow-hidden"
				>
					<div className="flex items-center gap-2 px-4 py-2.5 border-b border-aram-border bg-aram-surface-2 shrink-0">
						<span className="text-[13px] font-semibold text-white">Chat to Aram</span>
						<button
							aria-label="Close"
							className="ml-auto w-8 h-8 rounded-full text-aram-muted hover:text-white hover:bg-aram-border flex items-center justify-center transition-colors"
							type="button"
							onClick={() => setIsOpen(false)}
						>
							<span className="material-symbols-outlined text-[18px]">close</span>
						</button>
					</div>
					<div className="flex-1 min-h-0 p-2">
						<ChatPane
							isSending={false}
							messages={agent.conversation}
							onNoneFit={() => {}}
							onSelectCandidate={() => {}}
							onSend={start}
						/>
					</div>
				</section>
			)}

			<button
				aria-label={isOpen && !hasConversation ? 'Close the Aram Agent' : 'Open the Aram Agent'}
				className="fixed right-6 bottom-6 z-50 w-14 h-14 rounded-full bg-aram-blue hover:bg-aram-blue-hover text-white shadow-[0_8px_24px_rgba(0,149,246,0.45)] flex items-center justify-center transition-colors"
				type="button"
				onClick={onButton}
			>
				<span className="material-symbols-outlined text-[28px]">{isOpen && !hasConversation ? 'close' : 'chat'}</span>
				{staged.length > 0 && (
					<span className="absolute -top-1 -right-1 min-w-5 h-5 px-1 rounded-full bg-white text-aram-blue text-[11px] font-bold flex items-center justify-center">
						{staged.length}
					</span>
				)}
			</button>
		</>
	);
}
