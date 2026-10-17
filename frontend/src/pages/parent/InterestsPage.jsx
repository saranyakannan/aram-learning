import { useEffect, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { getTags } from '../../api/aramApi';
import { useParent } from '../../lib/ParentContext';

export default function InterestsPage() {
	const { profile, saveTags } = useParent();
	const navigate = useNavigate();

	const [tags, setTags] = useState([]);
	const [tagsError, setTagsError] = useState(false);
	const [selected, setSelected] = useState(() => new Set(profile?.tags ?? []));
	const [isSaving, setIsSaving] = useState(false);
	const [saveError, setSaveError] = useState(null);

	useEffect(() => {
		let cancelled = false;
		getTags()
			.then((loaded) => {
				if (!cancelled) setTags(loaded);
			})
			.catch(() => {
				if (!cancelled) setTagsError(true);
			});
		return () => {
			cancelled = true;
		};
	}, []);

	if (!profile?.childName) {
		return <Navigate to="/parent/welcome" replace />;
	}

	function toggle(tagId) {
		setSelected((current) => {
			const next = new Set(current);
			if (!next.delete(tagId)) next.add(tagId);
			return next;
		});
	}

	async function handleContinue() {
		if (selected.size === 0 || isSaving) return;
		setIsSaving(true);
		setSaveError(null);
		try {
			await saveTags(tags.filter((tag) => selected.has(tag.id)).map((tag) => tag.id));
			navigate('/parent/channels');
		} catch {
			setSaveError("We couldn't save your picks. Please try again.");
			setIsSaving(false);
		}
	}

	return (
		<div className="relative w-full max-w-xl mx-auto flex flex-col items-center text-center">
			<div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-aram-surface-2 border border-aram-border text-aram-muted font-medium text-xs tracking-wide mb-4">
				<span className="w-2 h-2 rounded-full bg-aram-blue" />
				<span>Step 2 of 2 &bull; Personalize</span>
			</div>
			<h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight mb-2">
				Let&rsquo;s build {profile.childName}&rsquo;s learning space.
			</h1>
			<p className="text-neutral-400 text-sm sm:text-base max-w-md mb-8">
				Select tags to shape daily learning and discovery. Next you&rsquo;ll see suggested channels; nothing is
				added until you choose it.
			</p>

			{tagsError && <p className="text-sm text-aram-danger mb-6">We couldn&rsquo;t load the interests. Please refresh.</p>}

			<div className="flex flex-wrap justify-center gap-2.5 max-w-lg mb-8">
				{tags.map((tag) => {
					const isSelected = selected.has(tag.id);
					return (
						<button
							key={tag.id}
							aria-pressed={isSelected}
							className={[
								'flex items-center gap-1.5 px-4 py-2 rounded-full font-medium text-sm transition-all duration-200 border',
								isSelected
									? 'bg-aram-blue text-white shadow-sm border-aram-blue'
									: 'bg-aram-surface-2 text-neutral-300 hover:bg-[#202020] hover:text-white border-aram-border',
							].join(' ')}
							type="button"
							onClick={() => toggle(tag.id)}
						>
							<span className={`material-symbols-outlined text-[16px] ${isSelected ? '' : 'text-neutral-400'}`}>
								{tag.icon}
							</span>
							<span>{tag.label}</span>
							{isSelected && <span className="material-symbols-outlined text-[15px]">check</span>}
						</button>
					);
				})}
			</div>

			<div className="w-full max-w-sm flex flex-col items-center gap-4">
				{saveError && <p className="text-[13px] text-aram-danger">{saveError}</p>}
				<button
					className="w-full py-3.5 px-6 rounded-full bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 disabled:cursor-not-allowed text-white font-semibold text-sm transition-all duration-200 flex items-center justify-center gap-2 shadow-lg"
					disabled={selected.size === 0 || isSaving}
					type="button"
					onClick={handleContinue}
				>
					<span>{isSaving ? 'Saving...' : 'Continue to Aram'}</span>
					<span className="material-symbols-outlined text-[18px]">arrow_forward</span>
				</button>
				{selected.size === 0 && <p className="text-xs text-neutral-500">Pick at least one to continue.</p>}

				<div className="flex items-center justify-center gap-2 pt-1">
					<div className="w-2 h-2 rounded-full bg-aram-border-strong" />
					<div className="w-5 h-2 rounded-full bg-aram-blue" />
				</div>
				<p className="text-xs text-neutral-500">We only store the interests you pick here.</p>
			</div>
		</div>
	);
}
