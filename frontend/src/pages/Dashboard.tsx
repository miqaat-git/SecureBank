import { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'
import { Send, Wallet, Plus } from 'lucide-react'
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
    <div className="space-y-8 animate-in fade-in duration-500">
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Welcome back, {user?.preferred_username}</h1>
          <p className="text-slate-500 mt-1">Here is your financial overview for today.</p>
        </div>
      </div>
      
      <div className="grid gap-6 md:grid-cols-3">
        {/* TOTAL BALANCE CARD */}
        <div className="bg-slate-900 rounded-3xl shadow-xl p-8 text-white md:col-span-2 relative overflow-hidden group">
          <div className="absolute top-0 right-0 p-8 opacity-10 group-hover:opacity-20 transition duration-500 transform group-hover:scale-110">
            <Wallet className="w-48 h-48" />
          </div>
          <div className="relative z-10">
            <p className="text-slate-400 font-medium mb-2 uppercase tracking-wider text-sm">Total Available Balance</p>
            <h2 className="text-5xl font-bold tracking-tight mb-6">
              {loading ? '...' : totalBalance.toLocaleString('en-IN', { minimumFractionDigits: 2 })} <span className="text-2xl text-slate-400 font-medium">INR</span>
            </h2>
            <div className="flex gap-4">
              <Link to="/transfers" className="bg-brand-500 hover:bg-brand-600 text-white px-5 py-2.5 rounded-xl font-medium text-sm flex items-center gap-2 transition shadow-lg shadow-brand-500/30">
                <Send className="w-4 h-4" /> Send Money
              </Link>
              <button className="bg-slate-800 hover:bg-slate-700 text-white px-5 py-2.5 rounded-xl font-medium text-sm flex items-center gap-2 transition border border-slate-700">
                <Plus className="w-4 h-4" /> Add Money
              </button>
            </div>
          </div>
        </div>

        {/* STATS CARD */}
        <div className="bg-white rounded-3xl shadow-soft p-8 border border-slate-100 flex flex-col justify-center">
           <p className="text-slate-500 font-medium text-sm mb-1">Active Accounts</p>
           <h3 className="text-4xl font-bold text-slate-900 mb-6">{activeAccounts.length}</h3>
           <div className="space-y-4">
              {activeAccounts.map(acc => (
                <div key={acc.id} className="flex justify-between items-center text-sm">
                  <span className="text-slate-600 flex items-center gap-2">
                     <div className="w-2 h-2 rounded-full bg-green-500"></div>
                     {acc.accountType}
                  </span>
                  <span className="font-semibold text-slate-900">{acc.availableBalance.toFixed(2)} INR</span>
                </div>
              ))}
           </div>
        </div>
      </div>
    </div>
  )
}