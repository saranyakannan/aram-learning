import { useSearch } from '../lib/SearchContext';
import { AccountMenu, ViewSwitch } from './AccountControls';

/** Kid Space's top bar. Same size and positions as the parent portal's bar, so switching views does not move anything. */
export default function Header() {
	const { searchTerm, setSearchTerm } = useSearch();

	return (
		<header className="fixed top-0 left-72 right-0 h-16 bg-black/90 border-b border-aram-border backdrop-blur-xl z-40 px-6 flex items-center justify-between gap-6 xl:grid xl:grid-cols-[1fr_minmax(0,28rem)_1fr]">
			<div aria-hidden="true" className="hidden xl:block" />
			<div className="w-full max-w-xl xl:max-w-none relative">
				<span className="material-symbols-outlined absolute left-3.5 top-1/2 -translate-y-1/2 text-aram-faint text-[20px]">
					search
				</span>
				<input
					className="w-full h-11 bg-aram-surface border border-aram-border text-white placeholder-aram-faint pl-11 pr-4 rounded-full text-[13px] focus:outline-none focus:border-aram-blue focus:ring-1 focus:ring-aram-blue transition-all"
					placeholder="Search curated channels and videos..."
					type="search"
					value={searchTerm}
					onChange={(event) => setSearchTerm(event.target.value)}
				/>
			</div>
			<div className="flex items-center gap-3 shrink-0 whitespace-nowrap xl:justify-self-end">
				<ViewSwitch mode="kid" />
				<AccountMenu />
			</div>
		</header>
	);
}
