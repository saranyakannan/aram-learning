// Maps a curated channel's free-text category to a Material Symbol.
const ICONS_BY_KEYWORD = [
	[/math/i, 'calculate'],
	[/science/i, 'science'],
	[/nature|animal/i, 'forest'],
	[/read|phonic|language|letter/i, 'menu_book'],
	[/music|song/i, 'music_note'],
	[/art|craft/i, 'palette'],
	[/history|social/i, 'public'],
	[/early|toddler/i, 'child_care'],
];

export function iconForCategory(category) {
	const match = ICONS_BY_KEYWORD.find(([pattern]) => pattern.test(category ?? ''));
	return match ? match[1] : 'auto_awesome';
}

// Aram uses a single brand accent (blue) across Kid Space.
export const ACCENT = {
	iconWrap: 'bg-primary/15 text-primary',
	badgeText: 'text-primary',
	hoverText: 'group-hover:text-primary',
};

export function formatAgeRange(minAge, maxAge) {
	if (minAge == null && maxAge == null) return null;
	if (minAge == null) return `Up to age ${maxAge}`;
	if (maxAge == null) return `Ages ${minAge}+`;
	return `Ages ${minAge}–${maxAge}`;
}

export function formatRelativeDate(isoString) {
	if (!isoString) return '';
	const published = new Date(isoString);
	if (Number.isNaN(published.getTime())) return '';

	const diffMs = Date.now() - published.getTime();
	const diffDays = Math.floor(diffMs / (1000 * 60 * 60 * 24));

	if (diffDays <= 0) return 'Today';
	if (diffDays === 1) return 'Yesterday';
	if (diffDays < 7) return `${diffDays}d ago`;
	if (diffDays < 30) return `${Math.floor(diffDays / 7)}w ago`;
	return published.toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}
