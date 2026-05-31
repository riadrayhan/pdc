import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { deviceService, commandService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  Search,
  Filter,
  Lock,
  Unlock,
  Eye,
  Smartphone,
  Wifi,
  WifiOff,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Trash2,
  Download
} from 'lucide-react'

// Backend stores timestamps as naive UTC (datetime.utcnow()) without a 'Z'
// suffix, so the browser would otherwise read them as local time and show the
// wrong value. Append 'Z' when no timezone is present so it is parsed as UTC
// and rendered in the viewer's local timezone.
function formatDateTime(value) {
  if (!value) return 'Never'
  const hasTz = /[zZ]|[+-]\d{2}:?\d{2}$/.test(value)
  const date = new Date(hasTz ? value : `${value}Z`)
  return isNaN(date.getTime()) ? 'Never' : date.toLocaleString()
}

function StatusBadge({ status }) {
  const statusConfig = {
    active: { label: 'Active', className: 'bg-green-100 text-green-800' },
    locked: { label: 'Locked', className: 'bg-red-100 text-red-800' },
    pending: { label: 'Pending', className: 'bg-yellow-100 text-yellow-800' },
    inactive: { label: 'Inactive', className: 'bg-gray-100 text-gray-800' },
  }

  const config = statusConfig[status] || statusConfig.inactive

  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${config.className}`}>
      {config.label}
    </span>
  )
}

function DeviceRow({ device, onLock, onUnlock, onDelete, onUpdate, isLocking }) {
  const isOnline = device.is_online
  const isLocked = device.status === 'locked'
  const deviceName = [device.brand || device.manufacturer, device.device_model].filter(Boolean).join(' ') || 'Unknown Device'

  return (
    <tr className="hover:bg-gray-50">
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 bg-gray-100 rounded-lg flex items-center justify-center">
            <Smartphone className="w-5 h-5 text-gray-500" />
          </div>
          <div>
            <p className="text-sm font-medium text-gray-900">{deviceName}</p>
            <p className="text-xs text-gray-500">IMEI: {device.imei || 'N/A'}</p>
            {device.serial_number && <p className="text-xs text-gray-400">S/N: {device.serial_number}</p>}
            {device.battery_level != null && (
              <p className="text-xs text-gray-400">
                🔋 {device.battery_level}%{device.is_charging ? ' ⚡' : ''} | {device.network_type || '-'}
              </p>
            )}
          </div>
        </div>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="flex items-center gap-1.5">
          {isOnline ? (
            <>
              <Wifi className="w-4 h-4 text-green-500" />
              <span className="text-sm text-green-600">Online</span>
            </>
          ) : (
            <>
              <WifiOff className="w-4 h-4 text-gray-400" />
              <span className="text-sm text-gray-500">Offline</span>
            </>
          )}
        </div>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <StatusBadge status={device.status || 'pending'} />
      </td>
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
        {formatDateTime(device.last_seen)}
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="flex items-center gap-2">
          <Link
            to={`/devices/${device.id}`}
            className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded"
            title="View Details"
          >
            <Eye className="w-4 h-4" />
          </Link>
          {isLocked ? (
            <button
              onClick={() => onUnlock(device)}
              disabled={isLocking}
              className="p-1.5 text-green-500 hover:text-green-700 hover:bg-green-50 rounded disabled:opacity-50"
              title="Unlock Device"
            >
              <Unlock className="w-4 h-4" />
            </button>
          ) : (
            <button
              onClick={() => onLock(device)}
              disabled={isLocking}
              className="p-1.5 text-red-500 hover:text-red-700 hover:bg-red-50 rounded disabled:opacity-50"
              title="Lock Device"
            >
              <Lock className="w-4 h-4" />
            </button>
          )}
          <button
            onClick={() => onDelete(device)}
            className="p-1.5 text-red-400 hover:text-red-600 hover:bg-red-50 rounded"
            title="Delete Device"
          >
            <Trash2 className="w-4 h-4" />
          </button>
          <button
            onClick={() => onUpdate(device)}
            className="p-1.5 text-blue-400 hover:text-blue-600 hover:bg-blue-50 rounded"
            title="Push App Update"
          >
            <Download className="w-4 h-4" />
          </button>
        </div>
      </td>
    </tr>
  )
}

export default function Devices() {
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('all')
  const [page, setPage] = useState(1)
  const limit = 10
  const queryClient = useQueryClient()

  const { data, isLoading, error } = useQuery({
    queryKey: ['devices', page, search, statusFilter],
    queryFn: () => deviceService.list({
      skip: (page - 1) * limit,
      limit,
      search: search || undefined,
      status: statusFilter !== 'all' ? statusFilter : undefined,
    }),
    keepPreviousData: true,
    refetchInterval: 2000, // Auto-refresh every 2 seconds
  })

  const lockMutation = useMutation({
    mutationFn: (device) => commandService.lock({
      device_id: device.id,
      reason: 'EMI payment overdue',
      message: 'Your device has been locked due to pending EMI payment. Please contact your dealer.',
    }),
    onSuccess: () => {
      toast.success('Lock command sent successfully')
      queryClient.invalidateQueries(['devices'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to lock device')
    },
  })

  const unlockMutation = useMutation({
    mutationFn: (device) => commandService.unlock({
      device_id: device.id,
      reason: 'EMI payment received',
    }),
    onSuccess: () => {
      toast.success('Unlock command sent successfully')
      queryClient.invalidateQueries(['devices'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to unlock device')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (device) => deviceService.delete(device.id),
    onSuccess: () => {
      toast.success('Device deleted successfully')
      queryClient.invalidateQueries(['devices'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to delete device')
    },
  })

  const updateAppMutation = useMutation({
    mutationFn: (device) => commandService.updateApp({
      device_id: device.id,
      force: false,
      reason: 'OTA update from dashboard',
    }),
    onSuccess: () => {
      toast.success('Update command sent — device will download and install silently')
      queryClient.invalidateQueries(['devices'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to send update command')
    },
  })

  const bulkUpdateMutation = useMutation({
    mutationFn: (deviceIds) => commandService.bulkUpdateApp({
      device_ids: deviceIds,
      force: false,
      reason: 'Bulk OTA update from dashboard',
    }),
    onSuccess: (res) => {
      const count = res?.data?.length || 0
      toast.success(`Update command sent to ${count} devices`)
      queryClient.invalidateQueries(['devices'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to send bulk update')
    },
  })

  const devices = data?.data?.devices || []
  const total = data?.data?.total || devices.length
  const totalPages = Math.ceil(total / limit)
  const isLocking = lockMutation.isLoading || unlockMutation.isLoading

  const handleLock = (device) => {
    if (confirm(`Are you sure you want to lock ${device.device_model || 'this device'}?`)) {
      lockMutation.mutate(device)
    }
  }

  const handleUnlock = (device) => {
    if (confirm(`Are you sure you want to unlock ${device.device_model || 'this device'}?`)) {
      unlockMutation.mutate(device)
    }
  }

  const handleDelete = (device) => {
    if (confirm(`⚠️ Are you sure you want to PERMANENTLY DELETE ${device.device_model || 'this device'} (${device.imei})?\n\nThis will also delete all commands and contracts linked to this device.\n\nThis action CANNOT be undone!`)) {
      deleteMutation.mutate(device)
    }
  }

  const handleUpdate = (device) => {
    if (confirm(`Push OTA app update to ${device.device_model || 'this device'}?\n\nThe device will download the latest APK and install it silently.`)) {
      updateAppMutation.mutate(device)
    }
  }

  const handleBulkUpdate = () => {
    if (devices.length === 0) {
      toast.error('No devices to update')
      return
    }
    if (confirm(`Push OTA update to ALL ${devices.length} devices?\n\nOnline devices update within seconds; offline devices will update the next time they come online (command stays queued).`)) {
      bulkUpdateMutation.mutate(devices.map(d => d.id))
    }
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Devices</h1>
          <p className="text-gray-500 mt-1">Manage all enrolled devices</p>
        </div>
        <button
          onClick={handleBulkUpdate}
          disabled={bulkUpdateMutation.isLoading}
          className="inline-flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 text-sm font-medium"
        >
          <Download className="w-4 h-4" />
          Push Update to All
        </button>
      </div>

      {/* Filters */}
      <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              placeholder="Search by IMEI or model..."
              value={search}
              onChange={(e) => {
                setSearch(e.target.value)
                setPage(1)
              }}
              className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
          <div className="flex items-center gap-2">
            <Filter className="w-5 h-5 text-gray-400" />
            <select
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value)
                setPage(1)
              }}
              className="px-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            >
              <option value="all">All Status</option>
              <option value="active">Active</option>
              <option value="locked">Locked</option>
              <option value="pending">Pending</option>
            </select>
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
          </div>
        ) : error ? (
          <div className="flex items-center justify-center h-64 text-red-500">
            <AlertTriangle className="w-5 h-5 mr-2" />
            Failed to load devices
          </div>
        ) : devices.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-gray-500">
            <Smartphone className="w-12 h-12 mb-3 text-gray-300" />
            <p>No devices found</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Device</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Connection</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Last Seen</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {devices.map((device) => (
                  <DeviceRow
                    key={device.id}
                    device={device}
                    onLock={handleLock}
                    onUnlock={handleUnlock}
                    onDelete={handleDelete}
                    onUpdate={handleUpdate}
                    isLocking={isLocking}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-6 py-4 border-t border-gray-100">
            <p className="text-sm text-gray-500">
              Showing {(page - 1) * limit + 1} to {Math.min(page * limit, total)} of {total} devices
            </p>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page === 1}
                className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <ChevronLeft className="w-5 h-5" />
              </button>
              <span className="text-sm text-gray-600">
                Page {page} of {totalPages}
              </span>
              <button
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                disabled={page === totalPages}
                className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <ChevronRight className="w-5 h-5" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
