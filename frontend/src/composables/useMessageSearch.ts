import { ref } from 'vue'
import type { DisplayMessage } from '../types'

export function useMessageSearch() {
  const searchQuery = ref('')
  const isSearchActive = ref(false)
  /** 搜索结果（命中的消息列表，供模板直接渲染） */
  const searchResults = ref<DisplayMessage[]>([])

  function searchMessages(messages: DisplayMessage[]): DisplayMessage[] {
    const query = searchQuery.value.trim()
    if (!query) {
      searchResults.value = []
      return []
    }

    const lowerQuery = query.toLowerCase()
    const results: DisplayMessage[] = []

    messages.forEach((message) => {
      const lowerText = (message.text || '').toLowerCase()
      if (lowerText.includes(lowerQuery)) {
        results.push({
          ...message,
          text: highlightKeyword(message.text, query),
          isStreaming: false,
        })
      }
    })

    searchResults.value = results
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
    searchResults.value = []
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
