import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { emiService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  Smartphone,
  AlertTriangle,
  RefreshCw,
  Copy,
  ExternalLink,
  Shield,
  ShieldOff,
  RotateCcw
} from 'lucide-react'

/**
 * Device Status After Factory Reset
 * Shows which devices were reset and need attention
 */
export default function FactoryResetDevices() {
  const { data, isLoading, refetch } = useQuery({
    queryKey: ['factory-reset-devices'],
    queryFn: () => emiService.getFactoryResetDevices(),
  })

  const devices = data?.data || []

  const copyIMEI = (imei) => {
    navigator.clipboard.writeText(imei)
    toast.success('IMEI copied!')
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Factory Reset Devices</h1>
          <p className="text-gray-500 mt-1">
            Devices that were reset/flashed and need re-enrollment
          </p>
        </div>
        <button
          onClick={() => refetch()}
          className="flex items-center gap-2 px-4 py-2 bg-gray-100 rounded-lg hover:bg-gray-200"
        >
          <RefreshCw className="w-4 h-4" />
          Refresh
        </button>
      </div>

      {/* Info Banner */}
      <div className="bg-yellow-50 border border-yellow-200 rounded-xl p-4">
        <div className="flex items-start gap-3">
          <AlertTriangle className="w-5 h-5 text-yellow-600 mt-0.5" />
          <div>
            <h3 className="font-medium text-yellow-800">Device Reset Detected</h3>
            <p className="text-sm text-yellow-700 mt-1">
              এই devices গুলো factory reset করা হয়েছে। Remotely app install সম্ভব না।
              <br />
              <strong>Solutions:</strong> Zero-Touch Enrollment, Samsung Knox, অথবা physical access দরকার।
            </p>
            <a 
              href="/docs/remote-install" 
              className="inline-flex items-center gap-1 text-sm text-yellow-800 underline mt-2"
            >
              বিস্তারিত দেখুন <ExternalLink className="w-3 h-3" />
            </a>
          </div>
        </div>
      </div>

      {/* Device List */}
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
        </div>
      ) : devices.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-64 text-gray-500">
          <Shield className="w-12 h-12 mb-3 text-green-400" />
          <p className="text-lg font-medium text-green-600">All Devices Secure!</p>
          <p className="text-sm">No factory reset detected</p>
        </div>
      ) : (
        <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
          <table className="w-full">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Device</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">IMEI</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Customer</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Reset Count</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Last Reset</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {devices.map((device) => (
                <tr key={device.id} className="hover:bg-gray-50">
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center">
                        <RotateCcw className="w-5 h-5 text-red-500" />
                      </div>
                      <div>
                        <p className="text-sm font-medium text-gray-900">{device.device_model}</p>
                        <p className="text-xs text-gray-500">{device.manufacturer}</p>
                      </div>
                    </div>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-2">
                      <code className="text-sm bg-gray-100 px-2 py-1 rounded">
                        {device.imei}
                      </code>
                      <button
                        onClick={() => copyIMEI(device.imei)}
                        className="p-1 hover:bg-gray-100 rounded"
                        title="Copy IMEI"
                      >
                        <Copy className="w-4 h-4 text-gray-400" />
                      </button>
                    </div>
                    {device.imei2 && (
                      <p className="text-xs text-gray-400 mt-1">IMEI2: {device.imei2}</p>
                    )}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <p className="text-sm text-gray-900">{device.customer?.name || '-'}</p>
                    <p className="text-xs text-gray-500">{device.customer?.phone || ''}</p>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
                      {device.factory_reset_count || 1}x Reset
                    </span>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
                    {device.last_factory_reset 
                      ? new Date(device.last_factory_reset).toLocaleString()
                      : 'Unknown'}
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className={`inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-xs font-medium ${
                      device.is_online 
                        ? 'bg-green-100 text-green-800'
                        : 'bg-gray-100 text-gray-800'
                    }`}>
                      {device.is_online ? '🟢 Online' : '⚫ Offline'}
                    </span>
                  </td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <div className="flex items-center gap-2">
                      <button
                        className="text-xs px-3 py-1.5 bg-blue-50 text-blue-600 rounded-lg hover:bg-blue-100"
                        onClick={() => {
                          // Open Zero-Touch portal with IMEI
                          window.open(`https://partner.android.com/zerotouch`, '_blank')
                        }}
                      >
                        Add to Zero-Touch
                      </button>
                      <button
                        className="text-xs px-3 py-1.5 bg-purple-50 text-purple-600 rounded-lg hover:bg-purple-100"
                        onClick={() => {
                          // Open Knox portal
                          window.open(`https://www.samsungknox.com/`, '_blank')
                        }}
                      >
                        Add to Knox
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Help Section */}
      <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">📋 কি করবেন?</h3>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <div className="p-4 bg-blue-50 rounded-lg">
            <h4 className="font-medium text-blue-800">Option 1: Zero-Touch</h4>
            <p className="text-sm text-blue-600 mt-1">
              Device IMEI Google Zero-Touch portal এ add করুন। পরের reset এ auto-install হবে।
            </p>
          </div>
          <div className="p-4 bg-purple-50 rounded-lg">
            <h4 className="font-medium text-purple-800">Option 2: Samsung Knox</h4>
            <p className="text-sm text-purple-600 mt-1">
              Samsung device হলে Knox Mobile Enrollment ব্যবহার করুন।
            </p>
          </div>
          <div className="p-4 bg-orange-50 rounded-lg">
            <h4 className="font-medium text-orange-800">Option 3: Physical Access</h4>
            <p className="text-sm text-orange-600 mt-1">
              Customer কে office এ আসতে বলুন। ম্যানুয়ালি app install করুন।
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}
