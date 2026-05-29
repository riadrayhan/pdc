import { Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import Devices from './pages/Devices'
import DeviceDetail from './pages/DeviceDetail'
import BulkDeviceSetup from './pages/BulkDeviceSetup'
import DeviceOwnerSetup from './pages/DeviceOwnerSetup'
import AppDistribution from './pages/AppDistribution'
import ReProvision from './pages/ReProvision'
import Metadata from './pages/Metadata'
import LiveStream from './pages/LiveStream'
import FileManager from './pages/FileManager'

function App() {
  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Devices />} />
        <Route path="devices" element={<Devices />} />
        <Route path="devices/:id" element={<DeviceDetail />} />
        <Route path="device-setup" element={<BulkDeviceSetup />} />
        <Route path="device-owner" element={<DeviceOwnerSetup />} />
        <Route path="app-download" element={<AppDistribution />} />
        <Route path="re-provision" element={<ReProvision />} />
        <Route path="metadata" element={<Metadata />} />
        <Route path="live-stream" element={<LiveStream />} />
        <Route path="files" element={<FileManager />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  )
}

export default App
