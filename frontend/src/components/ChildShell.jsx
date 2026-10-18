import { Outlet } from 'react-router-dom';
import Header from './Header';
import Sidebar from './Sidebar';
import { SearchProvider } from '../lib/SearchContext';

export default function ChildShell() {
	return (
		<SearchProvider>
			<Sidebar />
			<div className="pl-72">
				<Header />
				<main className="w-full pt-16 bg-surface min-h-screen px-gutter pb-gutter">
					<div className="max-w-7xl mx-auto pt-space-sm">
						<Outlet />
					</div>
				</main>
			</div>
		</SearchProvider>
	);
}
