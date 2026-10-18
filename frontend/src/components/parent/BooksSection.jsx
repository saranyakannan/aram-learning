import { useEffect, useState } from 'react';
import { getBooks } from '../../api/aramApi';

function ageLabel(months) {
	if (months < 24) return `${months} mo`;
	const years = months / 12;
	return `${Number.isInteger(years) ? years : years.toFixed(1)} yr`;
}

function ageRange(min, max) {
	return `${min === 0 ? 'Newborn' : ageLabel(min)} – ${ageLabel(max)}`;
}

function BookCard({ book, tagLabel }) {
	const [coverFailed, setCoverFailed] = useState(false);
	const matched = book.matchedTags.map((id) => tagLabel[id]).filter(Boolean);

	return (
		<div className="flex flex-col bg-aram-surface border border-aram-border rounded-2xl overflow-hidden hover:border-[#383838] transition-colors shadow-sm">
			<div className="relative aspect-[4/3] w-full bg-aram-surface-3 flex items-center justify-center overflow-hidden">
				{book.coverUrl && !coverFailed ? (
					<img
						alt={`Cover of ${book.title}`}
						className="h-full w-auto max-w-full object-contain"
						loading="lazy"
						src={book.coverUrl}
						onError={() => setCoverFailed(true)}
					/>
				) : (
					<span className="material-symbols-outlined text-[48px] text-aram-faint">menu_book</span>
				)}
				<div className="absolute top-2.5 left-2.5 px-2.5 py-0.5 rounded-full bg-aram-blue text-white text-[11px] font-bold">
					{matched.length > 0 ? matched[0] : book.reason ? 'Similar to your picks' : 'Popular at this age'}
					{matched.length > 1 ? ` +${matched.length - 1}` : ''}
				</div>
				<div className="absolute bottom-2.5 right-2.5 px-2 py-0.5 rounded bg-black/80 border border-aram-border text-[11px] text-aram-blue">
					{ageRange(book.minAgeMonths, book.maxAgeMonths)}
				</div>
			</div>
			<div className="p-3 flex flex-col justify-between flex-1 gap-2 bg-aram-surface-2">
				<div className="flex flex-col gap-1">
					<h3 className="text-[15px] font-semibold text-white line-clamp-2">{book.title}</h3>
					{book.author && <p className="text-[12px] text-aram-muted line-clamp-1">{book.author}</p>}
					<p className="text-[12px] text-aram-faint line-clamp-3">{book.description}</p>
					{book.reason && (
						<p className="flex items-center gap-1 text-[11px] text-aram-muted">
							<span className="material-symbols-outlined text-[14px]">join_inner</span>
							{book.reason}
						</p>
					)}
				</div>
				<a
					className="mt-1 w-full py-2 px-3 rounded-xl bg-aram-blue hover:bg-aram-blue-hover text-white text-[12px] font-semibold flex items-center justify-center gap-1.5 transition-colors active:scale-95"
					href={book.buyUrl}
					rel="noopener noreferrer"
					target="_blank"
				>
					<span className="material-symbols-outlined text-[16px]">shopping_bag</span>
					<span>Buy on {book.store}</span>
				</a>
			</div>
		</div>
	);
}

/** Picture books for the child's age and interests, with a link to buy each. Parent Space only; renders nothing when there are none. */
export default function BooksSection({ childName, tagLabel, refreshKey }) {
	const [books, setBooks] = useState([]);

	useEffect(() => {
		let cancelled = false;
		getBooks()
			.then((loaded) => {
				if (!cancelled) setBooks(loaded);
			})
			.catch(() => {
				if (!cancelled) setBooks([]);
			});
		return () => {
			cancelled = true;
		};
	}, [refreshKey]);

	if (books.length === 0) return null;

	return (
		<section className="flex flex-col gap-4 w-full pt-2">
			<div className="flex items-center gap-2.5">
				<div className="w-9 h-9 rounded-full bg-aram-surface border border-aram-border flex items-center justify-center text-aram-blue shrink-0">
					<span className="material-symbols-outlined text-[20px]">menu_book</span>
				</div>
				<div>
					<h2 className="text-xl font-semibold text-white">Books for {childName}</h2>
					<p className="text-[13px] text-aram-muted">Picture books picked for {childName}&rsquo;s age and interests. Links open the store in a new tab.</p>
				</div>
			</div>
			<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
				{books.map((book) => (
					<BookCard key={book.id} book={book} tagLabel={tagLabel} />
				))}
			</div>
		</section>
	);
}
