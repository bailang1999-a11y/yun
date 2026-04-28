import type { H5Order } from '../types/h5'

export interface OrderRealtimeEvent {
  type: 'ORDER_UPDATED' | 'PONG'
  order?: H5Order
  emittedAt?: string
}

export function subscribeOrderEvents(onEvent: (event: OrderRealtimeEvent) => void) {
  let socket: WebSocket | undefined
  let heartbeat: number | undefined
  let closed = false

  function connect() {
    socket = new WebSocket(realtimeUrl())
    socket.addEventListener('open', () => {
      heartbeat = window.setInterval(() => {
        if (socket?.readyState === WebSocket.OPEN) socket.send('ping')
      }, 25000)
    })
    socket.addEventListener('message', (message) => {
      try {
        onEvent(JSON.parse(String(message.data)) as OrderRealtimeEvent)
      } catch {
        // Ignore malformed development messages.
      }
    })
    socket.addEventListener('close', () => {
      if (heartbeat) window.clearInterval(heartbeat)
      if (!closed) window.setTimeout(connect, 3000)
    })
  }

  connect()

  return () => {
    closed = true
    if (heartbeat) window.clearInterval(heartbeat)
    socket?.close()
  }
}

function realtimeUrl() {
  const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL
  const base = configuredBaseUrl && configuredBaseUrl !== '/api'
    ? configuredBaseUrl
    : (import.meta.env.PROD ? window.location.origin : 'http://localhost:8080')
  const url = new URL('/ws/orders', base)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}
