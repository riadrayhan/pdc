import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams, useNavigate } from 'react-router-dom'
import {
  Cast,
  MonitorOff,
  Loader2,
  Search,
} from 'lucide-react'
import { streamingService } from '../services/streamingService'
import { watchScreen } from '../services/realtimeClient'
import { deviceService } from '../services/emiService'

function useDeviceList() {
  return useQuery({
    queryKey: ['stream-device-list'],
    queryFn: () => deviceService.list({ limit: 100 }),
  })
}

function DeviceSelector({ value, onChange }) {
  const { data } = useDeviceList()
  return (
    <div className="flex items-center gap-2">
      <Search className="w-4 h-4 text-gray-400" />
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="border border-gray-300 rounded-md px-3 py-1.5 text-sm bg-white min-w-[280px]"
      >
        <option value="">— Select an enrolled device —</option>
        {data?.data?.devices?.map((d) => {
          const label = [d.brand || d.manufacturer, d.device_model]
            .filter(Boolean).join(' ') || 'Device'
          const subtitle = d.imei || d.serial_number || d.id.slice(0, 8)
          return (
            <option key={d.id} value={d.id}>{label} — {subtitle}</option>
          )
        })}
      </select>
    </div>
  )
}

function useScreenStream(deviceId, enabled) {
  const [connected, setConnected] = useState(false)
  const [error, setError] = useState(null)
  const [frameUrl, setFrameUrl] = useState(null)
  const [fps, setFps] = useState(0)
  const frameCounter = useRef(0)
  const fpsTimer = useRef(null)
  const lastBlobUrl = useRef(null)

  useEffect(() => {
    if (!deviceId || !enabled) return undefined

    const ctrl = watchScreen(deviceId, {
      onStatus: (s) => {
        if (s === 'connected' || s === 'publisher-online') { setConnected(true); setError(null) }
        if (s === 'disconnected') setConnected(false)
      },
      onFrame: (data) => {
        const blob = new Blob([data], { type: 'image/jpeg' })
        const u = URL.createObjectURL(blob)
        setFrameUrl((prev) => {
          if (prev) URL.revokeObjectURL(prev)
          return u
        })
        if (lastBlobUrl.current) URL.revokeObjectURL(lastBlobUrl.current)
        lastBlobUrl.current = u
        frameCounter.current += 1
      },
    })

    fpsTimer.current = setInterval(() => {
      setFps(frameCounter.current)
      frameCounter.current = 0
    }, 1000)

    return () => {
      clearInterval(fpsTimer.current)
      try { ctrl.close() } catch { /* noop */ }
      if (lastBlobUrl.current) {
        URL.revokeObjectURL(lastBlobUrl.current)
        lastBlobUrl.current = null
      }
      setConnected(false)
      setFrameUrl(null)
      setFps(0)
    }
  }, [deviceId, enabled])

  return { connected, frameUrl, fps, error }
}

export default function LiveStream() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [deviceId, setDeviceId] = useState(params.get('device') || '')
  const [videoOn, setVideoOn] = useState(false)
  const [busy, setBusy] = useState('')
  const [msg, setMsg] = useState('')

  useEffect(() => {
    const next = new URLSearchParams(params)
    if (deviceId) next.set('device', deviceId); else next.delete('device')
    setParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId])

  const { connected: videoConn, frameUrl, fps } = useScreenStream(deviceId, videoOn)

  const { data: statusData, refetch: refetchStatus } = useQuery({
    queryKey: ['stream-status', deviceId],
    queryFn: () => streamingService.status(deviceId),
    enabled: !!deviceId,
    refetchInterval: 5000,
  })

  const status = statusData?.data

  const sendStart = async () => {
    if (!deviceId) return
    setBusy('video')
    setMsg('')
    try {
      await streamingService.startScreenMirror(deviceId, { quality: 50, fps: 5, scale: 0.5 })
      setVideoOn(true)
      setMsg('Sent screen start command to device. Waiting for stream…')
      setTimeout(refetchStatus, 1500)
    } catch (e) {
      setMsg(`Failed: ${e?.response?.data?.detail || e.message}`)
    } finally {
      setBusy('')
    }
  }

  const sendStop = async () => {
    if (!deviceId) return
    setBusy('video')
    setMsg('')
    try {
      await streamingService.stopScreenMirror(deviceId)
      setVideoOn(false)
      setMsg('Sent screen stop command.')
      setTimeout(refetchStatus, 1500)
    } catch (e) {
      setMsg(`Failed: ${e?.response?.data?.detail || e.message}`)
    } finally {
      setBusy('')
    }
  }

  return (
    <div className="p-6 space-y-5">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900 flex items-center gap-2">
            <Cast className="w-6 h-6 text-blue-600" /> Live Stream
          </h1>
          <p className="text-sm text-gray-500">
            Real-time screen mirror. The device shows a persistent notification
            while streaming. For audio, use the dedicated Live Audio page.
          </p>
        </div>
        <DeviceSelector value={deviceId} onChange={setDeviceId} />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        <div className="lg:col-span-2 bg-black rounded-lg overflow-hidden aspect-[9/16] md:aspect-video flex items-center justify-center">
          {!deviceId && (
            <p className="text-gray-400 text-sm">Select a device to begin</p>
          )}
          {deviceId && !videoOn && (
            <p className="text-gray-400 text-sm">Screen mirror is off</p>
          )}
          {deviceId && videoOn && !frameUrl && (
            <div className="text-gray-300 text-sm flex items-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin" />
              Waiting for first frame from device…
            </div>
          )}
          {frameUrl && (
            <img src={frameUrl} alt="device screen"
              className="w-full h-full object-contain" />
          )}
        </div>

        <div className="space-y-4">
          <div className="bg-white rounded-lg shadow-sm p-4">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
                <Cast className="w-4 h-4" /> Screen mirror
              </h3>
              <StatusPill ok={videoConn || status?.video_streaming}
                label={videoConn ? `${fps} fps` : (status?.video_streaming ? 'on' : 'off')} />
            </div>
            <p className="text-xs text-gray-500 mb-3">
              Sends JPEG frames over a WebSocket. The device must accept the
              MediaProjection consent dialog once per session.
            </p>
            <div className="flex gap-2">
              {!videoOn ? (
                <button
                  onClick={() => sendStart()}
                  disabled={!deviceId || busy === 'video'}
                  className="flex-1 px-3 py-2 rounded-md bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium disabled:opacity-50">
                  {busy === 'video' ? 'Starting…' : 'Start mirror'}
                </button>
              ) : (
                <button
                  onClick={() => sendStop()}
                  disabled={busy === 'video'}
                  className="flex-1 px-3 py-2 rounded-md bg-gray-200 hover:bg-gray-300 text-gray-900 text-sm font-medium disabled:opacity-50 flex items-center justify-center gap-1">
                  <MonitorOff className="w-4 h-4" /> Stop mirror
                </button>
              )}
            </div>
          </div>

          {msg && (
            <div className="bg-blue-50 border border-blue-200 rounded-md p-3 text-xs text-blue-900">
              {msg}
            </div>
          )}

          {deviceId && (
            <button
              onClick={() => navigate(`/devices/${deviceId}`)}
              className="text-xs text-blue-600 hover:underline">
              ← Back to device details
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

function StatusPill({ ok, label }) {
  return (
    <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${
      ok ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'
    }`}>{label}</span>
  )
}
