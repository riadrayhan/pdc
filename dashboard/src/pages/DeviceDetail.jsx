import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { deviceService, commandService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  ArrowLeft,
  Smartphone,
  Wifi,
  WifiOff,
  Lock,
  Unlock,
  AlertTriangle,
  Bell,
  RefreshCw,
  User,
  Phone,
  MapPin,
  Clock,
  Activity,
  Send,
  EyeOff,
  Eye,
  Power,
  PowerOff,
  Camera,
  CameraOff,
  Navigation,
  ToggleLeft,
  ToggleRight,
  Image,
  Map,
  Trash2,
  Shield,
  MessageSquare,
  Cast
} from 'lucide-react'

function InfoCard({ label, value, icon: Icon }) {
  return (
    <div className="flex items-center gap-3 p-4 bg-gray-50 rounded-lg">
      <div className="w-10 h-10 bg-white rounded-lg flex items-center justify-center shadow-sm">
        <Icon className="w-5 h-5 text-gray-500" />
      </div>
      <div>
        <p className="text-xs text-gray-500">{label}</p>
        <p className="text-sm font-medium text-gray-900">{value || '-'}</p>
      </div>
    </div>
  )
}

function CommandHistoryItem({ command }) {
  const statusConfig = {
    sent: { color: 'text-blue-600', bg: 'bg-blue-100' },
    delivered: { color: 'text-green-600', bg: 'bg-green-100' },
    executed: { color: 'text-green-700', bg: 'bg-green-100' },
    failed: { color: 'text-red-600', bg: 'bg-red-100' },
    pending: { color: 'text-yellow-600', bg: 'bg-yellow-100' },
  }

  const config = statusConfig[command.status] || statusConfig.pending
  const typeIcons = {
    lock: Lock,
    unlock: Unlock,
    warning: Bell,
    sync: RefreshCw,
    hide_app: EyeOff,
    unhide_app: Eye,
    disable_app: PowerOff,
    enable_app: Power,
    gps_track: Navigation,
    camera_on: Camera,
    camera_off: CameraOff,
    uninstall_app: Trash2,
  }
  const Icon = typeIcons[command.command_type] || Send

  return (
    <div className="flex items-start gap-3 p-3 hover:bg-gray-50 rounded-lg">
      <div className={`w-8 h-8 rounded-full flex items-center justify-center ${config.bg}`}>
        <Icon className={`w-4 h-4 ${config.color}`} />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-gray-900 capitalize">{command.command_type}</p>
          <span className={`text-xs ${config.color} capitalize`}>{command.status}</span>
        </div>
        <p className="text-xs text-gray-500 mt-0.5">{command.reason || 'No reason provided'}</p>
        <p className="text-xs text-gray-400 mt-1">
          {new Date(command.created_at).toLocaleString()}
        </p>
      </div>
    </div>
  )
}

function ToggleButton({ label, enabled, onToggle, disabled, activeColor = 'bg-green-500', inactiveColor = 'bg-gray-300', activeIcon: ActiveIcon, inactiveIcon: InactiveIcon, description }) {
  return (
    <div className="flex items-center justify-between p-4 bg-gray-50 rounded-lg">
      <div className="flex items-center gap-3">
        <div className={`w-10 h-10 rounded-lg flex items-center justify-center shadow-sm ${enabled ? activeColor : 'bg-white'}`}>
          {enabled ? (
            <ActiveIcon className="w-5 h-5 text-white" />
          ) : (
            <InactiveIcon className="w-5 h-5 text-gray-400" />
          )}
        </div>
        <div>
          <p className="text-sm font-medium text-gray-900">{label}</p>
          {description && <p className="text-xs text-gray-500">{description}</p>}
        </div>
      </div>
      <button
        onClick={onToggle}
        disabled={disabled}
        className={`relative inline-flex h-7 w-12 items-center rounded-full transition-colors focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed ${enabled ? activeColor : inactiveColor}`}
      >
        <span className={`inline-block h-5 w-5 transform rounded-full bg-white shadow-md transition-transform ${enabled ? 'translate-x-6' : 'translate-x-1'}`} />
      </button>
    </div>
  )
}

export default function DeviceDetail() {
  const { id } = useParams()
  const queryClient = useQueryClient()
  const [showLocationPanel, setShowLocationPanel] = useState(false)
  const [frpAccount, setFrpAccount] = useState('')
  const [customMessage, setCustomMessage] = useState('')

  const { data: deviceData, isLoading, error } = useQuery({
    queryKey: ['device', id],
    queryFn: () => deviceService.get(id),
    refetchInterval: 2000,
  })

  const { data: commandsData } = useQuery({
    queryKey: ['device-commands', id],
    queryFn: () => commandService.getHistory(id, 20),
    refetchInterval: 2000,
  })

  const { data: locationData, refetch: refetchLocation } = useQuery({
    queryKey: ['device-location', id],
    queryFn: () => deviceService.getLocation(id),
    refetchInterval: showLocationPanel ? 3000 : false,
    enabled: showLocationPanel,
  })

  const { data: photoData, refetch: refetchPhoto } = useQuery({
    queryKey: ['device-photo', id],
    queryFn: () => deviceService.getPhoto(id),
    refetchInterval: 5000,
  })

  const lockMutation = useMutation({
    mutationFn: () => commandService.lock({
      device_id: id,
      reason: 'EMI payment overdue',
      message: 'Your device has been locked due to pending EMI payment. Please contact your dealer.',
    }),
    onSuccess: () => {
      toast.success('Lock command sent successfully')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to lock device')
    },
  })

  const unlockMutation = useMutation({
    mutationFn: () => commandService.unlock({
      device_id: id, reason: 'EMI payment received',
    }),
    onSuccess: () => {
      toast.success('Unlock command sent successfully')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to unlock device')
    },
  })

  const warningMutation = useMutation({
    mutationFn: () => commandService.warning({
      device_id: id,
      message: 'Payment reminder: Please make your EMI payment to avoid device lock.',
    }),
    onSuccess: () => {
      toast.success('Warning sent successfully')
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to send warning')
    },
  })

  const blacklistMutation = useMutation({
    mutationFn: () => deviceService.updateStatus(id, {
      status: 'deactivated',
      reason: 'Device blacklisted by admin (tamper / fraud / non-payment)',
    }),
    onSuccess: () => {
      toast.success('Device BLACKLISTED — re-enrollment is now blocked')
      queryClient.invalidateQueries(['device', id])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to blacklist device')
    },
  })

  const reactivateMutation = useMutation({
    mutationFn: () => deviceService.updateStatus(id, {
      status: 'active',
      reason: 'Device reactivated by admin',
    }),
    onSuccess: () => {
      toast.success('Device reactivated')
      queryClient.invalidateQueries(['device', id])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to reactivate device')
    },
  })

  const hideAppMutation = useMutation({
    mutationFn: () => commandService.hideApp({
      device_id: id, reason: 'Admin hide app from launcher',
    }),
    onSuccess: () => {
      toast.success('Hide app command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to hide app') },
  })

  const unhideAppMutation = useMutation({
    mutationFn: () => commandService.unhideApp({
      device_id: id, reason: 'Admin unhide app in launcher',
    }),
    onSuccess: () => {
      toast.success('Unhide app command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to unhide app') },
  })

  const disableAppMutation = useMutation({
    mutationFn: () => commandService.disableApp({
      device_id: id, reason: 'Admin disabled app protections',
    }),
    onSuccess: () => {
      toast.success('Disable app command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to disable app') },
  })

  const enableAppMutation = useMutation({
    mutationFn: () => commandService.enableApp({
      device_id: id, reason: 'Admin enabled app protections',
    }),
    onSuccess: () => {
      toast.success('Enable app command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to enable app') },
  })

  const gpsTrackMutation = useMutation({
    mutationFn: () => commandService.gpsTrack({
      device_id: id, reason: 'Admin GPS tracking request',
    }),
    onSuccess: () => {
      toast.success('GPS tracking command sent - waiting for device location...')
      setShowLocationPanel(true)
      queryClient.invalidateQueries(['device-commands', id])
      setTimeout(() => refetchLocation(), 3000)
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to request GPS') },
  })

  const cameraOnMutation = useMutation({
    mutationFn: () => commandService.cameraOn({
      device_id: id, reason: 'Admin turned on camera',
    }),
    onSuccess: () => {
      toast.success('Camera ON command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to turn on camera') },
  })

  const cameraOffMutation = useMutation({
    mutationFn: () => commandService.cameraOff({
      device_id: id, reason: 'Admin turned off camera',
    }),
    onSuccess: () => {
      toast.success('Camera OFF command sent')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to turn off camera') },
  })

  const uninstallAppMutation = useMutation({
    mutationFn: () => commandService.uninstallApp({
      device_id: id, reason: 'Admin permanently uninstalled app',
    }),
    onSuccess: () => {
      toast.success('Uninstall command sent - app will be permanently removed from device')
      queryClient.invalidateQueries(['device', id])
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to uninstall app') },
  })

  const setFRPMutation = useMutation({
    mutationFn: (account) => commandService.setFRPAccount({
      device_id: id,
      frp_account: account,
      reason: `Set FRP account: ${account}`,
    }),
    onSuccess: () => {
      toast.success('FRP account set successfully — device is now recovery-protected')
      setFrpAccount('')
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to set FRP account') },
  })

  const sendMessageMutation = useMutation({
    mutationFn: (message) => commandService.sendMessage({
      device_id: id,
      message: message,
      reason: 'Custom message from admin',
    }),
    onSuccess: () => {
      toast.success('Message sent to device')
      setCustomMessage('')
      queryClient.invalidateQueries(['device-commands', id])
    },
    onError: (error) => { toast.error(error.response?.data?.detail || 'Failed to send message') },
  })

  const device = deviceData?.data
  const commands = commandsData?.data || []
  const location = locationData?.data
  const photo = photoData?.data
  const isLocked = device?.status === 'locked'
  const isOnline = device?.is_online
  const isAppHidden = device?.is_app_hidden || false
  const isAppDisabled = device?.is_app_disabled || false
  const isCameraActive = device?.camera_active || false
  const isProcessing = lockMutation.isLoading || unlockMutation.isLoading || warningMutation.isLoading || hideAppMutation.isLoading || unhideAppMutation.isLoading || disableAppMutation.isLoading || enableAppMutation.isLoading || gpsTrackMutation.isLoading || cameraOnMutation.isLoading || cameraOffMutation.isLoading || uninstallAppMutation.isLoading || setFRPMutation.isLoading || sendMessageMutation.isLoading

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
      </div>
    )
  }

  if (error || !device) {
    return (
      <div className="flex flex-col items-center justify-center h-64">
        <AlertTriangle className="w-12 h-12 text-red-500 mb-3" />
        <p className="text-gray-500">Device not found</p>
        <Link to="/devices" className="text-primary-600 hover:underline mt-2">
          Back to devices
        </Link>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center gap-4">
        <Link
          to="/devices"
          className="p-2 rounded-lg hover:bg-gray-100 transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </Link>
        <div className="flex-1">
          <h1 className="text-2xl font-bold text-gray-900">
            {[device.manufacturer, device.device_model].filter(Boolean).join(' ') || 'Unknown Device'}
          </h1>
          <p className="text-gray-500">IMEI: {device.imei || 'N/A'}</p>
        </div>
        <div className="flex items-center gap-2">
          <Link
            to={`/live-stream?device=${device.id}`}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-md text-sm font-medium"
          >
            <Cast className="w-4 h-4" /> Live stream
          </Link>
          {isOnline ? (
            <span className="flex items-center gap-1.5 px-3 py-1.5 bg-green-100 text-green-700 rounded-full text-sm font-medium">
              <Wifi className="w-4 h-4" />
              Online
            </span>
          ) : (
            <span className="flex items-center gap-1.5 px-3 py-1.5 bg-gray-100 text-gray-600 rounded-full text-sm font-medium">
              <WifiOff className="w-4 h-4" />
              Offline
            </span>
          )}
          {isLocked && (
            <span className="flex items-center gap-1.5 px-3 py-1.5 bg-red-100 text-red-700 rounded-full text-sm font-medium">
              <Lock className="w-4 h-4" />
              Locked
            </span>
          )}
          {device.status === 'deactivated' && (
            <span className="flex items-center gap-1.5 px-3 py-1.5 bg-rose-700 text-white rounded-full text-sm font-medium">
              <Trash2 className="w-4 h-4" />
              Blacklisted
            </span>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Device Info */}
        <div className="lg:col-span-2 space-y-6">
          {/* Device Details Card */}
          <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            <h2 className="text-lg font-semibold text-gray-900 mb-4">Device Information</h2>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <InfoCard label="Manufacturer" value={device.manufacturer} icon={Smartphone} />
              <InfoCard label="Model" value={device.device_model} icon={Smartphone} />
              <InfoCard label="Brand" value={device.brand} icon={Smartphone} />
              <InfoCard label="Device Name" value={device.device_name} icon={Smartphone} />
              <InfoCard label="IMEI" value={device.imei} icon={Activity} />
              <InfoCard label="IMEI 2" value={device.imei2} icon={Activity} />
              <InfoCard label="Serial Number" value={device.serial_number} icon={Activity} />
              <InfoCard label="Android Version" value={device.android_version} icon={Smartphone} />
              <InfoCard label="App Version" value={device.app_version} icon={Activity} />
              <InfoCard label="Battery" value={device.battery_level != null ? `${device.battery_level}%${device.is_charging ? ' ⚡ Charging' : ''}` : '-'} icon={Activity} />
              <InfoCard label="Network" value={device.network_type ? device.network_type.toUpperCase() : '-'} icon={Wifi} />
              <InfoCard label="Device Owner" value={device.is_device_owner ? '✅ Yes' : '❌ No'} icon={Lock} />
              <InfoCard label="Admin Active" value={device.is_admin_active ? '✅ Yes' : '❌ No'} icon={Lock} />
              <InfoCard label="Status" value={device.status?.toUpperCase()} icon={Activity} />
              <InfoCard label="Factory Resets" value={device.factory_reset_count || '0'} icon={RefreshCw} />
              <InfoCard label="Last Seen" value={device.last_seen ? new Date(device.last_seen).toLocaleString() : 'Never'} icon={Clock} />
              <InfoCard label="Enrolled On" value={device.enrolled_at ? new Date(device.enrolled_at).toLocaleDateString() : device.created_at ? new Date(device.created_at).toLocaleDateString() : '-'} icon={Clock} />
            </div>
          </div>

          {/* Customer Details Card */}
          {device.customer && (
            <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
              <h2 className="text-lg font-semibold text-gray-900 mb-4">Customer Information</h2>
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <InfoCard label="Name" value={device.customer.name} icon={User} />
                <InfoCard label="Phone" value={device.customer.phone} icon={Phone} />
                <InfoCard label="Email" value={device.customer.email} icon={User} />
                <InfoCard label="Address" value={device.customer.address} icon={MapPin} />
              </div>
              <div className="mt-4 pt-4 border-t">
                <Link
                  to={`/customers/${device.customer.id}`}
                  className="text-primary-600 hover:text-primary-700 text-sm font-medium"
                >
                  View Customer Details →
                </Link>
              </div>
            </div>
          )}

          {/* Actions Card */}
          <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            <h2 className="text-lg font-semibold text-gray-900 mb-4">Quick Actions</h2>
            <div className="flex flex-wrap gap-3">
              {isLocked ? (
                <button onClick={() => unlockMutation.mutate()} disabled={isProcessing}
                  className="flex items-center gap-2 px-4 py-2.5 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                  <Unlock className="w-4 h-4" /> Unlock Device
                </button>
              ) : (
                <button onClick={() => { if (confirm('Are you sure you want to lock this device?')) { lockMutation.mutate() } }}
                  disabled={isProcessing}
                  className="flex items-center gap-2 px-4 py-2.5 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                  <Lock className="w-4 h-4" /> Lock Device
                </button>
              )}
              <button onClick={() => warningMutation.mutate()} disabled={isProcessing}
                className="flex items-center gap-2 px-4 py-2.5 bg-yellow-500 text-white rounded-lg hover:bg-yellow-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                <Bell className="w-4 h-4" /> Send Warning
              </button>
              <button onClick={() => { gpsTrackMutation.mutate() }} disabled={isProcessing}
                className="flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                <Navigation className="w-4 h-4" /> Track GPS Location
              </button>
              <button onClick={() => queryClient.invalidateQueries(['device', id])}
                className="flex items-center gap-2 px-4 py-2.5 border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors">
                <RefreshCw className="w-4 h-4" /> Refresh
              </button>
              {device.status === 'deactivated' ? (
                <button
                  onClick={() => { if (confirm('Reactivate this device? Re-enrollment will be allowed again.')) reactivateMutation.mutate() }}
                  disabled={isProcessing || reactivateMutation.isLoading}
                  className="flex items-center gap-2 px-4 py-2.5 bg-emerald-600 text-white rounded-lg hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                  <Shield className="w-4 h-4" /> Reactivate (Remove Blacklist)
                </button>
              ) : (
                <button
                  onClick={() => { if (confirm('BLACKLIST this device permanently?\n\nThe device will be marked DEACTIVATED. Even after factory reset and APK reinstall, the server will REFUSE to re-enroll it. Use this for tamper / fraud / total non-payment cases.')) blacklistMutation.mutate() }}
                  disabled={isProcessing || blacklistMutation.isLoading}
                  className="flex items-center gap-2 px-4 py-2.5 bg-rose-700 text-white rounded-lg hover:bg-rose-800 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                  <Trash2 className="w-4 h-4" /> Blacklist Device
                </button>
              )}
            </div>
          </div>

          {/* Send Custom Message Card */}
          <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center">
                <MessageSquare className="w-5 h-5 text-purple-600" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900">Send Custom Message</h2>
                <p className="text-xs text-gray-500">Send a message to display on the device lock screen</p>
              </div>
            </div>
            <div className="flex gap-3">
              <input
                type="text"
                value={customMessage}
                onChange={(e) => setCustomMessage(e.target.value)}
                placeholder="Enter message to display on lock screen..."
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-purple-500 focus:border-purple-500 outline-none"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && customMessage.trim()) {
                    sendMessageMutation.mutate(customMessage.trim())
                  }
                }}
              />
              <button
                onClick={() => {
                  if (!customMessage.trim()) {
                    toast.error('Please enter a message')
                    return
                  }
                  sendMessageMutation.mutate(customMessage.trim())
                }}
                disabled={isProcessing || !customMessage.trim()}
                className="flex items-center gap-2 px-4 py-2.5 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm font-medium whitespace-nowrap"
              >
                <Send className="w-4 h-4" /> Send Message
              </button>
            </div>
          </div>

          {/* Toggle Controls Card */}
          <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            <h2 className="text-lg font-semibold text-gray-900 mb-4">Device Controls</h2>
            <div className="space-y-3">
              <ToggleButton
                label="App Visibility"
                description={isAppHidden ? 'App is HIDDEN from launcher' : 'App is VISIBLE in launcher'}
                enabled={!isAppHidden}
                onToggle={() => {
                  if (isAppHidden) {
                    unhideAppMutation.mutate()
                  } else {
                    if (confirm('Hide the app from the device launcher?')) {
                      hideAppMutation.mutate()
                    }
                  }
                }}
                disabled={isProcessing}
                activeColor="bg-indigo-500"
                inactiveColor="bg-purple-400"
                activeIcon={Eye}
                inactiveIcon={EyeOff}
              />
              <ToggleButton
                label="App Protection"
                description={isAppDisabled ? 'App protections are DISABLED' : 'App protections are ENABLED'}
                enabled={!isAppDisabled}
                onToggle={() => {
                  if (isAppDisabled) {
                    enableAppMutation.mutate()
                  } else {
                    if (confirm('DISABLE the app? This will remove all protections.')) {
                      disableAppMutation.mutate()
                    }
                  }
                }}
                disabled={isProcessing}
                activeColor="bg-teal-500"
                inactiveColor="bg-orange-400"
                activeIcon={Power}
                inactiveIcon={PowerOff}
              />
              <ToggleButton
                label="Remote Camera"
                description={isCameraActive ? 'Camera is ON - capturing photos' : 'Camera is OFF'}
                enabled={isCameraActive}
                onToggle={() => {
                  if (isCameraActive) {
                    cameraOffMutation.mutate()
                  } else {
                    if (confirm('Turn ON remote camera? This will start capturing photos from the device.')) {
                      cameraOnMutation.mutate()
                    }
                  }
                }}
                disabled={isProcessing}
                activeColor="bg-rose-500"
                inactiveColor="bg-gray-300"
                activeIcon={Camera}
                inactiveIcon={CameraOff}
              />
            </div>
            
            {/* Uninstall App - Permanent Removal */}
            <div className="mt-6 pt-4 border-t border-red-200">
              <div className="bg-red-50 rounded-lg p-4">
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center flex-shrink-0">
                    <Trash2 className="w-5 h-5 text-red-600" />
                  </div>
                  <div className="flex-1">
                    <h3 className="text-sm font-semibold text-red-900">Permanently Uninstall App</h3>
                    <p className="text-xs text-red-600 mt-1">
                      This will remove ALL protections, clear device owner, and permanently uninstall 
                      RR Locker from the device. This action cannot be undone remotely.
                    </p>
                    <button
                      onClick={() => {
                        if (confirm('⚠️ WARNING: This will PERMANENTLY UNINSTALL RR Locker from the device.\n\nAll protections will be removed and the app will be deleted.\n\nThis action CANNOT be undone remotely.\n\nAre you absolutely sure?')) {
                          if (confirm('FINAL CONFIRMATION: Permanently remove RR Locker from this device?')) {
                            uninstallAppMutation.mutate()
                          }
                        }
                      }}
                      disabled={isProcessing}
                      className="mt-3 flex items-center gap-2 px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm font-medium"
                    >
                      <Trash2 className="w-4 h-4" /> Uninstall App Permanently
                    </button>
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Factory Reset Protection (FRP) */}
          <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
            <div className="flex items-center gap-3 mb-4">
              <div className="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center">
                <Shield className="w-5 h-5 text-blue-600" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900">Recovery Mode Protection</h2>
                <p className="text-xs text-gray-500">Factory Reset Protection (FRP) — blocks recovery mode bypass</p>
              </div>
            </div>
            <div className="bg-blue-50 rounded-lg p-4 mb-4">
              <p className="text-sm text-blue-800">
                <strong>How it works:</strong> After setting a Google account, if anyone does a factory reset 
                via recovery mode, the phone will be <strong>permanently locked</strong> and require this 
                Google account to unlock. Without it, the phone is completely useless.
              </p>
            </div>
            <div className="flex gap-3">
              <input
                type="email"
                value={frpAccount}
                onChange={(e) => setFrpAccount(e.target.value)}
                placeholder="Enter Google account email (e.g. admin@gmail.com)"
                className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none"
              />
              <button
                onClick={() => {
                  if (!frpAccount || !frpAccount.includes('@')) {
                    toast.error('Please enter a valid Google account email')
                    return
                  }
                  if (confirm(`Set FRP account to: ${frpAccount}?\n\nAfter any factory reset, this Google account will be required to unlock the phone.`)) {
                    setFRPMutation.mutate(frpAccount)
                  }
                }}
                disabled={isProcessing || !frpAccount}
                className="flex items-center gap-2 px-4 py-2.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors text-sm font-medium whitespace-nowrap"
              >
                <Shield className="w-4 h-4" /> Set FRP Account
              </button>
            </div>
            <p className="text-xs text-gray-400 mt-2">Recovery mode protections are also enforced automatically: DISALLOW_FACTORY_RESET, safe boot blocked, OEM unlock disabled, bootloader locked.</p>
          </div>

          {/* GPS Location Panel */}
          {(showLocationPanel || location?.latitude) && (
            <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                  <Navigation className="w-5 h-5 text-blue-600" /> GPS Location
                </h2>
                <div className="flex items-center gap-2">
                  <button onClick={() => { gpsTrackMutation.mutate() }} disabled={isProcessing}
                    className="flex items-center gap-1 px-3 py-1.5 text-sm bg-blue-100 text-blue-700 rounded-lg hover:bg-blue-200 transition-colors">
                    <RefreshCw className="w-3 h-3" /> Refresh Location
                  </button>
                  <button onClick={() => setShowLocationPanel(false)}
                    className="text-gray-400 hover:text-gray-600 text-sm">
                    Close
                  </button>
                </div>
              </div>
              {location?.latitude && location?.longitude ? (
                <div className="space-y-3">
                  <div className="grid grid-cols-2 gap-4">
                    <div className="p-3 bg-blue-50 rounded-lg">
                      <p className="text-xs text-blue-600 font-medium">Latitude</p>
                      <p className="text-sm font-bold text-blue-900">{location.latitude}</p>
                    </div>
                    <div className="p-3 bg-blue-50 rounded-lg">
                      <p className="text-xs text-blue-600 font-medium">Longitude</p>
                      <p className="text-sm font-bold text-blue-900">{location.longitude}</p>
                    </div>
                  </div>
                  {location.address && (
                    <div className="p-3 bg-gray-50 rounded-lg">
                      <p className="text-xs text-gray-500">Address</p>
                      <p className="text-sm font-medium text-gray-900">{location.address}</p>
                    </div>
                  )}
                  {location.updated_at && (
                    <p className="text-xs text-gray-400">
                      Last updated: {new Date(location.updated_at).toLocaleString()}
                    </p>
                  )}
                  {/* Google Maps embed */}
                  <div className="rounded-lg overflow-hidden border border-gray-200" style={{ height: '300px' }}>
                    <iframe
                      width="100%"
                      height="100%"
                      style={{ border: 0 }}
                      loading="lazy"
                      allowFullScreen
                      referrerPolicy="no-referrer-when-downgrade"
                      src={`https://www.google.com/maps?q=${location.latitude},${location.longitude}&z=15&output=embed`}
                    />
                  </div>
                  <a
                    href={`https://www.google.com/maps?q=${location.latitude},${location.longitude}`}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center gap-2 text-sm text-blue-600 hover:text-blue-700"
                  >
                    <Map className="w-4 h-4" /> Open in Google Maps
                  </a>
                </div>
              ) : (
                <div className="text-center py-8">
                  <Navigation className="w-10 h-10 mx-auto text-gray-300 mb-2 animate-pulse" />
                  <p className="text-sm text-gray-500">Waiting for device location...</p>
                  <p className="text-xs text-gray-400 mt-1">Location will appear here when the device responds</p>
                </div>
              )}
            </div>
          )}

          {/* Camera Photo Panel */}
          {(isCameraActive || photo?.photo_url) && (
            <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-lg font-semibold text-gray-900 flex items-center gap-2">
                  <Camera className="w-5 h-5 text-rose-600" /> Remote Camera
                  {isCameraActive && (
                    <span className="flex items-center gap-1 px-2 py-0.5 bg-red-100 text-red-600 rounded-full text-xs font-medium animate-pulse">
                      <span className="w-2 h-2 bg-red-500 rounded-full"></span> LIVE
                    </span>
                  )}
                </h2>
              </div>
              {photo?.photo_url ? (
                <div className="space-y-3">
                  <div className="rounded-lg overflow-hidden border border-gray-200 bg-black">
                    <img
                      src={photo.photo_url}
                      alt="Device camera capture"
                      className="w-full h-auto max-h-96 object-contain"
                    />
                  </div>
                  {photo.updated_at && (
                    <p className="text-xs text-gray-400">
                      Captured at: {new Date(photo.updated_at).toLocaleString()}
                    </p>
                  )}
                </div>
              ) : (
                <div className="text-center py-8">
                  <Image className="w-10 h-10 mx-auto text-gray-300 mb-2 animate-pulse" />
                  <p className="text-sm text-gray-500">Waiting for camera capture...</p>
                  <p className="text-xs text-gray-400 mt-1">Photo will appear here when the device captures an image</p>
                </div>
              )}
            </div>
          )}
        </div>

        {/* Command History */}
        <div className="lg:col-span-1">
          <div className="bg-white rounded-xl shadow-sm border border-gray-100 sticky top-24">
            <div className="px-6 py-4 border-b border-gray-100">
              <h2 className="text-lg font-semibold text-gray-900">Command History</h2>
            </div>
            <div className="max-h-[500px] overflow-y-auto p-3">
              {commands.length > 0 ? (
                <div className="space-y-1">
                  {commands.map((command) => (
                    <CommandHistoryItem key={command.id} command={command} />
                  ))}
                </div>
              ) : (
                <div className="text-center py-8 text-gray-500">
                  <Send className="w-8 h-8 mx-auto mb-2 text-gray-300" />
                  <p className="text-sm">No commands sent yet</p>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
