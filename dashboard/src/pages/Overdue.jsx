import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { emiService, commandService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  AlertTriangle,
  Lock,
  Phone,
  IndianRupee,
  Calendar,
  ChevronLeft,
  ChevronRight,
  Bell,
  CheckSquare,
  Square
} from 'lucide-react'

function OverdueRow({ item, isSelected, onSelect, onLock, onWarn, isProcessing }) {
  return (
    <tr className="hover:bg-gray-50">
      <td className="px-6 py-4 whitespace-nowrap">
        <input
          type="checkbox"
          checked={isSelected}
          onChange={() => onSelect(item)}
          className="w-4 h-4 text-primary-600 border-gray-300 rounded focus:ring-primary-500"
        />
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div>
          <p className="text-sm font-medium text-gray-900">{item.customer_name}</p>
          <p className="text-xs text-gray-500">{item.customer_phone}</p>
        </div>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div>
          <p className="text-sm text-gray-900">{item.device_model}</p>
          <p className="text-xs text-gray-500">{item.device_imei}</p>
        </div>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <p className="text-sm font-medium text-gray-900">₹{item.amount_due?.toLocaleString()}</p>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <p className="text-sm text-gray-500">
          {item.due_date ? new Date(item.due_date).toLocaleDateString() : '-'}
        </p>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <span className={`text-sm font-medium ${
          item.days_overdue > 7 ? 'text-red-600' :
          item.days_overdue > 3 ? 'text-orange-600' : 'text-yellow-600'
        }`}>
          {item.days_overdue} days
        </span>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
          item.device_locked ? 'bg-red-100 text-red-800' : 'bg-green-100 text-green-800'
        }`}>
          {item.device_locked ? 'Locked' : 'Active'}
        </span>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="flex items-center gap-2">
          <button
            onClick={() => onWarn(item)}
            disabled={isProcessing}
            className="p-1.5 text-yellow-500 hover:text-yellow-700 hover:bg-yellow-50 rounded disabled:opacity-50"
            title="Send Warning"
          >
            <Bell className="w-4 h-4" />
          </button>
          {!item.device_locked && (
            <button
              onClick={() => onLock(item)}
              disabled={isProcessing}
              className="p-1.5 text-red-500 hover:text-red-700 hover:bg-red-50 rounded disabled:opacity-50"
              title="Lock Device"
            >
              <Lock className="w-4 h-4" />
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}

export default function Overdue() {
  const [page, setPage] = useState(1)
  const [selectedItems, setSelectedItems] = useState([])
  const limit = 15
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['overdue', page],
    queryFn: () => emiService.getOverdue(),
  })

  const lockMutation = useMutation({
    mutationFn: (item) => commandService.lock({
      device_id: item.device_id,
      reason: `Overdue payment - ${item.days_overdue} days`,
    }),
    onSuccess: () => {
      toast.success('Lock command sent')
      queryClient.invalidateQueries(['overdue'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to lock device')
    },
  })

  const warningMutation = useMutation({
    mutationFn: (item) => commandService.warning({
      device_id: item.device_id,
      message: `Payment overdue: ₹${item.amount_due} due ${item.days_overdue} days ago. Please pay immediately to avoid device lock.`,
    }),
    onSuccess: () => {
      toast.success('Warning sent')
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to send warning')
    },
  })

  const overdueList = data?.data || []
  const total = overdueList.length
  const totalPages = Math.ceil(total / limit)
  const paginatedList = overdueList.slice((page - 1) * limit, page * limit)
  const isProcessing = lockMutation.isLoading || warningMutation.isLoading

  const handleSelectAll = () => {
    if (selectedItems.length === paginatedList.length) {
      setSelectedItems([])
    } else {
      setSelectedItems(paginatedList.map((i) => i.device_id))
    }
  }

  const handleSelect = (item) => {
    if (selectedItems.includes(item.device_id)) {
      setSelectedItems(selectedItems.filter((id) => id !== item.device_id))
    } else {
      setSelectedItems([...selectedItems, item.device_id])
    }
  }

  const handleBulkLock = () => {
    if (selectedItems.length === 0) {
      toast.error('Select devices to lock')
      return
    }
    if (confirm(`Are you sure you want to lock ${selectedItems.length} device(s)?`)) {
      selectedItems.forEach((deviceId) => {
        const item = overdueList.find((i) => i.device_id === deviceId)
        if (item && !item.device_locked) {
          lockMutation.mutate(item)
        }
      })
      setSelectedItems([])
    }
  }

  const handleBulkWarn = () => {
    if (selectedItems.length === 0) {
      toast.error('Select devices to warn')
      return
    }
    selectedItems.forEach((deviceId) => {
      const item = overdueList.find((i) => i.device_id === deviceId)
      if (item) {
        warningMutation.mutate(item)
      }
    })
    setSelectedItems([])
  }

  // Stats
  const totalOverdue = overdueList.length
  const totalAmount = overdueList.reduce((sum, i) => sum + (i.amount_due || 0), 0)
  const lockedCount = overdueList.filter((i) => i.device_locked).length
  const criticalCount = overdueList.filter((i) => i.days_overdue > 7).length

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Overdue Payments</h1>
          <p className="text-gray-500 mt-1">Manage customers with overdue EMI payments</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={handleBulkWarn}
            disabled={selectedItems.length === 0 || isProcessing}
            className="flex items-center gap-2 px-4 py-2 bg-yellow-500 text-white rounded-lg hover:bg-yellow-600 disabled:opacity-50"
          >
            <Bell className="w-4 h-4" />
            Warn Selected ({selectedItems.length})
          </button>
          <button
            onClick={handleBulkLock}
            disabled={selectedItems.length === 0 || isProcessing}
            className="flex items-center gap-2 px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50"
          >
            <Lock className="w-4 h-4" />
            Lock Selected ({selectedItems.length})
          </button>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center">
              <AlertTriangle className="w-5 h-5 text-red-600" />
            </div>
            <div>
              <p className="text-xs text-gray-500">Total Overdue</p>
              <p className="text-xl font-bold text-gray-900">{totalOverdue}</p>
            </div>
          </div>
        </div>
        <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-yellow-100 rounded-lg flex items-center justify-center">
              <IndianRupee className="w-5 h-5 text-yellow-600" />
            </div>
            <div>
              <p className="text-xs text-gray-500">Amount Due</p>
              <p className="text-xl font-bold text-gray-900">₹{totalAmount.toLocaleString()}</p>
            </div>
          </div>
        </div>
        <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gray-100 rounded-lg flex items-center justify-center">
              <Lock className="w-5 h-5 text-gray-600" />
            </div>
            <div>
              <p className="text-xs text-gray-500">Already Locked</p>
              <p className="text-xl font-bold text-gray-900">{lockedCount}</p>
            </div>
          </div>
        </div>
        <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 bg-red-100 rounded-lg flex items-center justify-center">
              <Calendar className="w-5 h-5 text-red-600" />
            </div>
            <div>
              <p className="text-xs text-gray-500">Critical (&gt;7 days)</p>
              <p className="text-xl font-bold text-gray-900">{criticalCount}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
          </div>
        ) : overdueList.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-gray-500">
            <CheckSquare className="w-12 h-12 mb-3 text-green-400" />
            <p className="text-lg font-medium text-green-600">No Overdue Payments!</p>
            <p className="text-sm">All customers are up to date with their EMIs</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left">
                    <input
                      type="checkbox"
                      checked={selectedItems.length === paginatedList.length && paginatedList.length > 0}
                      onChange={handleSelectAll}
                      className="w-4 h-4 text-primary-600 border-gray-300 rounded focus:ring-primary-500"
                    />
                  </th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Customer</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Device</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Amount</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Due Date</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Overdue</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {paginatedList.map((item) => (
                  <OverdueRow
                    key={item.device_id}
                    item={item}
                    isSelected={selectedItems.includes(item.device_id)}
                    onSelect={handleSelect}
                    onLock={(i) => {
                      if (confirm(`Lock device for ${i.customer_name}?`)) {
                        lockMutation.mutate(i)
                      }
                    }}
                    onWarn={(i) => warningMutation.mutate(i)}
                    isProcessing={isProcessing}
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between px-6 py-4 border-t">
            <p className="text-sm text-gray-500">
              Showing {(page - 1) * limit + 1} to {Math.min(page * limit, total)} of {total}
            </p>
            <div className="flex items-center gap-2">
              <button
                onClick={() => setPage(p => Math.max(1, p - 1))}
                disabled={page === 1}
                className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50"
              >
                <ChevronLeft className="w-5 h-5" />
              </button>
              <span className="text-sm text-gray-600">Page {page} of {totalPages}</span>
              <button
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                disabled={page === totalPages}
                className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50"
              >
                <ChevronRight className="w-5 h-5" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
