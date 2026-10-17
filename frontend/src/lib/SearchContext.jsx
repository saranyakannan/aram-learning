import { createContext, useContext, useState } from 'react';

const SearchContext = createContext(null);

export function SearchProvider({ children }) {
	const [searchTerm, setSearchTerm] = useState('');
	return (
		<SearchContext.Provider value={{ searchTerm, setSearchTerm }}>{children}</SearchContext.Provider>
	);
}

export function useSearch() {
	const context = useContext(SearchContext);
	if (!context) {
		throw new Error('useSearch must be used within a SearchProvider');
	}
	return context;
}
