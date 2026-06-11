import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userId = ref(localStorage.getItem('userId') || '')
  const username = ref(localStorage.getItem('username') || '')
  const selectedAssistantId = ref<string | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(t: string, uid: string, uname: string) {
    token.value = t
    userId.value = uid
    username.value = uname
    localStorage.setItem('token', t)
    localStorage.setItem('userId', uid)
    localStorage.setItem('username', uname)
  }

  function logout() {
    token.value = ''
    userId.value = ''
    username.value = ''
    selectedAssistantId.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userId')
    localStorage.removeItem('username')
  }

  return { token, userId, username, selectedAssistantId, isLoggedIn, setAuth, logout }
})
