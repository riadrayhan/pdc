import api from './api'

// Device APIs
export const deviceService = {
  list: (params) => api.get('/devices', { params }),
  get: (id) => api.get(`/devices/${id}`),
  updateStatus: (id, data) => api.put(`/devices/${id}/status`, data),
  delete: (id) => api.delete(`/devices/${id}`),
  getFactoryResetDevices: () => api.get('/devices/factory-reset'),
  getLocation: (id) => api.get(`/devices/${id}/location`),
  getPhoto: (id) => api.get(`/devices/${id}/photo`),
}

// Customer APIs
export const customerService = {
  list: (params) => api.get('/customers', { params }),
  get: (id) => api.get(`/customers/${id}`),
  create: (data) => api.post('/customers', data),
  update: (id, data) => api.put(`/customers/${id}`, data),
  delete: (id) => api.delete(`/customers/${id}`),
}

// EMI APIs
export const emiService = {
  listContracts: (params) => api.get('/emi/contracts', { params }),
  getContract: (id) => api.get(`/emi/contracts/${id}`),
  createContract: (data) => api.post('/emi/contracts', data),
  deleteContract: (id) => api.delete(`/emi/contracts/${id}`),
  getPayments: (contractId) => api.get(`/emi/contracts/${contractId}/payments`),
  recordPayment: (paymentId, data) => api.post(`/emi/payments/${paymentId}/record`, data),
  getOverdue: () => api.get('/emi/overdue'),
  getStats: () => api.get('/emi/dashboard/stats'),
  getFactoryResetDevices: () => api.get('/devices/factory-reset'),
}

// Command APIs
export const commandService = {
  lock: (data) => api.post('/commands/lock', data),
  unlock: (data) => api.post('/commands/unlock', data),
  warning: (data) => api.post('/commands/warning', data),
  hideApp: (data) => api.post('/commands/hide-app', data),
  unhideApp: (data) => api.post('/commands/unhide-app', data),
  disableApp: (data) => api.post('/commands/disable-app', data),
  enableApp: (data) => api.post('/commands/enable-app', data),
  gpsTrack: (data) => api.post('/commands/gps-track', data),
  cameraOn: (data) => api.post('/commands/camera-on', data),
  cameraOff: (data) => api.post('/commands/camera-off', data),
  uninstallApp: (data) => api.post('/commands/uninstall-app', data),
  setFRPAccount: (data) => api.post('/commands/set-frp-account', data),
  sendMessage: (data) => api.post('/commands/send-message', data),
  updateApp: (data) => api.post('/commands/update-app', data),
  bulkUpdateApp: (data) => api.post('/commands/update-app/bulk', data),
  getHistory: (deviceId, limit = 50) => api.get(`/commands/${deviceId}`, { params: { limit } }),
  deleteHistory: (deviceId) => api.delete(`/commands/history/${deviceId}`),
  deleteCommand: (commandId) => api.delete(`/commands/single/${commandId}`),
}
