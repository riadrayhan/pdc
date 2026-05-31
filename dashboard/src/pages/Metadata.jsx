import { useEffect, useMemo, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { metadataService } from '../services/metadataService'
import { deviceService } from '../services/emiService'
import {
  Activity,
  BarChart3,
  Banknote,
  Bus,
  Database,
  MapPin,
  MessageSquare,
  Phone,
  Smartphone,
  CreditCard,
  Wifi,
  Search,
  Trash2,
  Users,
} from 'lucide-react'

const SimIcon = CreditCard

const TABS = [
  { id: 'overview', label: 'Overview', icon: BarChart3 },
  { id: 'call_logs', label: 'Call Logs', icon: Phone },
  { id: 'contacts', label: 'Contacts', icon: Users },
  { id: 'sms', label: 'SMS', icon: MessageSquare },
  { id: 'location', label: 'Locations', icon: MapPin },
  { id: 'installed_apps', label: 'Installed Apps', icon: Smartphone },
  { id: 'behavior', label: 'Behavior', icon: Activity },
  { id: 'financial', label: 'Financial Activity', icon: Banknote },
  { id: 'rides', label: 'Rides', icon: Bus },
  { id: 'device_info', label: 'Device Info', icon: Database },
]

function fmtDate(s) {
  if (!s) return '-'
  try { return new Date(s).toLocaleString() } catch { return s }
}

function fmtCallDate(ms) {
  if (!ms) return '-'
  const n = Number(ms)
  if (!Number.isFinite(n) || n <= 0) return ms
  return new Date(n).toLocaleString()
}

function StatCard({ label, value, icon: Icon }) {
  return (
    <div className="bg-white rounded-lg shadow-sm p-4 flex items-center gap-3">
      <div className="w-10 h-10 rounded-lg bg-blue-50 flex items-center justify-center">
        <Icon className="w-5 h-5 text-blue-600" />
      </div>
      <div>
        <p className="text-xs text-gray-500">{label}</p>
        <p className="text-lg font-semibold text-gray-900">{value ?? 0}</p>
      </div>
    </div>
  )
}

function Table({ columns, rows, emptyText = 'No records' }) {
  if (!rows || rows.length === 0) {
    return <div className="text-center text-sm text-gray-500 py-8">{emptyText}</div>
  }
  return (
    <div className="overflow-x-auto">
      <table className="min-w-full text-sm">
        <thead className="bg-gray-50 text-left text-xs text-gray-600 uppercase">
          <tr>
            {columns.map((c) => (
              <th key={c.key} className="px-3 py-2 font-medium">{c.label}</th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {rows.map((r, i) => (
            <tr key={r.id || i} className="hover:bg-gray-50">
              {columns.map((c) => (
                <td key={c.key} className="px-3 py-2 text-gray-700 align-top">
                  {c.render ? c.render(r) : (r[c.key] ?? '-')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Pagination({ page, pageSize, total, onChange }) {
  const pageCount = Math.max(1, Math.ceil((total || 0) / pageSize))
  if (!total) return null
  const maxButtons = 7
  let start = Math.max(0, page - 3)
  let end = Math.min(pageCount, start + maxButtons)
  start = Math.max(0, end - maxButtons)
  const pages = []
  for (let i = start; i < end; i++) pages.push(i)
  return (
    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 border-t border-gray-100 pt-3 mt-2">
      <span className="text-xs text-gray-500">
        Showing {page * pageSize + 1}–{Math.min((page + 1) * pageSize, total)} of {total}
      </span>
      <div className="flex items-center gap-1 flex-wrap">
        <button disabled={page === 0} onClick={() => onChange(page - 1)}
          className="px-2 py-1 rounded border border-gray-200 text-gray-600 text-xs disabled:opacity-40 hover:bg-gray-100">Prev</button>
        {start > 0 && <span className="px-1 text-gray-400">…</span>}
        {pages.map((p) => (
          <button key={p} onClick={() => onChange(p)}
            className={`px-3 py-1 rounded border text-xs ${
              p === page ? 'bg-blue-600 text-white border-blue-600' : 'border-gray-200 text-gray-700 hover:bg-gray-100'
            }`}>{p + 1}</button>
        ))}
        {end < pageCount && <span className="px-1 text-gray-400">…</span>}
        <button disabled={page >= pageCount - 1} onClick={() => onChange(page + 1)}
          className="px-2 py-1 rounded border border-gray-200 text-gray-600 text-xs disabled:opacity-40 hover:bg-gray-100">Next</button>
      </div>
    </div>
  )
}

function DeleteAllButton({ dataTypes, deviceId, onDone, disabled, label = 'Delete all' }) {
  const [busy, setBusy] = useState(false)
  const handle = async () => {
    if (disabled || busy) return
    const scope = deviceId ? 'this device' : 'ALL devices'
    if (!window.confirm(`Delete all records for ${scope}? This cannot be undone.`)) return
    setBusy(true)
    try {
      for (const t of dataTypes) await metadataService.deleteAll(t, deviceId)
      onDone?.()
    } finally {
      setBusy(false)
    }
  }
  return (
    <button
      onClick={handle}
      disabled={disabled || busy}
      className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-xs font-medium bg-red-50 text-red-600 hover:bg-red-100 border border-red-200 disabled:opacity-40 disabled:cursor-not-allowed"
    >
      <Trash2 className="w-3.5 h-3.5" />
      {busy ? 'Deleting…' : label}
    </button>
  )
}

// Generic single-table tab with delete-all + pagination
function MetaTable({ deviceId, dataTypes, queryKey, fetcher, columns, emptyText = 'No records', pageSize = 50 }) {
  const [page, setPage] = useState(0)
  const qc = useQueryClient()
  useEffect(() => { setPage(0) }, [deviceId])
  const { data, isLoading } = useQuery({
    queryKey: [queryKey, deviceId, page],
    queryFn: () => fetcher(deviceId, pageSize, page * pageSize),
    keepPreviousData: true,
  })
  const items = data?.data?.items || []
  const total = data?.data?.total ?? items.length
  const refresh = () => {
    setPage(0)
    qc.invalidateQueries({ queryKey: [queryKey] })
    qc.invalidateQueries({ queryKey: ['md-summary'] })
  }
  return (
    <div className="space-y-3">
      <div className="flex justify-end">
        <DeleteAllButton dataTypes={dataTypes} deviceId={deviceId} onDone={refresh} disabled={total === 0} />
      </div>
      {isLoading ? (
        <div className="text-sm text-gray-500">Loading…</div>
      ) : (
        <Table rows={items} columns={columns} emptyText={emptyText} />
      )}
      <Pagination page={page} pageSize={pageSize} total={total} onChange={setPage} />
    </div>
  )
}

function DeviceSelector({ value, onChange }) {
  const { data } = useQuery({
    queryKey: ['metadata-devices'],
    queryFn: () => metadataService.listDeviceIds(),
  })
  const { data: devices } = useQuery({
    queryKey: ['all-devices-for-md'],
    queryFn: () => deviceService.list({ limit: 100 }),
  })
  const enrolledMap = useMemo(() => {
    const m = new Map()
    devices?.data?.devices?.forEach((d) => {
      const label = [d.brand || d.manufacturer, d.device_model].filter(Boolean).join(' ') || 'Device'
      m.set(String(d.id), `${label} (${d.imei || d.serial_number || d.id.slice(0, 8)})`)
    })
    return m
  }, [devices])
  const ids = data?.data?.device_ids || []

  return (
    <div className="flex items-center gap-2">
      <Search className="w-4 h-4 text-gray-400" />
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="border border-gray-300 rounded-md px-3 py-1.5 text-sm bg-white min-w-[260px]"
      >
        <option value="">All devices</option>
        {ids.map((id) => (
          <option key={id} value={id}>{enrolledMap.get(id) || id}</option>
        ))}
      </select>
    </div>
  )
}

// ── Sub-tabs ─────────────────────────────────────────────────────────────

function OverviewTab({ deviceId }) {
  const { data, isLoading } = useQuery({
    queryKey: ['md-summary', deviceId],
    queryFn: () => metadataService.summary(deviceId),
  })
  if (isLoading) return <div className="text-sm text-gray-500">Loading…</div>
  const counts = data?.data?.counts || {}
  const info = data?.data?.latest_device_info
  const sim = data?.data?.latest_sim
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-3">
        <StatCard label="Call logs" value={counts.call_logs} icon={Phone} />
        <StatCard label="Contacts" value={counts.contacts} icon={Users} />
        <StatCard label="SMS" value={counts.sms} icon={MessageSquare} />
        <StatCard label="Locations" value={counts.location_dwell + (counts.location || 0)} icon={MapPin} />
        <StatCard label="Installed apps" value={counts.installed_apps} icon={Smartphone} />
        <StatCard label="Mobile money" value={counts.mobile_money} icon={Banknote} />
        <StatCard label="Recharges" value={counts.telecom_usage} icon={Wifi} />
        <StatCard label="Rides" value={counts.ride_hailing} icon={Bus} />
      </div>

      {info && (
        <div className="bg-white rounded-lg shadow-sm p-4">
          <h3 className="text-sm font-semibold text-gray-900 mb-3">Device info</h3>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
            <Field label="Brand" value={info.brand} />
            <Field label="Model" value={info.model} />
            <Field label="Manufacturer" value={info.manufacturer} />
            <Field label="OS" value={`${info.os_version} (API ${info.api_level})`} />
            <Field label="Security patch" value={info.security_patch} />
            <Field label="Rooted" value={info.is_rooted} />
            <Field
              label="Active SIM"
              value={
                sim?.carrier
                  ? `${sim.carrier}${sim.phone_number ? ` (${sim.phone_number})` : ''}`
                  : (info.network_type || '-')
              }
            />
            <Field label="Network" value={info.network_type} />
            <Field label="RAM" value={info.ram_info} />
            <Field label="Storage" value={info.storage_info} />
            <Field label="Battery" value={info.battery_info} />
            <Field label="Timezone" value={info.timezone} />
          </div>
        </div>
      )}
    </div>
  )
}

function Field({ label, value }) {
  return (
    <div>
      <p className="text-xs text-gray-500">{label}</p>
      <p className="text-sm font-medium text-gray-900 break-words">{value ?? '-'}</p>
    </div>
  )
}

function CallLogsTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['call_logs']}
      queryKey="md-calls"
      fetcher={metadataService.callLogs}
      columns={[
        { key: 'number', label: 'Number' },
        { key: 'type', label: 'Type', render: (r) => (
          <span className={`px-2 py-0.5 rounded-full text-xs ${
            r.type === 'INCOMING' ? 'bg-green-100 text-green-700' :
            r.type === 'OUTGOING' ? 'bg-blue-100 text-blue-700' :
            r.type === 'MISSED' ? 'bg-red-100 text-red-700' :
            'bg-gray-100 text-gray-700'
          }`}>{r.type}</span>
        ) },
        { key: 'duration', label: 'Duration (s)' },
        { key: 'call_date', label: 'When', render: (r) => fmtCallDate(r.call_date) },
        { key: 'created_at', label: 'Synced', render: (r) => fmtDate(r.created_at) },
      ]}
    />
  )
}

function ContactsTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['contacts']}
      queryKey="md-contacts"
      fetcher={metadataService.contacts}
      emptyText="No contacts synced yet"
      columns={[
        { key: 'name', label: 'Name' },
        { key: 'number', label: 'Number' },
        { key: 'type', label: 'Type' },
        { key: 'times_contacted', label: 'Times contacted' },
        { key: 'last_contacted', label: 'Last contacted', render: (r) => fmtCallDate(r.last_contacted) },
        { key: 'created_at', label: 'Synced', render: (r) => fmtDate(r.created_at) },
      ]}
    />
  )
}

function SmsTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['sms']}
      queryKey="md-sms"
      fetcher={metadataService.sms}
      columns={[
        { key: 'address', label: 'From / To' },
        { key: 'type', label: 'Type' },
        { key: 'body', label: 'Message', render: (r) => (
          <span className="block max-w-xl truncate" title={r.body}>{r.body}</span>
        ) },
        { key: 'sms_date', label: 'When', render: (r) => fmtCallDate(r.sms_date) },
      ]}
    />
  )
}

function LocationTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['location', 'location_dwell']}
      queryKey="md-location-dwell"
      fetcher={metadataService.locationDwell}
      columns={[
        { key: 'timestamp', label: 'When' },
        { key: 'address', label: 'Address' },
        { key: 'latitude', label: 'Lat/Lng', render: (r) => (
          <a className="text-blue-600 hover:underline"
             href={`https://www.google.com/maps?q=${r.latitude},${r.longitude}`}
             target="_blank" rel="noreferrer">
            {Number(r.latitude).toFixed(5)}, {Number(r.longitude).toFixed(5)}
          </a>
        ) },
        { key: 'accuracy', label: 'Accuracy (m)', render: (r) => Math.round(r.accuracy || 0) },
        { key: 'location_type', label: 'Type' },
        { key: 'dwell_minutes', label: 'Dwell (min)' },
        { key: 'event_type', label: 'Event' },
      ]}
    />
  )
}

function InstalledAppsTab({ deviceId }) {
  const [page, setPage] = useState(0)
  const [filter, setFilter] = useState('INSTALLED')
  const pageSize = 100
  const qc = useQueryClient()
  useEffect(() => { setPage(0) }, [deviceId])
  const { data, isLoading } = useQuery({
    queryKey: ['md-apps', deviceId, page],
    queryFn: () => metadataService.installedApps(deviceId, pageSize, page * pageSize),
    keepPreviousData: true,
  })
  const total = data?.data?.total ?? (data?.data?.items?.length || 0)
  const rows = (data?.data?.items || []).filter((r) => filter === 'ALL' || r.status === filter)
  const refresh = () => {
    setPage(0)
    qc.invalidateQueries({ queryKey: ['md-apps'] })
    qc.invalidateQueries({ queryKey: ['md-summary'] })
  }
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex gap-2">
          {['INSTALLED','NOT_INSTALLED','SUMMARY','ALL'].map((s) => (
            <button key={s} onClick={() => setFilter(s)}
              className={`px-3 py-1 rounded text-xs font-medium ${
                filter === s ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}>{s}</button>
          ))}
        </div>
        <DeleteAllButton dataTypes={['installed_apps']} deviceId={deviceId} onDone={refresh} disabled={total === 0} />
      </div>
      {isLoading ? (
        <div className="text-sm text-gray-500">Loading…</div>
      ) : (
        <Table
          rows={rows}
          columns={[
            { key: 'app_name', label: 'App' },
            { key: 'package_name', label: 'Package' },
            { key: 'category', label: 'Category' },
            { key: 'version', label: 'Version' },
            { key: 'install_date', label: 'Installed' },
            { key: 'last_update', label: 'Updated' },
            { key: 'status', label: 'Status' },
          ]}
        />
      )}
      <Pagination page={page} pageSize={pageSize} total={total} onChange={setPage} />
    </div>
  )
}

function BehaviorTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['behavior_scores']}
      queryKey="md-behavior"
      fetcher={metadataService.behavior}
      columns={[
        { key: 'timestamp', label: 'When' },
        { key: 'total_calls', label: 'Calls' },
        { key: 'unique_call_contacts', label: 'Contacts' },
        { key: 'network_size', label: 'Network' },
        { key: 'night_ratio', label: 'Night %' },
        { key: 'weekend_ratio', label: 'Weekend %' },
        { key: 'avg_call_duration', label: 'Avg dur (s)' },
        { key: 'total_sms', label: 'SMS' },
        { key: 'unique_locations', label: 'Locations' },
        { key: 'total_mfs_txns', label: 'MFS txns' },
        { key: 'total_mfs_volume', label: 'MFS vol' },
        { key: 'mfs_activity_score', label: 'MFS score' },
      ]}
    />
  )
}

function FinancialTab({ deviceId }) {
  const [mfsPage, setMfsPage] = useState(0)
  const [telPage, setTelPage] = useState(0)
  const pageSize = 50
  const qc = useQueryClient()
  useEffect(() => { setMfsPage(0); setTelPage(0) }, [deviceId])
  const { data: mfs, isLoading: l1 } = useQuery({
    queryKey: ['md-mfs', deviceId, mfsPage],
    queryFn: () => metadataService.mobileMoney(deviceId, pageSize, mfsPage * pageSize),
    keepPreviousData: true,
  })
  const { data: tel, isLoading: l2 } = useQuery({
    queryKey: ['md-telecom', deviceId, telPage],
    queryFn: () => metadataService.telecom(deviceId, pageSize, telPage * pageSize),
    keepPreviousData: true,
  })
  const mfsItems = mfs?.data?.items || []
  const telItems = tel?.data?.items || []
  const mfsTotal = mfs?.data?.total ?? mfsItems.length
  const telTotal = tel?.data?.total ?? telItems.length
  const refresh = () => {
    setMfsPage(0); setTelPage(0)
    qc.invalidateQueries({ queryKey: ['md-mfs'] })
    qc.invalidateQueries({ queryKey: ['md-telecom'] })
    qc.invalidateQueries({ queryKey: ['md-summary'] })
  }
  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <DeleteAllButton dataTypes={['mobile_money', 'telecom_usage']} deviceId={deviceId}
          onDone={refresh} disabled={mfsTotal === 0 && telTotal === 0} label="Delete all financial" />
      </div>
      <section>
        <h3 className="text-sm font-semibold text-gray-900 mb-2">Mobile money (bKash / Nagad)</h3>
        {l1 ? <div className="text-sm text-gray-500">Loading…</div> : (
          <Table
            rows={mfsItems}
            columns={[
              { key: 'timestamp', label: 'When' },
              { key: 'provider', label: 'Provider' },
              { key: 'txn_type', label: 'Type' },
              { key: 'amount', label: 'Amount' },
              { key: 'balance', label: 'Balance' },
              { key: 'counter_party', label: 'Counter party' },
              { key: 'txn_id', label: 'Txn ID' },
            ]}
          />
        )}
        <Pagination page={mfsPage} pageSize={pageSize} total={mfsTotal} onChange={setMfsPage} />
      </section>
      <section>
        <h3 className="text-sm font-semibold text-gray-900 mb-2">Telecom recharges</h3>
        {l2 ? <div className="text-sm text-gray-500">Loading…</div> : (
          <Table
            rows={telItems}
            columns={[
              { key: 'timestamp', label: 'When' },
              { key: 'operator', label: 'Operator' },
              { key: 'recharge_type', label: 'Type' },
              { key: 'amount', label: 'Amount' },
              { key: 'balance', label: 'Balance' },
              { key: 'sender', label: 'Sender' },
            ]}
          />
        )}
        <Pagination page={telPage} pageSize={pageSize} total={telTotal} onChange={setTelPage} />
      </section>
    </div>
  )
}

function RidesTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['ride_hailing']}
      queryKey="md-rides"
      fetcher={metadataService.rides}
      columns={[
        { key: 'timestamp', label: 'When' },
        { key: 'provider', label: 'Provider' },
        { key: 'ride_type', label: 'Type' },
        { key: 'amount', label: 'Amount' },
        { key: 'trip_details', label: 'Details', render: (r) => (
          <span className="block max-w-xl truncate" title={r.trip_details}>{r.trip_details}</span>
        ) },
      ]}
    />
  )
}

function DeviceInfoTab({ deviceId }) {
  return (
    <MetaTable
      deviceId={deviceId}
      dataTypes={['device_info']}
      queryKey="md-deviceinfo"
      fetcher={metadataService.deviceInfo}
      columns={[
        { key: 'timestamp', label: 'When' },
        { key: 'brand', label: 'Brand' },
        { key: 'model', label: 'Model' },
        { key: 'os_version', label: 'OS' },
        { key: 'is_rooted', label: 'Rooted' },
        { key: 'network_type', label: 'Network' },
        { key: 'battery_info', label: 'Battery' },
        { key: 'ram_info', label: 'RAM' },
        { key: 'storage_info', label: 'Storage' },
      ]}
    />
  )
}

// ── Main page ────────────────────────────────────────────────────────────

export default function Metadata() {
  const [params, setParams] = useSearchParams()
  const [tab, setTab] = useState(params.get('tab') || 'overview')
  const [deviceId, setDeviceId] = useState(params.get('device') || '')

  useEffect(() => {
    const next = new URLSearchParams(params)
    next.set('tab', tab)
    if (deviceId) next.set('device', deviceId); else next.delete('device')
    setParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, deviceId])

  return (
    <div className="p-6 space-y-6">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Device Metadata</h1>
          <p className="text-sm text-gray-500">
            Call logs, SMS, location, installed apps, SIM history, and behavior analytics
            collected from enrolled devices.
          </p>
        </div>
        <DeviceSelector value={deviceId} onChange={setDeviceId} />
      </div>

      <div className="flex flex-wrap gap-2 border-b border-gray-200 pb-2">
        {TABS.map((t) => {
          const Icon = t.icon
          const active = tab === t.id
          return (
            <button
              key={t.id}
              onClick={() => setTab(t.id)}
              className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-sm font-medium transition ${
                active
                  ? 'bg-blue-600 text-white shadow-sm'
                  : 'bg-white text-gray-700 hover:bg-gray-100 border border-gray-200'
              }`}
            >
              <Icon className="w-4 h-4" />
              {t.label}
            </button>
          )
        })}
      </div>

      <div className="bg-white rounded-lg shadow-sm p-4">
        {tab === 'overview' && <OverviewTab deviceId={deviceId} />}
        {tab === 'call_logs' && <CallLogsTab deviceId={deviceId} />}
        {tab === 'contacts' && <ContactsTab deviceId={deviceId} />}
        {tab === 'sms' && <SmsTab deviceId={deviceId} />}
        {tab === 'location' && <LocationTab deviceId={deviceId} />}
        {tab === 'installed_apps' && <InstalledAppsTab deviceId={deviceId} />}
        {tab === 'behavior' && <BehaviorTab deviceId={deviceId} />}
        {tab === 'financial' && <FinancialTab deviceId={deviceId} />}
        {tab === 'rides' && <RidesTab deviceId={deviceId} />}
        {tab === 'device_info' && <DeviceInfoTab deviceId={deviceId} />}
      </div>

      {!deviceId && (
        <p className="text-xs text-gray-500">
          Tip: select a device above to filter. <Link to="/" className="text-blue-600">Browse devices</Link>
        </p>
      )}
    </div>
  )
}
