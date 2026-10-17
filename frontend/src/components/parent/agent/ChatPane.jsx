import { useEffect, useRef, useState } from 'react';
import CandidateCards from './CandidateCards';
import Loader from '../../Loader';

const QUICK_PROMPTS = [
	{ icon: 'link', label: 'Add by YouTube link', text: 'Add https://www.youtube.com/@' },
	{ icon: 'alternate_email', label: 'Add by @handle', text: 'Add @' },
	{ icon: 'search', label: 'Find by name', text: 'Add the channel ' },
	{ icon: 'podcasts', label: 'Add a podcast', text: 'Add the podcast ' },
];

// The model is told to use plain text, but if it still bolds a name with **...** show it bold, not as asterisks.
function renderInline(text) {
	return text.split(/(\*\*[^*]+\*\*)/).map((part, index) =>
		part.startsWith('**') && part.endsWith('**') && part.length > 4 ? (
			<strong key={index}>{part.slice(2, -2)}</strong>
		) : (
			part
		),
	);
}

function Avatar() {
	return (
		<div className="w-7 h-7 rounded-full bg-aram-surface flex-shrink-0 flex items-center justify-center text-aram-blue mt-1 border border-aram-border">
			<span className="material-symbols-outlined text-[16px]">smart_toy</span>
		</div>
	);
}

export default function ChatPane({ messages, isSending, onSend, onSelectCandidate, onNoneFit }) {
	const [draft, setDraft] = useState('');
	const endRef = useRef(null);
	const inputRef = useRef(null);

	useEffect(() => {
		endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
	}, [messages, isSending]);

	const lastAssistantIndex = messages.findLastIndex((message) => message.role === 'assistant');

	function submit(event) {
		event.preventDefault();
		const text = draft.trim();
		if (!text || isSending) return;
		setDraft('');
		onSend(text);
	}

	function fillPrompt(text) {
		setDraft(text);
		inputRef.current?.focus();
	}

	return (
		<div className="flex flex-col h-full bg-black rounded-xl overflow-hidden border border-aram-border">
			<div className="px-5 py-3 bg-aram-surface-2 flex items-center gap-3 border-b border-aram-border">
				<div className="relative w-8 h-8 rounded-full bg-aram-surface flex items-center justify-center text-aram-blue">
					<span className="material-symbols-outlined text-[18px]">smart_toy</span>
					<span className="absolute -bottom-0.5 -right-0.5 w-2.5 h-2.5 rounded-full bg-[#6adf5c] ring-2 ring-aram-surface-2" />
				</div>
				<div className="flex flex-col">
					<span className="text-[13px] font-semibold text-white">Aram Co-Pilot</span>
					<span className="text-[11px] text-aram-muted">Checks every channel before it reaches your child</span>
				</div>
			</div>

			<div className="flex-1 overflow-y-auto px-5 py-5 flex flex-col gap-5">
				{messages.map((message, index) => {
					if (message.role === 'user') {
						return (
							<div key={index} className="flex justify-end">
								<div className="max-w-[85%] px-4 py-3 rounded-2xl rounded-tr-sm bg-aram-blue text-white text-[14px] leading-relaxed font-medium">
									{message.text}
								</div>
							</div>
						);
					}
					const isError = message.role === 'error';
					return (
						<div key={index} className="flex items-start gap-3 max-w-[92%]">
							<Avatar />
							<div className="flex flex-col">
								<div
									className={`px-4 py-3 rounded-2xl rounded-tl-sm text-[14px] leading-relaxed border ${
										isError
											? 'bg-aram-surface-2 border-aram-danger/40 text-aram-danger'
											: 'bg-aram-surface-2 border-aram-border text-white'
									}`}
								>
									{renderInline(message.text)}
								</div>
								{index === lastAssistantIndex && message.candidates?.length > 0 && (
									<CandidateCards
										candidates={message.candidates}
										disabled={isSending}
										onNoneFit={onNoneFit}
										onSelect={onSelectCandidate}
									/>
								)}
							</div>
						</div>
					);
				})}

				{isSending && (
					<div className="flex items-start gap-3">
						<Avatar />
						<div className="px-4 py-3 rounded-2xl rounded-tl-sm bg-aram-surface-2 border border-aram-border text-aram-muted text-[13px] flex items-center gap-2">
							<Loader inline size="sm" label="Looking that up and checking it..." />
						</div>
					</div>
				)}
				<div ref={endRef} />
			</div>

			<div className="px-5 pt-2 pb-2 flex items-center gap-2 overflow-x-auto border-t border-aram-border/50">
				{QUICK_PROMPTS.map((prompt) => (
					<button
						key={prompt.label}
						className="px-3 py-1.5 rounded-full bg-aram-surface-2 text-aram-muted hover:text-white hover:bg-aram-surface-3 transition-colors text-[12px] whitespace-nowrap flex items-center gap-1 border border-aram-border"
						type="button"
						onClick={() => fillPrompt(prompt.text)}
					>
						<span className="material-symbols-outlined text-[14px] text-aram-blue">{prompt.icon}</span>
						{prompt.label}
					</button>
				))}
			</div>

			<form
				className="m-4 mt-2 flex items-center gap-2 bg-aram-surface-2 pl-4 pr-2 py-2 rounded-full border border-aram-border focus-within:border-aram-blue transition-colors"
				onSubmit={submit}
			>
				<input
					ref={inputRef}
					autoComplete="off"
					className="bg-transparent border-0 outline-none w-full text-[14px] text-white placeholder:text-aram-faint"
					maxLength={500}
					placeholder="Paste a YouTube link, @handle or channel name..."
					type="text"
					value={draft}
					onChange={(event) => setDraft(event.target.value)}
				/>
				<button
					aria-label="Send"
					className="w-9 h-9 rounded-full bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 text-white flex items-center justify-center transition-all active:scale-95 shrink-0"
					disabled={!draft.trim() || isSending}
					type="submit"
				>
					<span className="material-symbols-outlined text-[18px]">send</span>
				</button>
			</form>
		</div>
	);
}
