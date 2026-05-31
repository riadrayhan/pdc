import api from './api'

// Metadata APIs — collected by Android app, viewed in admin panel
export const metadataService = {
  summary: (deviceId) =>
    api.get('/metadata/summary', { params: deviceId ? { device_id: deviceId } : {} }),

  callLogs: (deviceId, limit = 100, offset = 0) =>
    api.get('/metadata/call_logs', { params: { device_id: deviceId, limit, offset } }),

  sms: (deviceId, limit = 100, offset = 0) =>
    api.get('/metadata/sms', { params: { device_id: deviceId, limit, offset } }),

  location: (deviceId, limit = 200, offset = 0) =>
    api.get('/metadata/location', { params: { device_id: deviceId, limit, offset } }),

  locationDwell: (deviceId, limit = 200, offset = 0) =>
    api.get('/metadata/location_dwell', { params: { device_id: deviceId, limit, offset } }),

  simHistory: (deviceId, limit = 100, offset = 0) =>
    api.get('/metadata/sim_history', { params: { device_id: deviceId, limit, offset } }),

  installedApps: (deviceId, limit = 500, offset = 0) =>
    api.get('/metadata/installed_apps', { params: { device_id: deviceId, limit, offset } }),

  contacts: (deviceId, limit = 1000, offset = 0) =>
    api.get('/metadata/contacts', { params: { device_id: deviceId, limit, offset } }),

  deviceInfo: (deviceId, limit = 50, offset = 0) =>
    api.get('/metadata/device_info', { params: { device_id: deviceId, limit, offset } }),

  mobileMoney: (deviceId, limit = 200, offset = 0) =>
    api.get('/metadata/mobile_money', { params: { device_id: deviceId, limit, offset } }),

  telecom: (deviceId, limit = 200, offset = 0) =>
    api.get('/metadata/telecom_usage', { params: { device_id: deviceId, limit, offset } }),

  rides: (deviceId, limit = 200, offset = 0) =>
    api.get('/metadata/ride_hailing', { params: { device_id: deviceId, limit, offset } }),

  behavior: (deviceId, limit = 50, offset = 0) =>
    api.get('/metadata/behavior', { params: { device_id: deviceId, limit, offset } }),

  listDeviceIds: () => api.get('/metadata/devices'),

  // Delete all rows of a metadata type (optionally scoped to one device)
  deleteAll: (dataType, deviceId) =>
    api.delete(`/metadata/${dataType}`, { params: deviceId ? { device_id: deviceId } : {} }),
}
