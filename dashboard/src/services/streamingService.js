import api from './api'

/**
 * Build the WebSocket URL for /screen/* endpoints based on the current page
 * origin. Works for both `/api/v1` proxied via nginx and direct dev access.
 */
function wsUrl(path) {
  const base = import.meta.env.VITE_API_URL || '/api/v1'
  let origin
  if (/^https?:/i.test(base)) {
    origin = base.replace(/^http/i, 'ws')
  } else {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    origin = `${proto}//${window.location.host}${base.startsWith('/') ? base : '/' + base}`
  }
  if (!origin.endsWith('/')) origin += '/'
  return origin + path.replace(/^\//, '')
}

export const streamingService = {
  startScreenMirror: (deviceId, opts = {}) =>
    api.post('/commands/screen-mirror/start', {
      device_id: deviceId,
      quality: opts.quality ?? 50,
      fps: opts.fps ?? 4,
      scale: opts.scale ?? 0.5,
      reason: opts.reason,
    }),

  stopScreenMirror: (deviceId, reason) =>
    api.post('/commands/screen-mirror/stop', { device_id: deviceId, reason }),

  startAudioStream: (deviceId, opts = {}) =>
    api.post('/commands/audio-stream/start', {
      device_id: deviceId,
      quality: opts.quality ?? 50,
      reason: opts.reason,
    }),

  stopAudioStream: (deviceId, reason) =>
    api.post('/commands/audio-stream/stop', { device_id: deviceId, reason }),

  status: (deviceId) => api.get(`/screen/status/${deviceId}`),

  viewerUrl: (deviceId) => wsUrl(`screen/view/${deviceId}`),
  audioUrl: (deviceId) => wsUrl(`screen/audio/view/${deviceId}`),
}
