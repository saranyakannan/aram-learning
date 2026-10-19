import { useState } from 'react';
import { useAuth } from '../../lib/AuthContext';
import { useParent } from '../../lib/ParentContext';
import { slugifyHandle } from '../../lib/handle';

const INPUT_CLASS =
	'w-full bg-aram-input text-white text-sm pl-10 pr-3 py-2.5 rounded-lg border border-aram-border placeholder:text-aram-faint focus:outline-none focus:border-aram-blue transition-all';
const SELECT_CLASS =
	'w-full bg-aram-input text-white text-sm px-3 py-2.5 rounded-lg border border-aram-border focus:outline-none focus:border-aram-blue transition-all';
// The age is fixed for now: the catalog is grounded for 4-year-olds only, with all ages to follow.
const LOCKED = ' disabled:opacity-60 disabled:cursor-not-allowed';
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const currentYear = new Date().getFullYear();
const birthYears = Array.from({ length: 19 }, (_, index) => currentYear - index);

function Field({ id, label, icon, children }) {
	return (
		<div className="flex flex-col gap-1">
			<label className="text-[13px] font-medium text-white" htmlFor={id}>
				{label}
			</label>
			<div className="relative">
				<span className="material-symbols-outlined absolute left-3 top-1/2 -translate-y-1/2 text-aram-faint text-[18px]">
					{icon}
				</span>
				{children}
			</div>
		</div>
	);
}

/** Edit the parent and child details collected during onboarding, and sign out. Interests and approvals are not touched. */
export default function ProfilePage() {
	const { profile, saveBasics } = useParent();
	const { user, signOut } = useAuth();

	const [parentName, setParentName] = useState(profile?.parentName ?? '');
	const [childName, setChildName] = useState(profile?.childName ?? '');
	const [birthYear, setBirthYear] = useState(profile?.birthYearMonth?.slice(0, 4) ?? '');
	const [birthMonth, setBirthMonth] = useState(profile?.birthYearMonth?.slice(5, 7) ?? '');
	const [isSaving, setIsSaving] = useState(false);
	const [saved, setSaved] = useState(false);
	const [error, setError] = useState(null);

	const birthYearMonth = birthYear && birthMonth ? `${birthYear}-${birthMonth}` : '';
	const isValid = parentName.trim() !== '' && childName.trim() !== '' && birthYearMonth !== '';
	const isChanged =
		parentName.trim() !== (profile?.parentName ?? '') ||
		childName.trim() !== (profile?.childName ?? '') ||
		birthYearMonth !== (profile?.birthYearMonth ?? '');

	async function handleSubmit(event) {
		event.preventDefault();
		if (!isValid || !isChanged || isSaving) return;
		setIsSaving(true);
		setError(null);
		setSaved(false);
		try {
			await saveBasics({
				parentName: parentName.trim(),
				handle: slugifyHandle(parentName),
				childName: childName.trim(),
				birthYearMonth,
			});
			setSaved(true);
		} catch {
			setError("We couldn't save that. Please try again.");
		} finally {
			setIsSaving(false);
		}
	}

	return (
		<div className="w-full max-w-2xl mx-auto px-6 py-10 flex flex-col gap-8">
			<div className="flex flex-col gap-1">
				<h1 className="text-3xl font-bold text-white tracking-tight">Profile &amp; settings</h1>
				<p className="text-aram-muted text-sm">Your details and your child&rsquo;s. Interests and approved channels stay as they are.</p>
			</div>

			<section className="flex flex-col gap-4 bg-aram-surface rounded-xl p-6 border border-aram-border">
				<h2 className="text-[15px] font-semibold text-white">Signed in with Google</h2>
				<div className="flex items-center justify-between gap-4">
					<div className="flex items-center gap-3 min-w-0">
						<div className="w-10 h-10 rounded-full bg-aram-blue flex items-center justify-center text-white text-sm font-bold shrink-0">
							{(profile?.parentName ?? '?')[0]?.toUpperCase()}
						</div>
						<div className="min-w-0">
							<p className="text-[13px] text-white font-medium truncate">{user?.displayName ?? profile?.parentName}</p>
							<p className="text-[12px] text-aram-muted truncate">{user?.email}</p>
						</div>
					</div>
					<button
						className="shrink-0 flex items-center gap-2 px-4 py-2 rounded-full bg-aram-border hover:bg-aram-border-strong text-white text-[13px] font-semibold transition-colors"
						type="button"
						onClick={signOut}
					>
						<span className="material-symbols-outlined text-[18px]">logout</span>
						Sign out
					</button>
				</div>
			</section>

			<form className="flex flex-col gap-4 bg-aram-surface rounded-xl p-6 border border-aram-border" onSubmit={handleSubmit}>
				<h2 className="text-[15px] font-semibold text-white">Family details</h2>

				<Field id="parent-name" label="Parent's name" icon="person">
					<input
						className={INPUT_CLASS}
						id="parent-name"
						maxLength={60}
						type="text"
						value={parentName}
						onChange={(event) => setParentName(event.target.value)}
					/>
				</Field>

				<Field id="child-name" label="Child's name" icon="face">
					<input
						className={INPUT_CLASS}
						id="child-name"
						maxLength={40}
						type="text"
						value={childName}
						onChange={(event) => setChildName(event.target.value)}
					/>
				</Field>

				<div className="flex flex-col gap-1">
					<span className="text-[13px] font-medium text-white">Child&rsquo;s birth month</span>
					<div className="grid grid-cols-2 gap-3">
						<select aria-label="Birth month" className={SELECT_CLASS + LOCKED} disabled value={birthMonth} onChange={(event) => setBirthMonth(event.target.value)}>
							<option value="">Month</option>
							{MONTHS.map((name, index) => (
								<option key={name} value={String(index + 1).padStart(2, '0')}>
									{name}
								</option>
							))}
						</select>
						<select aria-label="Birth year" className={SELECT_CLASS + LOCKED} disabled value={birthYear} onChange={(event) => setBirthYear(event.target.value)}>
							<option value="">Year</option>
							{birthYears.map((year) => (
								<option key={year} value={String(year)}>
									{year}
								</option>
							))}
						</select>
					</div>
					<p className="text-[11px] text-aram-faint">Used to match channels to your child&rsquo;s age. We keep only the month and year.</p>
					<p className="text-[12px] font-medium text-amber-300">Fixed at 4 years for now: Aram is currently grounded with content for 4-year-olds only, and we are extending to all ages.</p>
				</div>

				{error && <p className="text-[13px] text-aram-danger">{error}</p>}
				{saved && !isChanged && (
					<p className="flex items-center gap-1.5 text-[13px] text-aram-blue">
						<span className="material-symbols-outlined text-[18px]">check_circle</span>
						Saved
					</p>
				)}

				<button
					className="self-start mt-1 px-6 py-2.5 rounded-lg bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 disabled:cursor-not-allowed text-white text-sm font-semibold transition-all"
					disabled={!isValid || !isChanged || isSaving}
					type="submit"
				>
					{isSaving ? 'Saving...' : 'Save changes'}
				</button>
			</form>
		</div>
	);
}
