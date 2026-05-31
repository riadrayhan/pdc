import { io } from 'socket.io-client'

/**
 * Resolve the Socket.IO server origin. The realtime layer lives on the same
 * host as the API (Express + Socket.IO share the HTTP server), so we derive the
 * origin from VITE_API_URL (when absolute) or the current page origin.
 */
function realtimeOrigin() {
  const base = import.meta.env.VITE_API_URL || '/api/v1'
  if (/^https?:/i.test(base)) {
    // Strip a trailing /api/v1 — Socket.IO is mounted at the server root.
    return base.replace(/\/?api\/v1\/?$/i, '')
  }
  return window.location.origin
}

/**
 * Open a Socket.IO connection to the /dashboard namespace. The server relays
 * device screen/audio frames and brokers WebRTC signaling + file-manager
 * messages over this single connection.
 */
export function connectDashboard() {
  return io(`${realtimeOrigin()}/dashboard`, {
    transports: ['websocket', 'polling'],
    reconnection: true,
    reconnectionDelay: 1000,
    reconnectionDelayMax: 5000,
  })
}

/**
 * Subscribe to a device's live screen (and optionally audio) stream.
 *
 * Returns a controller with a `close()` method. Frames arrive as
 * `screen-frame` (JPEG ArrayBuffer) and audio as `audio-format` + `audio-frame`
 * (PCM16 ArrayBuffer) events, mirroring the previous WebSocket hub contract.
 */
export function watchScreen(deviceId, { onFrame, onStatus } = {}) {
  const socket = connectDashboard()

  socket.on('connect', () => {
    socket.emit('watch', { device_id: deviceId, mode: 'screen' })
    onStatus?.('connected')
  })
  socket.on('disconnect', () => onStatus?.('disconnected'))
  socket.on('publisher-online', () => onStatus?.('publisher-online'))
  socket.on('publisher-offline', () => onStatus?.('publisher-offline'))
  socket.on('screen-frame', (data) => onFrame?.(data))

  return {
    socket,
    close() {
      try { socket.emit('unwatch', { device_id: deviceId }) } catch { /* noop */ }
      socket.removeAllListeners()
      socket.disconnect()
    },
  }
}

/**
 * Subscribe to a device's live audio stream. PCM16 frames are delivered to
 * `onFrame`; the announced format ({ sample_rate, channels, bits }) to
 * `onFormat`.
 */
export function watchAudio(deviceId, { onFrame, onFormat, onStatus } = {}) {
  const socket = connectDashboard()

  socket.on('connect', () => {
    socket.emit('watch', { device_id: deviceId, mode: 'audio' })
    onStatus?.('connected')
  })
  socket.on('disconnect', () => onStatus?.('disconnected'))
  socket.on('publisher-online', () => onStatus?.('publisher-online'))
  socket.on('publisher-offline', () => onStatus?.('publisher-offline'))
  socket.on('audio-format', (fmt) => onFormat?.(fmt))
  socket.on('audio-frame', (data) => onFrame?.(data))

  return {
    socket,
    close() {
      try { socket.emit('unwatch', { device_id: deviceId }) } catch { /* noop */ }
      socket.removeAllListeners()
      socket.disconnect()
    },
  }
}

/**
 * Open a remote file-manager channel. The device acts as producer; the browser
 * sends JSON requests via `send()` and receives JSON responses through
 * `onMessage`.
 */
export function watchFiles(deviceId, { onMessage, onStatus } = {}) {
  const socket = connectDashboard()

  socket.on('connect', () => {
    socket.emit('watch-files', { device_id: deviceId })
    onStatus?.('connected')
  })
  socket.on('disconnect', () => onStatus?.('disconnected'))
  socket.on('file-message', (text) => {
    try { onMessage?.(typeof text === 'string' ? JSON.parse(text) : text) }
    catch { onMessage?.({ event: 'raw', data: text }) }
  })

  return {
    socket,
    send(obj) {
      socket.emit('file-request', { device_id: deviceId, text: JSON.stringify(obj) })
    },
    close() {
      try { socket.emit('unwatch-files', { device_id: deviceId }) } catch { /* noop */ }
      socket.removeAllListeners()
      socket.disconnect()
    },
  }
}

export default { connectDashboard, watchScreen, watchAudio, watchFiles }
