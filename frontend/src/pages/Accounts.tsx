import { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'

type Account = {
  id: string
  accountNumber: string
  accountType: string
  status: string
  currency: string
  availableBalance: number
  createdAt: string
}

export default function Accounts() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!user?.customer_id) return
    api.get<{ content: Account[] }>(`/api/accounts?customerId=${user.customer_id}`)
      .then((r) => setAccounts(r.data.content))
      .finally(() => setLoading(false))
  }, [user?.customer_id])

  return (
    <div>
      <div className="flex justify-between items-center mb-6">
        <h1 className="text-2xl font-bold text-slate-900">My Accounts</h1>
        <button className="bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 transition">
          Apply for Account
        </button>
      </div>

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-slate-50 text-slate-500 text-xs uppercase tracking-wider border-b border-slate-200">
              <th className="px-6 py-4 font-medium">Account Type</th>
              <th className="px-6 py-4 font-medium">Account Number</th>
              <th className="px-6 py-4 font-medium">Status</th>
              <th className="px-6 py-4 font-medium text-right">Balance</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <tr><td colSpan={4} className="px-6 py-8 text-center text-slate-500">Loading accounts...</td></tr>}
            {!loading && accounts.length === 0 && <tr><td colSpan={4} className="px-6 py-8 text-center text-slate-500">No accounts found.</td></tr>}
            {accounts.map(acc => (
              <tr key={acc.id} className="hover:bg-slate-50 transition">
                <td className="px-6 py-4 font-medium text-slate-900">{acc.accountType}</td>
                <td className="px-6 py-4 font-mono text-sm text-slate-600">{acc.accountNumber}</td>
                <td className="px-6 py-4">
                  <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${
                    acc.status === 'ACTIVE' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                  }`}>
                    {acc.status}
                  </span>
                </td>
                <td className="px-6 py-4 text-right font-bold text-slate-900">
                  {acc.availableBalance.toFixed(2)} {acc.currency}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}