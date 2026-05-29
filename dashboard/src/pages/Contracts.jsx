import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { emiService, customerService, deviceService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  Search,
  Plus,
  FileText,
  Calendar,
  IndianRupee,
  X,
  ChevronLeft,
  ChevronRight,
  Eye,
  CheckCircle,
  Clock,
  AlertTriangle,
  Trash2
} from 'lucide-react'

function ContractStatusBadge({ status }) {
  const config = {
    active: { label: 'Active', className: 'bg-green-100 text-green-800' },
    completed: { label: 'Completed', className: 'bg-blue-100 text-blue-800' },
    defaulted: { label: 'Defaulted', className: 'bg-red-100 text-red-800' },
    cancelled: { label: 'Cancelled', className: 'bg-gray-100 text-gray-800' },
  }
  const c = config[status] || config.active
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${c.className}`}>
      {c.label}
    </span>
  )
}

function ContractRow({ contract, onView, onDelete }) {
  const paidAmount = contract.paid_amount || 0
  const totalAmount = contract.total_amount || 0
  const progress = totalAmount > 0 ? (paidAmount / totalAmount) * 100 : 0

  return (
    <tr className="hover:bg-gray-50">
      <td className="px-6 py-4 whitespace-nowrap">
        <p className="text-sm font-medium text-gray-900">{contract.contract_number}</p>
        <p className="text-xs text-gray-500">{contract.customer?.name || 'Unknown'}</p>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <p className="text-sm text-gray-900">{contract.device?.device_model || 'Unknown'}</p>
        <p className="text-xs text-gray-500">{contract.device?.imei}</p>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <p className="text-sm font-medium text-gray-900">₹{totalAmount.toLocaleString()}</p>
        <p className="text-xs text-gray-500">₹{contract.emi_amount?.toLocaleString()}/month</p>
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="w-24">
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs text-gray-500">{contract.paid_installments || 0}/{contract.total_installments}</span>
          </div>
          <div className="w-full bg-gray-200 rounded-full h-1.5">
            <div
              className="bg-primary-600 h-1.5 rounded-full transition-all"
              style={{ width: `${Math.min(progress, 100)}%` }}
            />
          </div>
        </div>
      </td>
      <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">
        {contract.start_date ? new Date(contract.start_date).toLocaleDateString() : '-'}
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <ContractStatusBadge status={contract.status} />
      </td>
      <td className="px-6 py-4 whitespace-nowrap">
        <div className="flex items-center gap-2">
          <button
            onClick={() => onView(contract)}
            className="p-1.5 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded"
            title="View Details"
          >
            <Eye className="w-4 h-4" />
          </button>
          <button
            onClick={() => onDelete(contract)}
            className="p-1.5 text-red-400 hover:text-red-600 hover:bg-red-50 rounded"
            title="Delete Contract"
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </td>
    </tr>
  )
}

function CreateContractModal({ isOpen, onClose, onSubmit, isLoading }) {
  const [formData, setFormData] = useState({
    customer_id: '',
    device_id: '',
    device_price: '',
    down_payment: '',
    emi_amount: '',
    total_installments: '12',
    start_date: new Date().toISOString().split('T')[0],
  })

  const { data: customersData } = useQuery({
    queryKey: ['customers-list'],
    queryFn: () => customerService.list({ limit: 100 }),
    enabled: isOpen,
  })

  const { data: devicesData } = useQuery({
    queryKey: ['devices-list'],
    queryFn: () => deviceService.list({ limit: 100, status: 'pending' }),
    enabled: isOpen,
  })

  const customers = customersData?.data?.items || customersData?.data || []
  const devices = devicesData?.data?.items || devicesData?.data || []

  const handleSubmit = (e) => {
    e.preventDefault()
    if (!formData.customer_id || !formData.device_id || !formData.emi_amount) {
      toast.error('Please fill all required fields')
      return
    }
    onSubmit({
      ...formData,
      customer_id: parseInt(formData.customer_id),
      device_id: parseInt(formData.device_id),
      device_price: parseFloat(formData.device_price) || 0,
      down_payment: parseFloat(formData.down_payment) || 0,
      emi_amount: parseFloat(formData.emi_amount),
      total_installments: parseInt(formData.total_installments),
    })
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="fixed inset-0 bg-black/50" onClick={onClose} />
      <div className="relative bg-white rounded-xl shadow-xl max-w-lg w-full mx-4 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between p-4 border-b">
          <h2 className="text-lg font-semibold text-gray-900">Create EMI Contract</h2>
          <button onClick={onClose} className="p-1 hover:bg-gray-100 rounded">
            <X className="w-5 h-5" />
          </button>
        </div>
        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Customer *</label>
            <select
              value={formData.customer_id}
              onChange={(e) => setFormData({ ...formData, customer_id: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
            >
              <option value="">Select customer</option>
              {customers.map((c) => (
                <option key={c.id} value={c.id}>{c.name} - {c.phone}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Device *</label>
            <select
              value={formData.device_id}
              onChange={(e) => setFormData({ ...formData, device_id: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
            >
              <option value="">Select device</option>
              {devices.map((d) => (
                <option key={d.id} value={d.id}>{d.device_model} - {d.imei}</option>
              ))}
            </select>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Device Price</label>
              <input
                type="number"
                value={formData.device_price}
                onChange={(e) => setFormData({ ...formData, device_price: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
                placeholder="₹"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Down Payment</label>
              <input
                type="number"
                value={formData.down_payment}
                onChange={(e) => setFormData({ ...formData, down_payment: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
                placeholder="₹"
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">EMI Amount *</label>
              <input
                type="number"
                value={formData.emi_amount}
                onChange={(e) => setFormData({ ...formData, emi_amount: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
                placeholder="₹/month"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Installments *</label>
              <select
                value={formData.total_installments}
                onChange={(e) => setFormData({ ...formData, total_installments: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
              >
                {[3, 6, 9, 12, 18, 24].map((n) => (
                  <option key={n} value={n}>{n} months</option>
                ))}
              </select>
            </div>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Start Date</label>
            <input
              type="date"
              value={formData.start_date}
              onChange={(e) => setFormData({ ...formData, start_date: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
            />
          </div>
          <div className="flex gap-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isLoading}
              className="flex-1 px-4 py-2.5 bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50"
            >
              {isLoading ? 'Creating...' : 'Create Contract'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function Contracts() {
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('all')
  const [page, setPage] = useState(1)
  const [showModal, setShowModal] = useState(false)
  const limit = 10
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['contracts', page, search, statusFilter],
    queryFn: () => emiService.listContracts({
      skip: (page - 1) * limit,
      limit,
      search: search || undefined,
      status: statusFilter !== 'all' ? statusFilter : undefined,
    }),
    keepPreviousData: true,
  })

  const createMutation = useMutation({
    mutationFn: (data) => emiService.createContract(data),
    onSuccess: () => {
      toast.success('Contract created successfully')
      setShowModal(false)
      queryClient.invalidateQueries(['contracts'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to create contract')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (contract) => emiService.deleteContract(contract.id),
    onSuccess: () => {
      toast.success('Contract deleted successfully')
      queryClient.invalidateQueries(['contracts'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to delete contract')
    },
  })

  const handleDeleteContract = (contract) => {
    if (confirm(`⚠️ Are you sure you want to DELETE contract "${contract.contract_number}"?\n\nThis will also delete all payment records.\n\nThis action CANNOT be undone!`)) {
      deleteMutation.mutate(contract)
    }
  }

  const contracts = data?.data?.items || data?.data || []
  const total = data?.data?.total || contracts.length
  const totalPages = Math.ceil(total / limit)

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Contracts</h1>
          <p className="text-gray-500 mt-1">Manage EMI contracts</p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center gap-2 px-4 py-2.5 bg-primary-600 text-white rounded-lg hover:bg-primary-700"
        >
          <Plus className="w-4 h-4" />
          New Contract
        </button>
      </div>

      {/* Filters */}
      <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
        <div className="flex flex-col sm:flex-row gap-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
            <input
              type="text"
              placeholder="Search contracts..."
              value={search}
              onChange={(e) => {
                setSearch(e.target.value)
                setPage(1)
              }}
              className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
            />
          </div>
          <select
            value={statusFilter}
            onChange={(e) => {
              setStatusFilter(e.target.value)
              setPage(1)
            }}
            className="px-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500"
          >
            <option value="all">All Status</option>
            <option value="active">Active</option>
            <option value="completed">Completed</option>
            <option value="defaulted">Defaulted</option>
          </select>
        </div>
      </div>

      {/* Table */}
      <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
        {isLoading ? (
          <div className="flex items-center justify-center h-64">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
          </div>
        ) : contracts.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-64 text-gray-500">
            <FileText className="w-12 h-12 mb-3 text-gray-300" />
            <p>No contracts found</p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Contract</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Device</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Amount</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Progress</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Start Date</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Status</th>
                  <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {contracts.map((contract) => (
                  <ContractRow
                    key={contract.id}
                    contract={contract}
                    onView={(c) => {/* Navigate or show detail modal */}}
                    onDelete={handleDeleteContract}
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

      {/* Create Modal */}
      <CreateContractModal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        onSubmit={(data) => createMutation.mutate(data)}
        isLoading={createMutation.isLoading}
      />
    </div>
  )
}
