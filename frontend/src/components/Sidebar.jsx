import { NavLink } from 'react-router-dom';
import { useParent } from '../lib/ParentContext';
import AramMark from './AramMark';

const SOON_LINKS = [
	{ icon: 'menu_book', label: 'Read / Stories' },
	{ icon: 'bookmark', label: 'Saved' },
];

/** Kid Space's sidebar. Same size, spacing and child card as the parent portal's sidebar, so switching views does not move anything. */
export default function Sidebar() {
	const { profile } = useParent();

	return (
		<aside className="fixed left-0 top-0 h-full w-72 bg-[#0c0c0c] border-r border-aram-border z-50 flex flex-col p-3">
			<div className="flex flex-col gap-3">
				<div className="flex items-center gap-2 px-1 py-1">
					<AramMark size={40} />
					<div className="flex flex-col">
						<span className="text-lg font-bold text-white tracking-tight leading-none">Aram</span>
						<span className="text-[11px] text-aram-blue uppercase tracking-wider font-bold mt-1">Kid Space</span>
					</div>
				</div>

				<div className="p-2 rounded-2xl bg-aram-surface border border-aram-border flex items-center">
					<div className="flex items-center gap-2 min-w-0">
						<div className="w-9 h-9 rounded-full bg-aram-blue flex items-center justify-center text-white text-sm font-bold shrink-0">
							{profile?.childName?.[0]?.toUpperCase()}
						</div>
						<div className="flex flex-col min-w-0">
							<span className="text-[13px] text-white font-semibold truncate">{profile?.childName}</span>
							<span className="text-[11px] text-aram-muted">Age {profile?.childAge}</span>
						</div>
					</div>
				</div>

				<nav className="flex flex-col gap-1 mt-1">
					<NavLink
						end
						to="/"
						className={({ isActive }) =>
							[
								'flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all',
								isActive
									? 'bg-aram-blue text-white font-bold shadow-[0_4px_14px_rgba(0,149,246,0.35)]'
									: 'text-aram-muted hover:bg-aram-surface-2 hover:text-white',
							].join(' ')
						}
					>
						<span className="material-symbols-outlined text-[20px]">home</span>
						<span className="text-[13px] font-medium">Home</span>
					</NavLink>

					{SOON_LINKS.map((link) => (
						<div
							key={link.label}
							aria-disabled="true"
							className="flex items-center justify-between gap-3 px-3 py-2.5 rounded-xl text-aram-faint cursor-not-allowed"
						>
							<div className="flex items-center gap-3">
								<span className="material-symbols-outlined text-[20px]">{link.icon}</span>
								<span className="text-[13px] font-medium">{link.label}</span>
							</div>
							<span className="text-[10px] font-semibold bg-aram-border text-aram-muted px-2 py-0.5 rounded-full">
								Soon
							</span>
						</div>
					))}
				</nav>
			</div>
		</aside>
	);
}
