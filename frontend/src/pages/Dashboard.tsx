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
}

export default function Dashboard() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!user?.customer_id) {
      setLoading(false)
      return
    }
    
    // Fetch accounts using the customer_id from the JWT
    api.get<{ content: Account[] }>(`/api/accounts?customerId=${user.customer_id}`)
      .then((r) => setAccounts(r.data.content))
      .catch((e) => setError(e.response?.data?.message ?? e.message))
      .finally(() => setLoading(false))
  }, [user?.customer_id])

  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-900 mb-6">Dashboard</h1>
      <div className="grid gap-6 md:grid-cols-2">
        
        {/* IDENTITY CARD */}
        <div className="bg-white rounded-xl shadow p-6">
          <h2 className="text-sm font-medium text-slate-500 uppercase tracking-wide mb-3">
            Your Identity (from JWT)
          </h2>
          <dl className="space-y-2 text-sm">
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Email</dt>
              <dd className="font-medium text-slate-900 truncate">{user?.email ?? '—'}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Username</dt>
              <dd className="font-medium text-slate-900">{user?.preferred_username ?? '—'}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Roles</dt>
              <dd className="font-medium text-slate-900">{user?.roles.join(', ') || '—'}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Customer ID</dt>
              <dd className="font-mono text-xs text-slate-700 truncate">{user?.customer_id ?? '—'}</dd>
            </div>
          </dl>
        </div>

        {/* ACCOUNTS CARD */}
        <div className="bg-white rounded-xl shadow p-6">
          <h2 className="text-sm font-medium text-slate-500 uppercase tracking-wide mb-3">
            Your Accounts (from API)
          </h2>
          {loading && <div className="text-slate-400 text-sm">Loading…</div>}
          {error && <div className="text-red-600 text-sm">{error}</div>}
          
          {!loading && !error && accounts.length === 0 && (
            <p className="text-sm text-slate-500">No accounts found.</p>
          )}

          {accounts.length > 0 && (
            <ul className="space-y-3">
              {accounts.map(acc => (
                <li key={acc.id} className="border border-slate-200 rounded-lg p-3">
                  <div className="flex justify-between items-center">
                    <div>
                      <p className="font-bold text-slate-900">{acc.accountType}</p>
                      <p className="text-xs text-slate-500 font-mono">{acc.accountNumber}</p>
                    </div>
                    <div className="text-right">
                      <p className="font-bold text-slate-900">
                        {acc.availableBalance.toFixed(2)} {acc.currency}
                      </p>
                      <span className={`text-[10px] uppercase px-2 py-0.5 rounded-full font-bold ${
                        acc.status === 'ACTIVE' ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
                      }`}>
                        {acc.status}
                      </span>
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

      </div>

      <div className="mt-6 bg-blue-50 border border-blue-200 rounded-xl p-6">
        <h3 className="font-medium text-blue-900 mb-2">Phase 9B milestone</h3>
        <p className="text-sm text-blue-800">
          If you can see this dashboard with your real bank accounts fetched from the API, 
          authentication + bearer-token API calls are working end-to-end. 
          The accounts shown here are the ones we created and funded via PowerShell in Phase 7!
        </p>
      </div>
    </div>
  )
}