import { ref, onUnmounted } from 'vue'

interface UseWebSocketOptions {
  url: string
  onMessage?: (data: unknown) => void
  onOpen?: () => void
  onClose?: () => void
  onError?: (error: Event) => void
  reconnectInterval?: number
  maxReconnectAttempts?: number
}

export function useWebSocket(options: UseWebSocketOptions) {
  const {
    url,
    onMessage,
    onOpen,
    onClose,
    onError,
    reconnectInterval = 3000,
    maxReconnectAttempts = 5,
  } = options

  const isConnected = ref(false)
  const reconnectAttempts = ref(0)
  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null

  function connect() {
    try {
      ws = new WebSocket(url)

      ws.onopen = () => {
        isConnected.value = true
        reconnectAttempts.value = 0
        onOpen?.()
      }

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data)
          onMessage?.(data)
        } catch {
          onMessage?.(event.data)
        }
      }

      ws.onclose = () => {
        isConnected.value = false
        onClose?.()
        attemptReconnect()
      }

      ws.onerror = (error) => {
        isConnected.value = false
        onError?.(error)
      }
    } catch {
      attemptReconnect()
    }
  }

  function attemptReconnect() {
    if (reconnectAttempts.value >= maxReconnectAttempts) return
    reconnectAttempts.value++
    reconnectTimer = setTimeout(() => {
      connect()
    }, reconnectInterval * Math.min(reconnectAttempts.value, 3))
  }

  function send(data: unknown) {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(typeof data === 'string' ? data : JSON.stringify(data))
    }
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    isConnected.value = false
  }

  onUnmounted(() => {
    disconnect()
  })

  return { isConnected, connect, send, disconnect }
}
