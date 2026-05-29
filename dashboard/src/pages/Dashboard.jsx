import { useQuery } from '@tanstack/react-query'
import { emiService, deviceService, customerService } from '../services/emiService'
import {
  Smartphone,
  Lock,
  Users,
  FileText,
  AlertTriangle,
  TrendingUp,
  TrendingDown,
  Activity
} from 'lucide-react'

function StatCard({ title, value, icon: Icon, trend, trendValue, color = 'primary' }) {
  const colorClasses = {
    primary: 'bg-primary-50 text-primary-600',
    green: 'bg-green-50 text-green-600',
    red: 'bg-red-50 text-red-600',
    yellow: 'bg-yellow-50 text-yellow-600',
    blue: 'bg-blue-50 text-blue-600',
  }

  return (
    <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-gray-500">{title}</p>
          <p className="text-2xl font-bold text-gray-900 mt-1">{value}</p>
          {trend && (
            <div className={`flex items-center gap-1 mt-2 text-sm ${trend === 'up' ? 'text-green-600' : 'text-red-600'}`}>
              {trend === 'up' ? <TrendingUp className="w-4 h-4" /> : <TrendingDown className="w-4 h-4" />}
              <span>{trendValue}</span>
            </div>
          )}
        </div>
        <div className={`w-12 h-12 rounded-lg flex items-center justify-center ${colorClasses[color]}`}>
          <Icon className="w-6 h-6" />
        </div>
      </div>
    </div>
  )
}

function RecentActivity({ activities }) {
  return (
    <div className="bg-white rounded-xl p-6 shadow-sm border border-gray-100">
      <h3 className="text-lg font-semibold text-gray-900 mb-4">Recent Activity</h3>
      <div className="space-y-4">
        {activities?.length > 0 ? (
          activities.map((activity, index) => (
            <div key={index} className="flex items-start gap-3">
              <div className={`w-2 h-2 rounded-full mt-2 ${
                activity.type === 'lock' ? 'bg-red-500' :
                activity.type === 'unlock' ? 'bg-green-500' :
                activity.type === 'payment' ? 'bg-blue-500' : 'bg-gray-500'
              }`} />
              <div className="flex-1 min-w-0">
                <p className="text-sm text-gray-900">{activity.message}</p>
                <p className="text-xs text-gray-500 mt-0.5">{activity.time}</p>
              </div>
            </div>
          ))
        ) : (
          <p className="text-sm text-gray-500 text-center py-4">No recent activity</p>
        )}
      </div>
    </div>
  )
}

function OverdueTable({ devices }) {
  return (
    <div className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden">
      <div className="px-6 py-4 border-b border-gray-100">
        <h3 className="text-lg font-semibold text-gray-900">Overdue Payments</h3>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Customer</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Device</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Amount Due</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Days Overdue</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {devices?.length > 0 ? (
              devices.slice(0, 5).map((item, index) => (
                <tr key={index} className="hover:bg-gray-50">
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">{item.customer_name}</td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{item.device_model}</td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">₹{item.amount_due?.toLocaleString()}</td>
                  <td className="px-6 py-4 whitespace-nowrap text-sm text-red-600">{item.days_overdue} days</td>
                  <td className="px-6 py-4 whitespace-nowrap">
                    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium ${
                      item.device_locked ? 'bg-red-100 text-red-800' : 'bg-yellow-100 text-yellow-800'
                    }`}>
                      {item.device_locked ? 'Locked' : 'Warning'}
                    </span>
                  </td>
                </tr>
              ))
            ) : (
              <tr>
                <td colSpan={5} className="px-6 py-8 text-center text-sm text-gray-500">
                  No overdue payments
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default function Dashboard() {
  const { data: stats, isLoading: statsLoading } = useQuery({
    queryKey: ['dashboard-stats'],
    queryFn: () => emiService.getStats(),
    refetchInterval: 30000, // Refresh every 30 seconds
  })

  const { data: overdueData, isLoading: overdueLoading } = useQuery({
    queryKey: ['overdue'],
    queryFn: () => emiService.getOverdue(),
  })

  const statsData = stats?.data
  const overdueList = overdueData?.data || []

  // Mock recent activity - in real app, this would come from an API
  const recentActivity = [
    { type: 'lock', message: 'Device locked for customer Rahul Kumar', time: '2 minutes ago' },
    { type: 'payment', message: 'Payment received from Priya Sharma - ₹5,000', time: '15 minutes ago' },
    { type: 'unlock', message: 'Device unlocked for customer Amit Singh', time: '1 hour ago' },
    { type: 'warning', message: 'Warning sent to Deepak Verma', time: '2 hours ago' },
    { type: 'payment', message: 'Payment received from Neha Gupta - ₹3,500', time: '3 hours ago' },
  ]

  if (statsLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary-600"></div>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-500 mt-1">Overview of your RR Locker system</p>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total Devices"
          value={statsData?.total_devices || 0}
          icon={Smartphone}
          color="blue"
        />
        <StatCard
          title="Locked Devices"
          value={statsData?.locked_devices || 0}
          icon={Lock}
          color="red"
        />
        <StatCard
          title="Active Contracts"
          value={statsData?.active_contracts || 0}
          icon={FileText}
          color="green"
        />
        <StatCard
          title="Overdue Payments"
          value={statsData?.overdue_payments || overdueList.length}
          icon={AlertTriangle}
          color="yellow"
        />
      </div>

      {/* Secondary Stats */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <StatCard
          title="Total Customers"
          value={statsData?.total_customers || 0}
          icon={Users}
          color="primary"
        />
        <StatCard
          title="Online Devices"
          value={statsData?.online_devices || 0}
          icon={Activity}
          color="green"
        />
        <StatCard
          title="This Month's Collection"
          value={`₹${(statsData?.monthly_collection || 0).toLocaleString()}`}
          icon={TrendingUp}
          color="blue"
        />
      </div>

      {/* Content Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Overdue Table - Takes 2 columns */}
        <div className="lg:col-span-2">
          <OverdueTable devices={overdueList} />
        </div>

        {/* Recent Activity */}
        <div>
          <RecentActivity activities={recentActivity} />
        </div>
      </div>
    </div>
  )
}
