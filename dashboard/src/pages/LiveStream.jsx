import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams, useNavigate } from 'react-router-dom'
import {
  Cast,
  Mic,
  MicOff,
  MonitorOff,
  Volume2,
  VolumeX,
  Loader2,
  Search,
  Radio,
} from 'lucide-react'
import { streamingService } from '../services/streamingService'
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
  const wsRef = useRef(null)
  const lastBlobUrl = useRef(null)

  useEffect(() => {
    if (!deviceId || !enabled) return undefined
    const url = streamingService.viewerUrl(deviceId)
    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    wsRef.current = ws

    ws.onopen = () => { setConnected(true); setError(null) }
    ws.onclose = () => { setConnected(false) }
    ws.onerror = () => { setError('WebSocket error'); setConnected(false) }
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') return // ignore text events
      const blob = new Blob([ev.data], { type: 'image/jpeg' })
      const u = URL.createObjectURL(blob)
      setFrameUrl((prev) => {
        if (prev) URL.revokeObjectURL(prev)
        return u
      })
      if (lastBlobUrl.current) URL.revokeObjectURL(lastBlobUrl.current)
      lastBlobUrl.current = u
      frameCounter.current += 1
    }

    fpsTimer.current = setInterval(() => {
      setFps(frameCounter.current)
      frameCounter.current = 0
    }, 1000)

    return () => {
      clearInterval(fpsTimer.current)
      try { ws.close() } catch { /* noop */ }
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

/**
 * Plays raw PCM16 audio frames received via WebSocket using Web Audio API.
 * The device announces sample_rate / channels in a JSON text frame on connect.
 */
function useAudioStream(deviceId, enabled, muted) {
  const [connected, setConnected] = useState(false)
  const [info, setInfo] = useState({ sampleRate: 16000, channels: 1 })
  const [error, setError] = useState(null)
  const wsRef = useRef(null)
  const ctxRef = useRef(null)
  const gainRef = useRef(null)
  const nextStartRef = useRef(0)
  // Keep the announced audio format in a ref so the ws.onmessage closure always
  // reads the latest value. Reading `info` (state) inside the closure would use
  // the stale value captured when the effect first ran.
  const formatRef = useRef({ sampleRate: 16000, channels: 1 })

  useEffect(() => {
    if (!deviceId || !enabled) return undefined

    const AudioCtx = window.AudioContext || window.webkitAudioContext
    const ctx = new AudioCtx()
    ctxRef.current = ctx
    const gain = ctx.createGain()
    gain.gain.value = muted ? 0 : 1
    gain.connect(ctx.destination)
    gainRef.current = gain

    const url = streamingService.audioUrl(deviceId)
    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    wsRef.current = ws

    ws.onopen = () => { setConnected(true); setError(null) }
    ws.onclose = () => { setConnected(false) }
    ws.onerror = () => { setError('Audio WebSocket error'); setConnected(false) }
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        try {
          const meta = JSON.parse(ev.data)
          if (meta.sample_rate) {
            const fmt = {
              sampleRate: meta.sample_rate,
              channels: meta.channels || 1,
            }
            formatRef.current = fmt
            setInfo(fmt)
          }
        } catch { /* ignore */ }
        return
      }
      // Binary PCM16 mono frame
      const pcm = new Int16Array(ev.data)
      const channels = formatRef.current.channels || 1
      const sampleRate = formatRef.current.sampleRate || 16000
      const frames = pcm.length / channels
      if (frames <= 0) return
      const buf = ctx.createBuffer(channels, frames, sampleRate)
      for (let ch = 0; ch < channels; ch++) {
        const out = buf.getChannelData(ch)
        for (let i = 0; i < frames; i++) {
          out[i] = pcm[i * channels + ch] / 32768
        }
      }
      const src = ctx.createBufferSource()
      src.buffer = buf
      src.connect(gain)
      const now = ctx.currentTime
      // Small fixed jitter buffer (~60ms) for low-latency realtime playback.
      const start = Math.max(now + 0.06, nextStartRef.current)
      src.start(start)
      nextStartRef.current = start + buf.duration
      // Drop accumulated latency if we lag too far behind (keep it tight for realtime)
      if (nextStartRef.current - now > 0.5) {
        nextStartRef.current = now + 0.06
      }
    }

    return () => {
      try { ws.close() } catch { /* noop */ }
      try { ctx.close() } catch { /* noop */ }
      ctxRef.current = null
      gainRef.current = null
      nextStartRef.current = 0
      setConnected(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId, enabled])

  useEffect(() => {
    if (gainRef.current) gainRef.current.gain.value = muted ? 0 : 1
  }, [muted])

  return { connected, info, error }
}

export default function LiveStream() {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const [deviceId, setDeviceId] = useState(params.get('device') || '')
  const [videoOn, setVideoOn] = useState(false)
  const [audioOn, setAudioOn] = useState(false)
  const [muted, setMuted] = useState(false)
  const [busy, setBusy] = useState('')
  const [msg, setMsg] = useState('')

  useEffect(() => {
    const next = new URLSearchParams(params)
    if (deviceId) next.set('device', deviceId); else next.delete('device')
    setParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId])

  const { connected: videoConn, frameUrl, fps } = useScreenStream(deviceId, videoOn)
  const { connected: audioConn, info: audioInfo } = useAudioStream(deviceId, audioOn, muted)

  const { data: statusData, refetch: refetchStatus } = useQuery({
    queryKey: ['stream-status', deviceId],
    queryFn: () => streamingService.status(deviceId),
    enabled: !!deviceId,
    refetchInterval: 5000,
  })

  const status = statusData?.data

  const sendStart = async (kind) => {
    if (!deviceId) return
    setBusy(kind)
    setMsg('')
    try {
      if (kind === 'video') {
        await streamingService.startScreenMirror(deviceId, { quality: 50, fps: 5, scale: 0.5 })
        setVideoOn(true)
      } else {
        await streamingService.startAudioStream(deviceId)
        setAudioOn(true)
      }
      setMsg(`Sent ${kind} start command to device. Waiting for stream…`)
      setTimeout(refetchStatus, 1500)
    } catch (e) {
      setMsg(`Failed: ${e?.response?.data?.detail || e.message}`)
    } finally {
      setBusy('')
    }
  }

  const sendStop = async (kind) => {
    if (!deviceId) return
    setBusy(kind)
    setMsg('')
    try {
      if (kind === 'video') {
        await streamingService.stopScreenMirror(deviceId)
        setVideoOn(false)
      } else {
        await streamingService.stopAudioStream(deviceId)
        setAudioOn(false)
      }
      setMsg(`Sent ${kind} stop command.`)
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
            Real-time screen mirror and live audio (microphone + system playback).
            The device shows a persistent notification while streaming.
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
                  onClick={() => sendStart('video')}
                  disabled={!deviceId || busy === 'video'}
                  className="flex-1 px-3 py-2 rounded-md bg-blue-600 hover:bg-blue-700 text-white text-sm font-medium disabled:opacity-50">
                  {busy === 'video' ? 'Starting…' : 'Start mirror'}
                </button>
              ) : (
                <button
                  onClick={() => sendStop('video')}
                  disabled={busy === 'video'}
                  className="flex-1 px-3 py-2 rounded-md bg-gray-200 hover:bg-gray-300 text-gray-900 text-sm font-medium disabled:opacity-50 flex items-center justify-center gap-1">
                  <MonitorOff className="w-4 h-4" /> Stop mirror
                </button>
              )}
            </div>
          </div>

          <div className="bg-white rounded-lg shadow-sm p-4">
            <div className="flex items-center justify-between mb-2">
              <h3 className="text-sm font-semibold text-gray-900 flex items-center gap-2">
                <Radio className="w-4 h-4" /> Live audio
              </h3>
              <StatusPill ok={audioConn || status?.audio_streaming}
                label={audioConn
                  ? `${(audioInfo.sampleRate / 1000).toFixed(1)} kHz`
                  : (status?.audio_streaming ? 'on' : 'off')} />
            </div>
            <p className="text-xs text-gray-500 mb-3">
              Mic + system playback mixed in real time. Picks up voice calls and
              VoIP apps such as Google Meet whenever the app routes through the
              media stream.
            </p>
            <div className="flex gap-2">
              {!audioOn ? (
                <button
                  onClick={() => sendStart('audio')}
                  disabled={!deviceId || busy === 'audio'}
                  className="flex-1 px-3 py-2 rounded-md bg-green-600 hover:bg-green-700 text-white text-sm font-medium disabled:opacity-50 flex items-center justify-center gap-1">
                  <Mic className="w-4 h-4" /> {busy === 'audio' ? 'Starting…' : 'Start listening'}
                </button>
              ) : (
                <>
                  <button
                    onClick={() => sendStop('audio')}
                    disabled={busy === 'audio'}
                    className="flex-1 px-3 py-2 rounded-md bg-gray-200 hover:bg-gray-300 text-gray-900 text-sm font-medium disabled:opacity-50 flex items-center justify-center gap-1">
                    <MicOff className="w-4 h-4" /> Stop
                  </button>
                  <button
                    onClick={() => setMuted((m) => !m)}
                    className="px-3 py-2 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-900 text-sm font-medium flex items-center gap-1">
                    {muted ? <VolumeX className="w-4 h-4" /> : <Volume2 className="w-4 h-4" />}
                  </button>
                </>
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
