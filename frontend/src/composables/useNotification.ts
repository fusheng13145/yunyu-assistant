import { ref } from 'vue'

export type NotificationType = 'success' | 'error' | 'warning' | 'info'

const notification = ref({
  show: false,
  message: '',
  type: 'info' as NotificationType,
})

let hideTimer: ReturnType<typeof setTimeout> | null = null

const show = (message: string, type: NotificationType = 'info') => {
  if (hideTimer !== null) clearTimeout(hideTimer)
  notification.value = { show: true, message, type }
  hideTimer = setTimeout(() => {
    hideTimer = null
    notification.value.show = false
  }, 3000)
}

const hide = () => {
  if (hideTimer !== null) {
    clearTimeout(hideTimer)
    hideTimer = null
  }
  notification.value.show = false
}

export function useNotification() {
  return { notification, show, hide }
}
