import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/** Token 存储键名 */
const TOKEN_KEY = 'token'
const REFRESH_TOKEN_KEY = 'refreshToken'
const USER_ID_KEY = 'userId'
const USERNAME_KEY = 'username'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const refreshToken = ref(localStorage.getItem(REFRESH_TOKEN_KEY) || '')
  const userId = ref(localStorage.getItem(USER_ID_KEY) || '')
  const username = ref(localStorage.getItem(USERNAME_KEY) || '')
  const selectedAssistantId = ref<string | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  function setAuth(t: string, rt: string, uid: string, uname: string) {
    token.value = t
    refreshToken.value = rt
    userId.value = uid
    username.value = uname
    localStorage.setItem(TOKEN_KEY, t)
    localStorage.setItem(REFRESH_TOKEN_KEY, rt)
    localStorage.setItem(USER_ID_KEY, uid)
    localStorage.setItem(USERNAME_KEY, uname)
  }

  /** 仅刷新访问令牌（令牌续期） */
  function setAccessToken(t: string) {
    token.value = t
    localStorage.setItem(TOKEN_KEY, t)
  }

  function logout() {
    token.value = ''
    refreshToken.value = ''
    userId.value = ''
    username.value = ''
    selectedAssistantId.value = null
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
    localStorage.removeItem(USER_ID_KEY)
    localStorage.removeItem(USERNAME_KEY)
  }

  /** 清除可能被污染的认证数据（检测到异常时调用） */
  function clearSuspiciousAuth() {
    console.warn('[安全] 检测到可疑的认证状态，正在清除')
    logout()
  }

  return { token, refreshToken, userId, username, selectedAssistantId, isLoggedIn, setAuth, setAccessToken, logout, clearSuspiciousAuth }
})
