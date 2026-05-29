import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { customerService } from '../services/emiService'
import toast from 'react-hot-toast'
import {
  Search,
  Plus,
  User,
  Phone,
  Mail,
  MapPin,
  X,
  ChevronLeft,
  ChevronRight,
  Smartphone,
  Edit2,
  Trash2
} from 'lucide-react'

function CustomerCard({ customer, onClick, onDelete }) {
  return (
    <div
      className="bg-white rounded-xl p-5 shadow-sm border border-gray-100 hover:shadow-md hover:border-primary-200 transition-all"
    >
      <div className="flex items-start gap-4 cursor-pointer" onClick={onClick}>
        <div className="w-12 h-12 bg-primary-100 rounded-full flex items-center justify-center flex-shrink-0">
          <span className="text-primary-700 font-semibold text-lg">
            {customer.name?.[0]?.toUpperCase() || 'C'}
          </span>
        </div>
        <div className="flex-1 min-w-0">
          <h3 className="text-sm font-semibold text-gray-900 truncate">{customer.name}</h3>
          <div className="mt-2 space-y-1">
            <div className="flex items-center gap-2 text-sm text-gray-500">
              <Phone className="w-3.5 h-3.5" />
              <span>{customer.phone || '-'}</span>
            </div>
            <div className="flex items-center gap-2 text-sm text-gray-500">
              <Mail className="w-3.5 h-3.5" />
              <span className="truncate">{customer.email || '-'}</span>
            </div>
          </div>
        </div>
      </div>
      <div className="mt-4 pt-4 border-t border-gray-100 flex items-center justify-between">
        <div className="flex items-center gap-1 text-sm text-gray-500">
          <Smartphone className="w-4 h-4" />
          <span>{customer.devices_count || 0} device(s)</span>
        </div>
        <div className="flex items-center gap-2">
          <span className={`text-xs px-2 py-1 rounded-full ${
            customer.status === 'active' ? 'bg-green-100 text-green-700' : 'bg-gray-100 text-gray-600'
          }`}>
            {customer.status || 'Active'}
          </span>
          <button
            onClick={(e) => { e.stopPropagation(); onDelete(customer); }}
            className="p-1.5 text-red-400 hover:text-red-600 hover:bg-red-50 rounded transition-colors"
            title="Delete Customer"
          >
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  )
}

function CreateCustomerModal({ isOpen, onClose, onSubmit, isLoading }) {
  const [formData, setFormData] = useState({
    name: '',
    phone: '',
    email: '',
    address: '',
    id_type: 'aadhar',
    id_number: '',
  })

  const handleSubmit = (e) => {
    e.preventDefault()
    if (!formData.name || !formData.phone) {
      toast.error('Name and phone are required')
      return
    }
    onSubmit(formData)
  }

  if (!isOpen) return null

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="fixed inset-0 bg-black/50" onClick={onClose} />
      <div className="relative bg-white rounded-xl shadow-xl max-w-md w-full mx-4 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between p-4 border-b">
          <h2 className="text-lg font-semibold text-gray-900">Add New Customer</h2>
          <button onClick={onClose} className="p-1 hover:bg-gray-100 rounded">
            <X className="w-5 h-5" />
          </button>
        </div>
        <form onSubmit={handleSubmit} className="p-4 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Name *</label>
            <input
              type="text"
              value={formData.name}
              onChange={(e) => setFormData({ ...formData, name: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
              placeholder="Enter full name"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Phone *</label>
            <input
              type="tel"
              value={formData.phone}
              onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
              placeholder="Enter phone number"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Email</label>
            <input
              type="email"
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
              placeholder="Enter email address"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Address</label>
            <textarea
              value={formData.address}
              onChange={(e) => setFormData({ ...formData, address: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
              rows={2}
              placeholder="Enter address"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">ID Type</label>
              <select
                value={formData.id_type}
                onChange={(e) => setFormData({ ...formData, id_type: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
              >
                <option value="aadhar">Aadhar Card</option>
                <option value="pan">PAN Card</option>
                <option value="voter">Voter ID</option>
                <option value="driving">Driving License</option>
              </select>
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">ID Number</label>
              <input
                type="text"
                value={formData.id_number}
                onChange={(e) => setFormData({ ...formData, id_number: e.target.value })}
                className="w-full px-3 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
                placeholder="Enter ID number"
              />
            </div>
          </div>
          <div className="flex gap-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 px-4 py-2.5 border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isLoading}
              className="flex-1 px-4 py-2.5 bg-primary-600 text-white rounded-lg hover:bg-primary-700 disabled:opacity-50 transition-colors"
            >
              {isLoading ? 'Creating...' : 'Create Customer'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function Customers() {
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [showModal, setShowModal] = useState(false)
  const limit = 12
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['customers', page, search],
    queryFn: () => customerService.list({
      skip: (page - 1) * limit,
      limit,
      search: search || undefined,
    }),
    keepPreviousData: true,
  })

  const createMutation = useMutation({
    mutationFn: (data) => customerService.create(data),
    onSuccess: () => {
      toast.success('Customer created successfully')
      setShowModal(false)
      queryClient.invalidateQueries(['customers'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to create customer')
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (customer) => customerService.delete(customer.id),
    onSuccess: () => {
      toast.success('Customer deleted successfully')
      queryClient.invalidateQueries(['customers'])
    },
    onError: (error) => {
      toast.error(error.response?.data?.detail || 'Failed to delete customer')
    },
  })

  const handleDeleteCustomer = (customer) => {
    if (confirm(`⚠️ Are you sure you want to DELETE customer "${customer.name}"?\n\nThis will also delete all their contracts and payments.\n\nThis action CANNOT be undone!`)) {
      deleteMutation.mutate(customer)
    }
  }

  const customers = data?.data?.items || data?.data || []
  const total = data?.data?.total || customers.length
  const totalPages = Math.ceil(total / limit)

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Customers</h1>
          <p className="text-gray-500 mt-1">Manage customer information</p>
        </div>
        <button
          onClick={() => setShowModal(true)}
          className="flex items-center gap-2 px-4 py-2.5 bg-primary-600 text-white rounded-lg hover:bg-primary-700 transition-colors"
        >
          <Plus className="w-4 h-4" />
          Add Customer
        </button>
      </div>

      {/* Search */}
      <div className="bg-white rounded-xl p-4 shadow-sm border border-gray-100">
        <div className="relative">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
          <input
            type="text"
            placeholder="Search by name, phone, or email..."
            value={search}
            onChange={(e) => {
              setSearch(e.target.value)
              setPage(1)
            }}
            className="w-full pl-10 pr-4 py-2.5 border border-gray-300 rounded-lg focus:ring-2 focus:ring-primary-500 focus:border-primary-500"
          />
        </div>
      </div>

      {/* Grid */}
      {isLoading ? (
        <div className="flex items-center justify-center h-64">
          <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
        </div>
      ) : customers.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-64 text-gray-500">
          <User className="w-12 h-12 mb-3 text-gray-300" />
          <p>No customers found</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {customers.map((customer) => (
            <CustomerCard
              key={customer.id}
              customer={customer}
              onClick={() => {/* Navigate to detail */}}
              onDelete={handleDeleteCustomer}
            />
          ))}
        </div>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <div className="flex items-center justify-between">
          <p className="text-sm text-gray-500">
            Showing {(page - 1) * limit + 1} to {Math.min(page * limit, total)} of {total} customers
          </p>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setPage(p => Math.max(1, p - 1))}
              disabled={page === 1}
              className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronLeft className="w-5 h-5" />
            </button>
            <span className="text-sm text-gray-600">Page {page} of {totalPages}</span>
            <button
              onClick={() => setPage(p => Math.min(totalPages, p + 1))}
              disabled={page === totalPages}
              className="p-2 rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <ChevronRight className="w-5 h-5" />
            </button>
          </div>
        </div>
      )}

      {/* Create Modal */}
      <CreateCustomerModal
        isOpen={showModal}
        onClose={() => setShowModal(false)}
        onSubmit={(data) => createMutation.mutate(data)}
        isLoading={createMutation.isLoading}
      />
    </div>
  )
}
