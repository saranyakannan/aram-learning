import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../lib/AuthContext';
import { useParent } from '../lib/ParentContext';

/** The one place to move between Kid Space and the parent portal. `mode` is the side you are on now. */
export function ViewSwitch({ mode }) {
	const options = [
		{ id: 'kid', to: '/', icon: 'child_care', label: 'Kid Space' },
		{ id: 'parent', to: '/parent', icon: 'shield_person', label: 'Parent' },
	];
	return (
		<nav aria-label="Switch view" className="flex items-center p-1 rounded-full bg-aram-surface border border-aram-border">
			{options.map((option) => {
				const active = option.id === mode;
				return (
					<Link
						key={option.id}
						aria-current={active ? 'page' : undefined}
						className={[
							'flex items-center gap-1.5 px-3 py-1.5 rounded-full text-[12px] font-semibold transition-colors',
							active ? 'bg-aram-blue text-white' : 'text-aram-muted hover:text-white',
						].join(' ')}
						to={option.to}
					>
						<span className="material-symbols-outlined text-[16px]">{option.icon}</span>
						<span className="hidden sm:inline">{option.label}</span>
					</Link>
				);
			})}
		</nav>
	);
}

/** Avatar button with the signed-in parent's name, a link to the profile and sign out. */
export function AccountMenu() {
	const { user, signOut } = useAuth();
	const { profile } = useParent();
	const [open, setOpen] = useState(false);
	const root = useRef(null);

	useEffect(() => {
		if (!open) return undefined;
		const close = (event) => {
			if (event.type === 'keydown' ? event.key === 'Escape' : !root.current?.contains(event.target)) setOpen(false);
		};
		document.addEventListener('mousedown', close);
		document.addEventListener('keydown', close);
		return () => {
			document.removeEventListener('mousedown', close);
			document.removeEventListener('keydown', close);
		};
	}, [open]);

	const name = profile?.parentName || user?.displayName || 'Parent';
	const initial = name.trim()[0]?.toUpperCase() ?? 'P';

	return (
		<div ref={root} className="relative">
			<button
				aria-expanded={open}
				aria-haspopup="menu"
				aria-label="Account menu"
				className="w-9 h-9 rounded-full bg-aram-blue text-white text-sm font-bold flex items-center justify-center hover:bg-aram-blue-hover transition-colors"
				type="button"
				onClick={() => setOpen((value) => !value)}
			>
				{initial}
			</button>
			{open && (
				<div
					className="absolute right-0 top-11 w-64 rounded-2xl bg-aram-surface border border-aram-border shadow-2xl p-2 z-50"
					role="menu"
				>
					<div className="px-3 py-2.5 border-b border-aram-border mb-1">
						<p className="text-[13px] font-semibold text-white truncate">{name}</p>
						{user?.email && <p className="text-[11px] text-aram-muted truncate">{user.email}</p>}
						{profile?.childName && <p className="text-[11px] text-aram-faint mt-0.5">Parent of {profile.childName}</p>}
					</div>
					<Link
						className="flex items-center gap-3 px-3 py-2.5 rounded-xl text-[13px] text-aram-muted hover:bg-aram-surface-3 hover:text-white transition-colors"
						role="menuitem"
						to="/parent/profile"
						onClick={() => setOpen(false)}
					>
						<span className="material-symbols-outlined text-[18px]">manage_accounts</span>
						Profile
					</Link>
					<button
						className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-[13px] text-aram-muted hover:bg-aram-surface-3 hover:text-white transition-colors"
						role="menuitem"
						type="button"
						onClick={signOut}
					>
						<span className="material-symbols-outlined text-[18px]">logout</span>
						Sign out
					</button>
				</div>
			)}
		</div>
	);
}
