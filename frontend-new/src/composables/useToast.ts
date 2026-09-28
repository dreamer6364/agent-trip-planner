import { ref } from 'vue'

interface Toast {
  id: number
  message: string
  type: 'success' | 'error' | 'info' | 'warning'
  duration: number
}

const toasts = ref<Toast[]>([])
let nextId = 0

export function useToast() {
  function show(message: string, type: Toast['type'] = 'info', duration = 3000) {
    const id = nextId++
    const toast: Toast = { id, message, type, duration }
    toasts.value.push(toast)
    setTimeout(() => dismiss(id), duration)
    return id
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  function success(message: string, duration = 3000) {
    return show(message, 'success', duration)
  }

  function error(message: string, duration = 4000) {
    return show(message, 'error', duration)
  }

  function info(message: string, duration = 3000) {
    return show(message, 'info', duration)
  }

  function warning(message: string, duration = 3500) {
    return show(message, 'warning', duration)
  }

  return { toasts, show, dismiss, success, error, info, warning }
}
