const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/** 14800000 -> "14.8M"; null/undefined -> null (YouTube hides some subscriber counts). */
export function formatCount(count) {
	return count == null ? null : compact.format(count);
}

/** 754 -> "12:34", 3723 -> "1:02:03"; null/undefined -> null. */
export function formatDuration(totalSeconds) {
	if (totalSeconds == null || totalSeconds < 0) return null;
	const hours = Math.floor(totalSeconds / 3600);
	const minutes = Math.floor((totalSeconds % 3600) / 60);
	const seconds = totalSeconds % 60;
	const pad = (value) => String(value).padStart(2, '0');
	return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${minutes}:${pad(seconds)}`;
}

export const PODCAST = 'PODCAST_FEED';

/** 1250000 -> "1.3M views"; 1 -> "1 view"; null/undefined -> null (older cached entries have no count). */
export function formatViews(count) {
	if (count == null) return null;
	return `${compact.format(count)} ${count === 1 ? 'view' : 'views'}`;
}

/** 45 -> "45 s", 754 -> "13 min", 3900 -> "1 h 5 min"; null/undefined/0 -> null (length unknown). */
export function formatMinutes(totalSeconds) {
	if (totalSeconds == null || totalSeconds <= 0) return null;
	if (totalSeconds < 60) return `${totalSeconds} s`;
	const minutes = Math.round(totalSeconds / 60);
	if (minutes < 60) return `${minutes} min`;
	const hours = Math.floor(minutes / 60);
	const rest = minutes % 60;
	return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`;
}
