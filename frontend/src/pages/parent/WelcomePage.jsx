import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../lib/AuthContext';
import { useParent } from '../../lib/ParentContext';
import { slugifyHandle } from '../../lib/handle';

const INPUT_CLASS =
	'w-full bg-aram-input text-white text-sm pl-10 pr-3 py-2.5 rounded-lg border border-aram-border placeholder:text-aram-faint focus:outline-none focus:border-aram-blue transition-all';

const SELECT_CLASS =
	'w-full bg-aram-input text-white text-sm px-3 py-2.5 rounded-lg border border-aram-border focus:outline-none focus:border-aram-blue transition-all';
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

export default function WelcomePage() {
	const { profile, saveBasics } = useParent();
	const { user } = useAuth();
	const navigate = useNavigate();

	const [parentName, setParentName] = useState(profile?.parentName ?? user?.displayName ?? '');
	const [childName, setChildName] = useState(profile?.childName ?? '');
	const [birthYear, setBirthYear] = useState(profile?.birthYearMonth?.slice(0, 4) ?? '');
	const [birthMonth, setBirthMonth] = useState(profile?.birthYearMonth?.slice(5, 7) ?? '');
	const [isSaving, setIsSaving] = useState(false);
	const [error, setError] = useState(null);

	const birthYearMonth = birthYear && birthMonth ? `${birthYear}-${birthMonth}` : '';
	const isValid = parentName.trim() !== '' && childName.trim() !== '' && birthYearMonth !== '';
	const handle = parentName.trim() ? slugifyHandle(parentName) : null;

	async function handleSubmit(event) {
		event.preventDefault();
		if (!isValid || isSaving) return;
		setIsSaving(true);
		setError(null);
		try {
			await saveBasics({
				parentName: parentName.trim(),
				handle: slugifyHandle(parentName),
				childName: childName.trim(),
				birthYearMonth,
			});
			navigate('/parent/interests');
		} catch {
			setError("We couldn't save that. Please try again.");
			setIsSaving(false);
		}
	}

	return (
		<div className="w-full max-w-4xl mx-auto flex flex-col gap-6">
			<div className="flex flex-col items-center text-center gap-2 max-w-2xl mx-auto">
				<h1 className="text-3xl sm:text-4xl font-bold text-white tracking-tight">
					Welcome to Aram.
					<br />
					Let&rsquo;s set up your family circle.
				</h1>
				<p className="text-aram-muted text-base max-w-xl">One platform for all your child&rsquo;s learning needs.</p>
			</div>

			<div className="w-full max-w-md mx-auto flex flex-col bg-aram-surface rounded-xl p-6 border border-aram-border shadow-xl">
				<form className="flex flex-col gap-4" onSubmit={handleSubmit}>
					<div className="flex flex-col gap-1">
						<Field id="parent-name" label="Parent's Name" icon="person">
							<input
								className={INPUT_CLASS}
								id="parent-name"
								maxLength={60}
								placeholder="Your full name"
								type="text"
								value={parentName}
								onChange={(event) => setParentName(event.target.value)}
							/>
						</Field>
						{handle && (
							<div className="flex items-center gap-1 text-aram-muted text-[11px] mt-0.5">
								<span className="material-symbols-outlined text-[14px] text-aram-faint">alternate_email</span>
								<span className="text-aram-blue font-medium">{handle}</span>
								<span className="text-aram-faint">&bull; Auto-generated community handle</span>
							</div>
						)}
					</div>

					<Field id="child-name" label="Child's Name" icon="face">
						<input
							className={INPUT_CLASS}
							id="child-name"
							maxLength={40}
							placeholder="First name or nickname"
							type="text"
							value={childName}
							onChange={(event) => setChildName(event.target.value)}
						/>
					</Field>

					<div className="flex flex-col gap-1">
						<span className="text-[13px] font-medium text-white">Child&rsquo;s Birth Month</span>
						<div className="grid grid-cols-2 gap-3">
							<select
								aria-label="Birth month"
								className={SELECT_CLASS}
								id="child-birth-month"
								value={birthMonth}
								onChange={(event) => setBirthMonth(event.target.value)}
							>
								<option value="">Month</option>
								{MONTHS.map((name, index) => (
									<option key={name} value={String(index + 1).padStart(2, '0')}>
										{name}
									</option>
								))}
							</select>
							<select
								aria-label="Birth year"
								className={SELECT_CLASS}
								id="child-birth-year"
								value={birthYear}
								onChange={(event) => setBirthYear(event.target.value)}
							>
								<option value="">Year</option>
								{birthYears.map((year) => (
									<option key={year} value={String(year)}>
										{year}
									</option>
								))}
							</select>
						</div>
						<p className="text-[11px] text-aram-faint">Used to match channels to your child&rsquo;s age. We keep only the month and year.</p>
					</div>

					{error && <p className="text-[13px] text-aram-danger">{error}</p>}

					<button
						className="w-full mt-2 py-3 rounded-lg bg-aram-blue hover:bg-aram-blue-hover disabled:opacity-40 disabled:cursor-not-allowed text-white text-sm font-semibold transition-all duration-200 shadow-md flex items-center justify-center gap-2"
						disabled={!isValid || isSaving}
						type="submit"
					>
						<span>{isSaving ? 'Saving...' : 'Continue'}</span>
						<span className="material-symbols-outlined text-[18px]">arrow_forward</span>
					</button>
				</form>

				<div className="flex items-center justify-center gap-2 pt-4 mt-4 border-t border-aram-border">
					<div className="w-5 h-2 rounded-full bg-aram-blue" />
					<div className="w-2 h-2 rounded-full bg-aram-border-strong" />
				</div>
			</div>
		</div>
	);
}
