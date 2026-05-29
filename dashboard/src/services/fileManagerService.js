import api from './api'

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

export const fileManagerService = {
  start: (deviceId, reason) =>
    api.post('/commands/file-manager/start', { device_id: deviceId, reason }),
  stop: (deviceId, reason) =>
    api.post('/commands/file-manager/stop', { device_id: deviceId, reason }),
  status: (deviceId) => api.get(`/files/status/${deviceId}`),
  adminUrl: (deviceId) => wsUrl(`files/admin/${deviceId}`),
}
