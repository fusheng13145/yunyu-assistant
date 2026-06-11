import { ref, computed } from 'vue'
import type { DisplayMessage } from '../types'

interface SearchResult {
  message: DisplayMessage
  index: number
  highlightedText: string
}

export function useMessageSearch() {
  const searchQuery = ref('')
  const isSearchActive = ref(false)

  const searchResults = computed<SearchResult[]>(() => {
    if (!searchQuery.value.trim()) return []

    const query = searchQuery.value.toLowerCase()

    // This would receive the full messages list as a parameter in practice
    return []
  })

  function searchMessages(messages: DisplayMessage[]): SearchResult[] {
    if (!searchQuery.value.trim()) return []

    const query = searchQuery.value.toLowerCase()
    const results: SearchResult[] = []

    messages.forEach((message, index) => {
      const lowerText = message.text.toLowerCase()
      if (lowerText.includes(query)) {
        results.push({
          message,
          index,
          highlightedText: highlightKeyword(message.text, searchQuery.value),
        })
      }
    })

    return results
  }

  function highlightKeyword(text: string, keyword: string): string {
    if (!keyword.trim()) return text

    try {
      const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const regex = new RegExp(`(${escaped})`, 'gi')
      return text.replace(regex, '**$1**')
    } catch {
      return text
    }
  }

  function clearSearch() {
    searchQuery.value = ''
    isSearchActive.value = false
  }

  function openSearch() {
    isSearchActive.value = true
  }

  function closeSearch() {
    clearSearch()
  }

  return {
    searchQuery,
    isSearchActive,
    searchResults,
    searchMessages,
    highlightKeyword,
    clearSearch,
    openSearch,
    closeSearch,
  }
}
