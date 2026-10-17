const MAX_LENGTH = 30;

// Community handle preview/value derived from the parent's name; the backend validates the same shape.
export function slugifyHandle(name) {
	const slug = name.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, MAX_LENGTH);
	return slug.length >= 3 ? slug : `${slug}parent`.slice(0, MAX_LENGTH);
}
