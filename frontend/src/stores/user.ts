import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** Token 存储键名 */
const TOKEN_KEY = 'token'
const USER_ID_KEY = 'userId'
const USERNAME_KEY = 'username'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const userId = ref(localStorage.getItem(USER_ID_KEY) || '')
  const username = ref(localStorage.getItem(USERNAME_KEY) || '')
  const selectedAssistantId = ref<string | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(t: string, uid: string, uname: string) {
    token.value = t
    userId.value = uid
    username.value = uname
    localStorage.setItem(TOKEN_KEY, t)
    localStorage.setItem(USER_ID_KEY, uid)
    localStorage.setItem(USERNAME_KEY, uname)
  }

  function logout() {
    token.value = ''
    userId.value = ''
    username.value = ''
    selectedAssistantId.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_ID_KEY)
    localStorage.removeItem(USERNAME_KEY)
  }

  /** 清除可能被污染的认证数据（检测到异常时调用） */
  function clearSuspiciousAuth() {
    console.warn('[安全] 检测到可疑的认证状态，正在清除')
    logout()
  }

  return { token, userId, username, selectedAssistantId, isLoggedIn, setAuth, logout, clearSuspiciousAuth }
})
