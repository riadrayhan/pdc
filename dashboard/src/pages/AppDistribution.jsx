import { useState, useEffect, useRef } from 'react'
import { QrCode, Download, Smartphone, Copy, Check, RefreshCw, ExternalLink, Link } from 'lucide-react'
import { QRCodeSVG } from 'qrcode.react'
import api from '../services/api'

// Backend public URL - used for QR codes that phones will scan
const BACKEND_URL = 'https://rr-locker-api.onrender.com'
const INSTALL_PAGE_URL = `${BACKEND_URL}/api/v1/app/install`
const DIRECT_DOWNLOAD_URL = `${BACKEND_URL}/api/v1/app/download`

export default function AppDistribution() {
  const [appInfo, setAppInfo] = useState(null)
  const [loading, setLoading] = useState(true)
  const [copied, setCopied] = useState(false)
  const [copiedInstall, setCopiedInstall] = useState(false)
  const [copiedDirect, setCopiedDirect] = useState(false)
  const [qrType, setQrType] = useState('install') // 'install' or 'direct'
  const qrRef = useRef(null)

  useEffect(() => {
    fetchAppInfo()
  }, [])

  const fetchAppInfo = async () => {
    try {
      setLoading(true)
      const res = await api.get('/app/info')
      setAppInfo(res.data)
    } catch (err) {
      console.error('Failed to fetch app info:', err)
    } finally {
      setLoading(false)
    }
  }

  const copyLink = (url, type) => {
    navigator.clipboard.writeText(url)
    const setters = { install: setCopiedInstall, direct: setCopiedDirect, drive: setCopied }
    const setter = setters[type]
    if (setter) {
      setter(true)
      setTimeout(() => setter(false), 2000)
    }
  }

  const downloadQrImage = () => {
    const svg = qrRef.current?.querySelector('svg')
    if (!svg) return
    const svgData = new XMLSerializer().serializeToString(svg)
    const canvas = document.createElement('canvas')
    canvas.width = 512
    canvas.height = 512
    const ctx = canvas.getContext('2d')
    const img = new Image()
    img.onload = () => {
      ctx.fillStyle = 'white'
      ctx.fillRect(0, 0, 512, 512)
      ctx.drawImage(img, 0, 0, 512, 512)
      const a = document.createElement('a')
      a.download = 'rrlocker-download-qr.png'
      a.href = canvas.toDataURL('image/png')
      a.click()
    }
    img.src = 'data:image/svg+xml;base64,' + btoa(unescape(encodeURIComponent(svgData)))
  }

  const activeQrUrl = qrType === 'direct' ? DIRECT_DOWNLOAD_URL : INSTALL_PAGE_URL

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">App Download QR</h1>
          <p className="text-gray-500 mt-1">Customer scans QR → app downloads on their phone</p>
        </div>
        <button
          onClick={fetchAppInfo}
          className="flex items-center gap-2 px-4 py-2 bg-gray-100 hover:bg-gray-200 rounded-lg text-sm font-medium text-gray-700 transition-colors"
        >
          <RefreshCw className="w-4 h-4" />
          Refresh
        </button>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* QR Code Card */}
        <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
          <div className="flex items-center gap-3 mb-4">
            <div className="w-10 h-10 bg-indigo-100 rounded-lg flex items-center justify-center">
              <QrCode className="w-5 h-5 text-indigo-600" />
            </div>
            <div>
              <h2 className="text-lg font-semibold text-gray-900">Download QR Code</h2>
              <p className="text-sm text-gray-500">Scan to download RR Locker app</p>
            </div>
          </div>

          {/* QR Type Toggle */}
          <div className="flex gap-2 mb-5">
            <button
              onClick={() => setQrType('install')}
              className={`flex-1 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                qrType === 'install'
                  ? 'bg-indigo-600 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              Install Page
            </button>
            <button
              onClick={() => setQrType('direct')}
              className={`flex-1 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                qrType === 'direct'
                  ? 'bg-indigo-600 text-white'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
              }`}
            >
              Direct Download
            </button>
          </div>

          <div className="flex flex-col items-center" ref={qrRef}>
            <div className="bg-white p-4 rounded-xl border-2 border-gray-100 shadow-inner inline-block">
              <QRCodeSVG
                value={activeQrUrl}
                size={220}
                level="H"
                fgColor="#1a1a2e"
                bgColor="#ffffff"
                includeMargin={false}
              />
            </div>
            <p className="mt-3 text-sm text-gray-500 text-center">
              {qrType === 'install'
                ? 'Opens install page with auto-download & instructions'
                : 'Directly downloads the APK file'}
            </p>
            <p className="mt-1 text-xs text-gray-400 text-center break-all max-w-xs">
              {activeQrUrl}
            </p>

            <div className="flex gap-3 mt-4">
              <button
                onClick={downloadQrImage}
                className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-medium transition-colors"
              >
                <Download className="w-4 h-4" />
                Save QR Image
              </button>
              <a
                href={INSTALL_PAGE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 px-4 py-2 bg-gray-100 hover:bg-gray-200 text-gray-700 rounded-lg text-sm font-medium transition-colors"
              >
                <ExternalLink className="w-4 h-4" />
                Preview
              </a>
            </div>
          </div>
        </div>

        {/* App Info & Links Card */}
        <div className="space-y-6">
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
            <div className="flex items-center gap-3 mb-6">
              <div className="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center">
                <Smartphone className="w-5 h-5 text-green-600" />
              </div>
              <div>
                <h2 className="text-lg font-semibold text-gray-900">App Info</h2>
                <p className="text-sm text-gray-500">RR Locker Android App</p>
              </div>
            </div>

            <div className="space-y-4">
              <div className="flex justify-between items-center py-3 border-b border-gray-100">
                <span className="text-sm text-gray-500">App Name</span>
                <span className="text-sm font-medium text-gray-900">RR Locker</span>
              </div>
              <div className="flex justify-between items-center py-3 border-b border-gray-100">
                <span className="text-sm text-gray-500">Source</span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
                  Server APK
                </span>
              </div>
              <div className="flex justify-between items-center py-3 border-b border-gray-100">
                <span className="text-sm text-gray-500">Status</span>
                <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
                  Available
                </span>
              </div>
              {appInfo?.apk_size_mb > 0 && (
                <div className="flex justify-between items-center py-3 border-b border-gray-100">
                  <span className="text-sm text-gray-500">APK Size</span>
                  <span className="text-sm font-medium text-gray-900">{appInfo.apk_size_mb} MB</span>
                </div>
              )}
            </div>
          </div>

          {/* Install Page Link */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
            <div className="flex items-center gap-2 mb-3">
              <Link className="w-4 h-4 text-indigo-600" />
              <h3 className="text-sm font-semibold text-gray-900">Install Page Link</h3>
            </div>
            <p className="text-xs text-gray-500 mb-3">Share with customers - opens page with auto-download & install guide</p>
            <div className="flex items-center gap-2">
              <input
                type="text"
                readOnly
                value={INSTALL_PAGE_URL}
                className="flex-1 px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600 truncate"
              />
              <button
                onClick={() => copyLink(INSTALL_PAGE_URL, 'install')}
                className={`flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                  copiedInstall
                    ? 'bg-green-100 text-green-700'
                    : 'bg-gray-100 hover:bg-gray-200 text-gray-700'
                }`}
              >
                {copiedInstall ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                {copiedInstall ? 'Copied!' : 'Copy'}
              </button>
            </div>
          </div>

          {/* Direct Download Link */}
          <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
            <h3 className="text-sm font-semibold text-gray-900 mb-3">Direct APK Download</h3>
            <div className="flex items-center gap-2">
              <input
                type="text"
                readOnly
                value={DIRECT_DOWNLOAD_URL}
                className="flex-1 px-3 py-2 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-600 truncate"
              />
              <button
                onClick={() => copyLink(DIRECT_DOWNLOAD_URL, 'direct')}
                className={`flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                  copiedDirect
                    ? 'bg-green-100 text-green-700'
                    : 'bg-gray-100 hover:bg-gray-200 text-gray-700'
                }`}
              >
                {copiedDirect ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                {copiedDirect ? 'Copied!' : 'Copy'}
              </button>
            </div>

            <a
              href={DIRECT_DOWNLOAD_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-4 w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-green-600 hover:bg-green-700 text-white rounded-lg text-sm font-medium transition-colors"
            >
              <Download className="w-4 h-4" />
              Download APK
            </a>
          </div>

          {/* How it works */}
          <div className="bg-indigo-50 rounded-xl border border-indigo-200 p-4">
            <h3 className="text-sm font-semibold text-indigo-800 mb-2">How QR Install Works</h3>
            <ol className="text-sm text-indigo-700 space-y-1.5 list-decimal list-inside">
              <li>Customer scans QR code with phone camera</li>
              <li>Browser opens → APK auto-downloads</li>
              <li>Customer taps downloaded file to install</li>
              <li>Enables "Install from unknown sources" if prompted</li>
              <li>Opens app and follows enrollment steps</li>
            </ol>
          </div>
        </div>
      </div>
    </div>
  )
}
