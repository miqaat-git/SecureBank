import { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'
import { ArrowUpRight, ArrowDownRight, Wallet } from 'lucide-react'
import { Link } from 'react-router-dom'

type Account = {
  id: string
  accountNumber: string
  accountType: string
  status: string
  currency: string
  availableBalance: number
}

export default function Dashboard() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!user?.customer_id) return
    api.get<{ content: Account[] }>(`/api/accounts?customerId=${user.customer_id}`)
      .then((r) => setAccounts(r.data.content))
      .finally(() => setLoading(false))
  }, [user?.customer_id])

  const totalBalance = accounts.reduce((sum, acc) => sum + (acc.status === 'ACTIVE' ? acc.availableBalance : 0), 0)
  const activeAccounts = accounts.filter(a => a.status === 'ACTIVE')

  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-900 mb-6">Overview</h1>
      
      <div className="grid gap-6 md:grid-cols-3 mb-8">
        <div className="bg-brand-600 rounded-2xl shadow-lg p-6 text-white md:col-span-2 relative overflow-hidden">
          <Wallet className="absolute -right-4 -top-4 w-32 h-32 text-white opacity-10" />
          <p className="text-brand-100 font-medium mb-1">Total Available Balance</p>
          <h2 className="text-4xl font-bold tracking-tight">
            {loading ? '...' : totalBalance.toFixed(2)} <span className="text-2xl text-brand-200">INR</span>
          </h2>
          <p className="text-sm text-brand-200 mt-2">Across {activeAccounts.length} active accounts</p>
        </div>
      </div>

      <h2 className="text-lg font-bold text-slate-900 mb-4">Recent Accounts</h2>
      {loading ? (
        <p className="text-slate-500">Loading accounts...</p>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {accounts.map(acc => (
             <div key={acc.id} className="bg-white border border-slate-200 rounded-xl p-5 shadow-sm hover:shadow-md transition">
               <div className="flex justify-between items-start mb-4">
                  <div>
                    <p className="font-bold text-lg text-slate-900">{acc.accountType}</p>
                    <p className="text-sm text-slate-500 font-mono mt-0.5">{acc.accountNumber}</p>
                  </div>
                  <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${
                        acc.status === 'ACTIVE' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                      }`}>
                    {acc.status}
                  </span>
               </div>
               <p className="text-2xl font-bold text-slate-900 mb-4">
                 {acc.availableBalance.toFixed(2)} <span className="text-sm font-normal text-slate-500">{acc.currency}</span>
               </p>
               <Link to="/accounts" className="text-sm text-brand-600 font-medium hover:underline">View Details</Link>
             </div>
          ))}
        </div>
      )}
    </div>
  )
}