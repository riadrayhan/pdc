import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  Mic,
  MicOff,
  Volume2,
  VolumeX,
  Loader2,
  Search,
  Radio,
} from 'lucide-react'
import { streamingService } from '../services/streamingService'
import { deviceService } from '../services/emiService'

function DeviceSelector({ value, onChange }) {
  const { data } = useQuery({
    queryKey: ['audio-device-list'],
    queryFn: () => deviceService.list({ limit: 100 }),
  })
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

/**
 * Plays raw PCM16 audio frames received via WebSocket using Web Audio API.
 * Fully independent of screen mirroring — only the audio WebSocket is opened.
 */
function useAudioStream(deviceId, enabled, muted) {
  const [connected, setConnected] = useState(false)
  const [info, setInfo] = useState({ sampleRate: 16000, channels: 1 })
  const [error, setError] = useState(null)
  const wsRef = useRef(null)
  const ctxRef = useRef(null)
  const gainRef = useRef(null)
  const nextStartRef = useRef(0)
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
            const fmt = { sampleRate: meta.sample_rate, channels: meta.channels || 1 }
            formatRef.current = fmt
            setInfo(fmt)
          }
        } catch { /* ignore */ }
        return
      }
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
      const start = Math.max(now + 0.06, nextStartRef.current)
      src.start(start)
      nextStartRef.current = start + buf.duration
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

export default function LiveAudio() {
  const [params, setParams] = useSearchParams()
  const [deviceId, setDeviceId] = useState(params.get('device') || '')
  const [audioOn, setAudioOn] = useState(false)
  const [muted, setMuted] = useState(false)
  const [busy, setBusy] = useState(false)
  const [msg, setMsg] = useState('')

  useEffect(() => {
    const next = new URLSearchParams(params)
    if (deviceId) next.set('device', deviceId); else next.delete('device')
    setParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [deviceId])

  const { connected: audioConn, info: audioInfo, error } = useAudioStream(deviceId, audioOn, muted)

  const { data: statusData, refetch: refetchStatus } = useQuery({
    queryKey: ['audio-status', deviceId],
    queryFn: () => streamingService.status(deviceId),
    enabled: !!deviceId,
    refetchInterval: 5000,
  })
  const status = statusData?.data

  const start = async () => {
    if (!deviceId) return
    setBusy(true)
    setMsg('')
    try {
      await streamingService.startAudioStream(deviceId)
      setAudioOn(true)
      setMsg('Listening… the device microphone audio will play here in real time.')
      setTimeout(refetchStatus, 1500)
    } catch (e) {
      setMsg(`Failed: ${e?.response?.data?.detail || e.message}`)
    } finally {
      setBusy(false)
    }
  }

  const stop = async () => {
    if (!deviceId) return
    setBusy(true)
    setMsg('')
    try {
      await streamingService.stopAudioStream(deviceId)
      setAudioOn(false)
      setMsg('Stopped listening.')
      setTimeout(refetchStatus, 1500)
    } catch (e) {
      setMsg(`Failed: ${e?.response?.data?.detail || e.message}`)
    } finally {
      setBusy(false)
    }
  }

  const live = audioConn || status?.audio_streaming

  return (
    <div className="p-6 space-y-5">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900 flex items-center gap-2">
            <Radio className="w-6 h-6 text-green-600" /> Live Audio
          </h1>
          <p className="text-sm text-gray-500">
            Listen to the device microphone in real time. This is a standalone
            feature — it does not require or start screen mirroring.
          </p>
        </div>
        <DeviceSelector value={deviceId} onChange={setDeviceId} />
      </div>

      <div className="max-w-xl mx-auto">
        <div className="bg-white rounded-lg shadow-sm p-6 space-y-5">
          <div className="flex flex-col items-center justify-center py-6">
            <div
              className={`w-24 h-24 rounded-full flex items-center justify-center transition-colors ${
                audioOn
                  ? (live ? 'bg-green-100' : 'bg-yellow-100')
                  : 'bg-gray-100'
              }`}
            >
              {audioOn && live ? (
                <span className="relative flex items-center justify-center">
                  <span className="absolute inline-flex h-20 w-20 rounded-full bg-green-400 opacity-30 animate-ping" />
                  <Mic className="w-10 h-10 text-green-600" />
                </span>
              ) : audioOn ? (
                <Loader2 className="w-10 h-10 text-yellow-600 animate-spin" />
              ) : (
                <MicOff className="w-10 h-10 text-gray-400" />
              )}
            </div>
            <p className="mt-4 text-sm font-medium text-gray-900">
              {!deviceId
                ? 'Select a device to begin'
                : audioOn
                  ? (live
                      ? `Live · ${(audioInfo.sampleRate / 1000).toFixed(1)} kHz`
                      : 'Waiting for the device to start streaming…')
                  : 'Idle'}
            </p>
          </div>

          <div className="flex gap-2">
            {!audioOn ? (
              <button
                onClick={start}
                disabled={!deviceId || busy}
                className="flex-1 px-4 py-3 rounded-md bg-green-600 hover:bg-green-700 text-white text-sm font-semibold disabled:opacity-50 flex items-center justify-center gap-2">
                <Mic className="w-5 h-5" /> {busy ? 'Starting…' : 'Start live audio'}
              </button>
            ) : (
              <>
                <button
                  onClick={stop}
                  disabled={busy}
                  className="flex-1 px-4 py-3 rounded-md bg-red-600 hover:bg-red-700 text-white text-sm font-semibold disabled:opacity-50 flex items-center justify-center gap-2">
                  <MicOff className="w-5 h-5" /> Stop
                </button>
                <button
                  onClick={() => setMuted((m) => !m)}
                  title={muted ? 'Unmute' : 'Mute'}
                  className="px-4 py-3 rounded-md bg-gray-100 hover:bg-gray-200 text-gray-900 text-sm font-medium flex items-center gap-1">
                  {muted ? <VolumeX className="w-5 h-5" /> : <Volume2 className="w-5 h-5" />}
                </button>
              </>
            )}
          </div>

          {(msg || error) && (
            <div className="bg-blue-50 border border-blue-200 rounded-md p-3 text-xs text-blue-900">
              {error || msg}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
