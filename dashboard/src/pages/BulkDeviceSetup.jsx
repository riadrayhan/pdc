import { useState } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import toast from 'react-hot-toast'
import {
  QrCode,
  Copy,
  CheckCircle,
  Smartphone,
  Shield,
  Download,
  Wifi,
} from 'lucide-react'

const APK_DOWNLOAD_URL = 'https://riadrayhan111-rr-locker-api.hf.space/api/v1/app/download'
const DEVICE_ADMIN = 'com.riad.rrlkr/com.riad.rrlkr.admin.EMIDeviceAdminReceiver'
const APK_CHECKSUM = '9pQNHmp25kjdJUjvb9wHIGlFBR3I2p9I2j8QJXlGdmI'
const APK_SIG_CHECKSUM = 'M3cJdKiSRbG7UPF_EGalAIPWoFlc-86PsVrVtj6jDA4'

function buildProvisioningPayload(wifiSsid, wifiPassword) {
  const payload = {
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': DEVICE_ADMIN,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': APK_DOWNLOAD_URL,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM': APK_CHECKSUM,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM': APK_SIG_CHECKSUM,
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': true,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
  }
  if (wifiSsid) {
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = wifiSsid
    if (wifiPassword) {
      payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 'WPA'
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = wifiPassword
    } else {
      payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 'NONE'
    }
  }
  return JSON.stringify(payload)
}

export default function BulkDeviceSetup() {
  const [wifiSsid, setWifiSsid] = useState('')
  const [wifiPassword, setWifiPassword] = useState('')

  const qrPayload = buildProvisioningPayload(wifiSsid, wifiPassword)

  const copyPayload = () => {
    navigator.clipboard.writeText(qrPayload)
    toast.success('QR payload copied!')
  }

  return (
    <div className="max-w-lg mx-auto space-y-6">
      <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
        <QrCode className="w-7 h-7 text-primary-600" />
        Device Setup (ZTE)
      </h1>

      {/* WiFi Config */}
      <div className="bg-white rounded-xl p-5 shadow-sm border border-gray-100">
        <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2 mb-3">
          <Wifi className="w-4 h-4 text-blue-500" />
          WiFi Configuration (Optional)
        </h3>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-xs text-gray-500 mb-1 block">WiFi Name (SSID)</label>
            <input
              type="text"
              value={wifiSsid}
              onChange={(e) => setWifiSsid(e.target.value)}
              placeholder="e.g. Office_WiFi"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
          <div>
            <label className="text-xs text-gray-500 mb-1 block">Password</label>
            <input
              type="text"
              value={wifiPassword}
              onChange={(e) => setWifiPassword(e.target.value)}
              placeholder="WiFi password"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
            />
          </div>
        </div>
        <p className="text-xs text-gray-400 mt-2">
          Device will auto-connect to this WiFi during setup.
        </p>
      </div>

      {/* QR Code */}
      <div className="bg-white rounded-xl p-8 shadow-sm border border-gray-100 flex flex-col items-center">
        <div className="p-4 bg-white border-2 border-primary-200 rounded-2xl shadow-inner">
          <QRCodeSVG
            value={qrPayload}
            size={300}
            level="L"
            includeMargin={true}
          />
        </div>
        <p className="mt-4 text-sm font-medium text-primary-700">
          Provisioning QR Code
        </p>
        <button
          onClick={copyPayload}
          className="mt-3 px-4 py-2 border border-gray-300 rounded-lg hover:bg-gray-50 flex items-center gap-2 text-sm"
        >
          <Copy className="w-4 h-4" />
          Copy Payload
        </button>
      </div>

      {/* Setup Steps */}
      <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
        <h3 className="text-lg font-semibold text-gray-900 mb-4">Setup Steps</h3>
        <div className="space-y-3">
          {[
            { icon: Smartphone, text: 'Factory reset the device' },
            { icon: QrCode, text: 'On Welcome screen, tap 6 times ? QR scanner opens' },
            { icon: Download, text: 'Scan this QR ? app downloads & installs as Device Owner' },
            { icon: Shield, text: 'Follow on-screen prompts to complete setup' },
            { icon: CheckCircle, text: 'App has full device control — Play Protect bypassed' },
          ].map((step, i) => (
            <div key={i} className="flex items-center gap-3">
              <span className="w-7 h-7 bg-primary-100 text-primary-700 rounded-full flex items-center justify-center text-sm font-bold flex-shrink-0">
                {i + 1}
              </span>
              <step.icon className="w-4 h-4 text-gray-400 flex-shrink-0" />
              <span className="text-sm text-gray-700">{step.text}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Info */}
      <div className="bg-green-50 border border-green-200 rounded-xl p-4">
        <p className="text-xs text-green-800">
          <strong>ZTE provisioning</strong> installs the app as Device Owner during factory reset setup. 
          Play Protect is bypassed during this process. The app gets full device control automatically.
        </p>
      </div>
    </div>
  )
}
