import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import {
  Folder, FolderOpen, File as FileIcon, Download, RefreshCw,
  ArrowLeft, HardDrive, Loader2, Search, Power, PowerOff, AlertTriangle, Trash2,
} from 'lucide-react'
import toast from 'react-hot-toast'
import { deviceService } from '../services/emiService'
import { fileManagerService } from '../services/fileManagerService'
import { watchFiles } from '../services/realtimeClient'

function useDeviceList() {
  return useQuery({
    queryKey: ['fm-device-list'],
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

function formatBytes(n) {
  if (n == null || isNaN(n)) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0; let v = Number(n)
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

function formatTime(ms) {
  if (!ms) return '-'
  try { return new Date(ms).toLocaleString() } catch { return '-' }
}

/**
 * Manages a single admin Socket.IO file-manager channel for {deviceId}.
 * Demuxes request/response pairs via req_id and exposes promise helpers.
 */
function useFileWs(deviceId, enabled) {
  const [connected, setConnected] = useState(false)
  const [deviceOnline, setDeviceOnline] = useState(false)
  const [error, setError] = useState(null)
  const ctrlRef = useRef(null)
  const pendingRef = useRef(new Map()) // req_id -> {resolve, reject, onChunk, name, chunks, size}

  useEffect(() => {
    if (!deviceId || !enabled) return undefined

    const ctrl = watchFiles(deviceId, {
      onStatus: (s) => {
        if (s === 'connected') { setConnected(true); setError(null) }
        if (s === 'disconnected') { setConnected(false); setDeviceOnline(false) }
      },
      onMessage: (msg) => {
        if (!msg) return
        if (msg.event === 'device_online') { setDeviceOnline(true); return }
        if (msg.event === 'device_offline') { setDeviceOnline(false); return }
        const id = msg.req_id
        if (!id) return
        const p = pendingRef.current.get(id)
        if (!p) return
        const action = msg.action
        if (action === 'list' || action === 'roots' || action === 'delete') {
          pendingRef.current.delete(id)
          if (msg.error) p.reject(new Error(msg.error))
          else p.resolve(msg)
        } else if (action === 'download_start') {
          p.name = msg.name
          p.size = msg.size
          p.chunks = []
          if (p.onMeta) p.onMeta(msg)
        } else if (action === 'chunk') {
          if (!p.chunks) p.chunks = []
          const bin = atob(msg.data || '')
          const u8 = new Uint8Array(bin.length)
          for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i)
          p.chunks.push(u8)
          if (p.onChunk) p.onChunk(u8.byteLength)
        } else if (action === 'download_end') {
          pendingRef.current.delete(id)
          p.resolve({ name: p.name, size: p.size, chunks: p.chunks || [] })
        } else if (action === 'error') {
          pendingRef.current.delete(id)
          p.reject(new Error(msg.error || 'unknown'))
        }
      },
    })
    ctrlRef.current = ctrl

    return () => {
      try { ctrl.close() } catch { /* noop */ }
      pendingRef.current.forEach((p) => p.reject(new Error('connection closed')))
      pendingRef.current.clear()
      ctrlRef.current = null
      setConnected(false)
      setDeviceOnline(false)
    }
  }, [deviceId, enabled])

  const send = (req) => {
    const ctrl = ctrlRef.current
    if (!ctrl) {
      return Promise.reject(new Error('not connected'))
    }
    const reqId = Math.random().toString(36).slice(2)
    return new Promise((resolve, reject) => {
      pendingRef.current.set(reqId, { resolve, reject, ...req.handlers })
      ctrl.send({ ...req.payload, req_id: reqId })
    })
  }

  return {
    connected,
    deviceOnline,
    error,
    listRoots: () => send({ payload: { action: 'roots' } }),
    listDir: (path) => send({ payload: { action: 'list', path } }),
    downloadFile: (path, onMeta, onChunk) =>
      send({ payload: { action: 'download', path }, handlers: { onMeta, onChunk } }),
    deletePath: (path) => send({ payload: { action: 'delete', path } }),
  }
}

export default function FileManager() {
  const [params, setParams] = useSearchParams()
  const [deviceId, setDeviceId] = useState(params.get('device') || '')
  const [sessionOn, setSessionOn] = useState(false)
  const [busy, setBusy] = useState('')

  const [path, setPath] = useState('')
  const [entries, setEntries] = useState([])
  const [parent, setParent] = useState(null)
  const [roots, setRoots] = useState([])
  const [hasManage, setHasManage] = useState(true)
  const [loading, setLoading] = useState(false)
  const [listError, setListError] = useState('')

  const [downloadProgress, setDownloadProgress] = useState(null)

  const fm = useFileWs(deviceId, sessionOn)

  useEffect(() => {
    if (!deviceId) return
    setParams((p) => { const q = new URLSearchParams(p); q.set('device', deviceId); return q })
  }, [deviceId, setParams])

  // Auto-load roots once device is online
  useEffect(() => {
    if (!fm.deviceOnline || !sessionOn) return
    let cancelled = false
    setLoading(true); setListError('')
    fm.listRoots()
      .then((r) => {
        if (cancelled) return
        setRoots(r.roots || [])
        setHasManage(r.has_manage_storage !== false)
        // Auto-open first root
        const first = (r.roots || [])[0]
        if (first) loadDir(first.path)
        else setLoading(false)
      })
      .catch((e) => { if (!cancelled) { setListError(e.message); setLoading(false) } })
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fm.deviceOnline, sessionOn])

  const loadDir = async (p) => {
    setLoading(true); setListError('')
    try {
      const res = await fm.listDir(p)
      const sorted = (res.entries || []).slice().sort((a, b) => {
        if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1
        return a.name.localeCompare(b.name)
      })
      setEntries(sorted)
      setPath(res.path || p)
      setParent(res.parent || null)
    } catch (e) {
      setListError(e.message)
    } finally {
      setLoading(false)
    }
  }

  const handleStart = async () => {
    if (!deviceId) { toast.error('Select a device first'); return }
    setBusy('start')
    try {
      await fileManagerService.start(deviceId, 'Admin opened file manager')
      setSessionOn(true)
      toast.success('File-manager command sent to device')
    } catch (e) {
      toast.error(e.response?.data?.detail || 'Failed to start')
    } finally { setBusy('') }
  }

  const handleStop = async () => {
    setBusy('stop')
    try {
      await fileManagerService.stop(deviceId, 'Admin closed file manager')
    } catch { /* ignore */ }
    setSessionOn(false)
    setEntries([]); setPath(''); setRoots([]); setParent(null)
    setBusy('')
  }

  const handleDownload = async (entry) => {
    if (entry.is_dir) return
    setDownloadProgress({ name: entry.name, received: 0, total: entry.size || 0 })
    try {
      const res = await fm.downloadFile(
        entry.path,
        (meta) => setDownloadProgress({ name: meta.name, received: 0, total: meta.size || 0 }),
        (added) => setDownloadProgress((p) => p ? { ...p, received: p.received + added } : p),
      )
      const blob = new Blob(res.chunks)
      const a = document.createElement('a')
      const url = URL.createObjectURL(blob)
      a.href = url
      a.download = res.name || entry.name
      document.body.appendChild(a); a.click(); document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      toast.success(`Downloaded ${res.name}`)
    } catch (e) {
      toast.error(`Download failed: ${e.message}`)
    } finally {
      setDownloadProgress(null)
    }
  }

  const handleDelete = async (entry) => {
    if (!window.confirm(`Permanently delete "${entry.name}" from device?`)) return
    try {
      const res = await fm.deletePath(entry.path)
      if (res.ok) {
        toast.success('Deleted')
        loadDir(path)
      } else {
        toast.error(res.error || 'Delete failed')
      }
    } catch (e) {
      toast.error(e.message)
    }
  }

  const crumbs = useMemo(() => {
    if (!path) return []
    const parts = path.split('/').filter(Boolean)
    const acc = []
    let cur = ''
    for (const p of parts) {
      cur += '/' + p
      acc.push({ name: p, path: cur })
    }
    return acc
  }, [path])

  return (
    <div className="space-y-4">
      <div className="bg-white rounded-lg shadow p-4 flex flex-wrap items-center gap-3">
        <DeviceSelector value={deviceId} onChange={setDeviceId} />
        {!sessionOn ? (
          <button
            disabled={!deviceId || busy === 'start'}
            onClick={handleStart}
            className="inline-flex items-center gap-2 px-3 py-1.5 rounded-md bg-primary-600 text-white text-sm disabled:opacity-50"
          >
            {busy === 'start' ? <Loader2 className="w-4 h-4 animate-spin" /> : <Power className="w-4 h-4" />}
            Start File Manager
          </button>
        ) : (
          <button
            onClick={handleStop}
            className="inline-flex items-center gap-2 px-3 py-1.5 rounded-md bg-red-600 text-white text-sm"
          >
            <PowerOff className="w-4 h-4" /> Stop
          </button>
        )}

        <span className={`ml-auto text-xs px-2 py-1 rounded ${
          fm.deviceOnline ? 'bg-green-100 text-green-700'
            : sessionOn ? 'bg-yellow-100 text-yellow-700'
            : 'bg-gray-100 text-gray-600'
        }`}>
          {fm.deviceOnline ? 'Device connected'
            : sessionOn ? 'Waiting for device…' : 'Session not started'}
        </span>
      </div>

      {!hasManage && sessionOn && (
        <div className="bg-yellow-50 border border-yellow-200 rounded-lg p-3 flex items-start gap-2">
          <AlertTriangle className="w-5 h-5 text-yellow-600 mt-0.5" />
          <div className="text-sm text-yellow-800">
            Device has not granted <b>All-files access</b> (MANAGE_EXTERNAL_STORAGE).
            Some folders outside app-private storage will be unreadable. Grant it from
            Settings → Apps → RR Locker → Special access → All files access.
          </div>
        </div>
      )}

      {roots.length > 0 && (
        <div className="bg-white rounded-lg shadow p-4">
          <div className="text-xs font-medium text-gray-500 mb-2">STORAGE VOLUMES</div>
          <div className="flex flex-wrap gap-2">
            {roots.map((r) => (
              <button key={r.path} onClick={() => loadDir(r.path)}
                className={`flex items-center gap-2 px-3 py-2 rounded-md border text-sm ${
                  path?.startsWith(r.path)
                    ? 'border-primary-500 bg-primary-50 text-primary-700'
                    : 'border-gray-200 hover:bg-gray-50'
                }`}>
                <HardDrive className="w-4 h-4" />
                <div className="text-left">
                  <div className="font-medium">{r.name}</div>
                  <div className="text-xs text-gray-500">
                    {formatBytes(r.free)} free / {formatBytes(r.total)}
                  </div>
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {sessionOn && (
        <div className="bg-white rounded-lg shadow">
          <div className="flex items-center gap-2 p-3 border-b overflow-x-auto">
            <button
              disabled={!parent}
              onClick={() => parent && loadDir(parent)}
              className="p-1 rounded hover:bg-gray-100 disabled:opacity-30"
            >
              <ArrowLeft className="w-4 h-4" />
            </button>
            <button onClick={() => path && loadDir(path)} className="p-1 rounded hover:bg-gray-100">
              <RefreshCw className="w-4 h-4" />
            </button>
            <div className="flex items-center gap-1 text-sm text-gray-700 whitespace-nowrap">
              {crumbs.length === 0 && <span className="text-gray-400">No path</span>}
              {crumbs.map((c, idx) => (
                <span key={c.path} className="flex items-center gap-1">
                  <button onClick={() => loadDir(c.path)}
                    className="hover:underline text-primary-700">
                    {idx === 0 ? '/' + c.name : c.name}
                  </button>
                  {idx < crumbs.length - 1 && <span className="text-gray-400">/</span>}
                </span>
              ))}
            </div>
          </div>

          <div className="divide-y">
            {loading && (
              <div className="p-6 flex items-center gap-2 text-sm text-gray-500">
                <Loader2 className="w-4 h-4 animate-spin" /> Loading…
              </div>
            )}
            {!loading && listError && (
              <div className="p-6 text-sm text-red-600">{listError}</div>
            )}
            {!loading && !listError && entries.length === 0 && (
              <div className="p-6 text-sm text-gray-500">Empty folder.</div>
            )}
            {!loading && entries.map((e) => (
              <div key={e.path} className="flex items-center gap-3 px-4 py-2 hover:bg-gray-50">
                {e.is_dir
                  ? <FolderOpen className="w-5 h-5 text-yellow-500 flex-shrink-0" />
                  : <FileIcon className="w-5 h-5 text-gray-400 flex-shrink-0" />}
                <button
                  className="flex-1 text-left text-sm truncate"
                  onClick={() => e.is_dir ? loadDir(e.path) : handleDownload(e)}
                  title={e.path}
                >
                  <div className="font-medium text-gray-900 truncate">{e.name}</div>
                  <div className="text-xs text-gray-500">
                    {e.is_dir ? 'Folder' : formatBytes(e.size)} · {formatTime(e.modified)}
                  </div>
                </button>
                {!e.is_dir && (
                  <button
                    onClick={() => handleDownload(e)}
                    className="p-2 rounded hover:bg-primary-50 text-primary-600"
                    title="Download"
                  >
                    <Download className="w-4 h-4" />
                  </button>
                )}
                <button
                  onClick={() => handleDelete(e)}
                  className="p-2 rounded hover:bg-red-50 text-red-500"
                  title="Delete"
                >
                  <Trash2 className="w-4 h-4" />
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {downloadProgress && (
        <div className="fixed bottom-4 right-4 bg-white shadow-lg rounded-lg p-3 w-72 z-50 border">
          <div className="flex items-center gap-2 mb-1 text-sm font-medium">
            <Download className="w-4 h-4 text-primary-600" />
            <span className="truncate">{downloadProgress.name}</span>
          </div>
          <div className="h-1.5 bg-gray-100 rounded overflow-hidden">
            <div
              className="h-full bg-primary-600 transition-all"
              style={{
                width: downloadProgress.total
                  ? `${Math.min(100, (downloadProgress.received / downloadProgress.total) * 100)}%`
                  : '40%',
              }}
            />
          </div>
          <div className="text-xs text-gray-500 mt-1">
            {formatBytes(downloadProgress.received)}
            {downloadProgress.total ? ` / ${formatBytes(downloadProgress.total)}` : ''}
          </div>
        </div>
      )}
    </div>
  )
}
