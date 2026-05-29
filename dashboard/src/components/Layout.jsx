import { useState } from 'react'
import { Outlet, Link, useLocation } from 'react-router-dom'
import {
  Smartphone,
  Menu,
  X,
  Lock,
  ShieldCheck,
  QrCode,
  Download,
  Wrench,
  BarChart3,
  Cast,
  FolderOpen
} from 'lucide-react'

export default function Layout() {
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const location = useLocation()

  const navItems = [
    { path: '/', icon: Smartphone, label: 'Devices', match: ['/','devices'] },
    { path: '/metadata', icon: BarChart3, label: 'Device Metadata', match: ['metadata'] },
    { path: '/live-stream', icon: Cast, label: 'Live Stream', match: ['live-stream'] },
    { path: '/files', icon: FolderOpen, label: 'File Manager', match: ['files'] },
    { path: '/device-owner', icon: ShieldCheck, label: 'Device Owner Setup', match: ['device-owner'] },
    { path: '/device-setup', icon: QrCode, label: 'Device Setup (QR)', match: ['device-setup'] },
    { path: '/app-download', icon: Download, label: 'App Download QR', match: ['app-download'] },
    { path: '/re-provision', icon: Wrench, label: 'Re-Provision (Service)', match: ['re-provision'] },
  ]

  const isActive = (item) => {
    const p = location.pathname
    if (item.path === '/' && (p === '/' || p === '/devices' || p.startsWith('/devices/'))) return true
    if (item.path !== '/' && p.startsWith(item.path)) return true
    return false
  }

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Mobile sidebar overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-40 bg-black/50 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Sidebar */}
      <aside
        className={`fixed inset-y-0 left-0 z-50 w-64 bg-white shadow-lg transform transition-transform duration-200 ease-in-out lg:translate-x-0 ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex h-full flex-col">
          {/* Logo */}
          <div className="flex h-16 items-center justify-between px-4 border-b">
            <div className="flex items-center gap-2">
              <div className="w-8 h-8 bg-primary-600 rounded-lg flex items-center justify-center">
                <Lock className="w-5 h-5 text-white" />
              </div>
              <span className="text-xl font-bold text-gray-900">RR Locker</span>
            </div>
            <button
              className="lg:hidden p-1 rounded hover:bg-gray-100"
              onClick={() => setSidebarOpen(false)}
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Navigation */}
          <nav className="flex-1 px-4 py-4 space-y-1 overflow-y-auto">
            {navItems.map((item) => {
              const Icon = item.icon
              const active = isActive(item)
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  onClick={() => setSidebarOpen(false)}
                  className={`flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                    active
                      ? 'bg-primary-50 text-primary-700'
                      : 'text-gray-600 hover:bg-gray-50 hover:text-gray-900'
                  }`}
                >
                  <Icon className="w-5 h-5" />
                  {item.label}
                </Link>
              )
            })}
          </nav>
        </div>
      </aside>

      {/* Main content */}
      <div className="lg:pl-64">
        {/* Header */}
        <header className="sticky top-0 z-30 h-16 bg-white border-b flex items-center px-4 lg:px-6">
          <button
            className="lg:hidden p-2 rounded-lg hover:bg-gray-100"
            onClick={() => setSidebarOpen(true)}
          >
            <Menu className="w-5 h-5" />
          </button>
          <div className="ml-auto flex items-center gap-4">
            <span className="text-sm font-medium text-gray-900">RR Locker Admin</span>
          </div>
        </header>

        {/* Page content */}
        <main className="p-4 lg:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
