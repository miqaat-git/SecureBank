import { useEffect, useState } from 'react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'
import { CheckCircle2, AlertCircle, ArrowLeftRight } from 'lucide-react'
import RecentTransfers from '../components/RecentTransfers'

type Account = {
  id: string
  accountNumber: string
  accountType: string
  status: string
  currency: string
  availableBalance: number
}

export default function Transfers() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[]>([])
  const [sourceId, setSourceId] = useState('')
  const [destinationId, setDestinationId] = useState('')
  const [amount, setAmount] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)
  const [historyRefreshKey, setHistoryRefreshKey] = useState(0)

  useEffect(() => {
    if (!user?.customer_id) return
    api.get<{ content: Account[] }>(`/api/accounts?customerId=${user.customer_id}`)
      .then((r) => {
        const active = r.data.content.filter(a => a.status === 'ACTIVE')
        setAccounts(active)
        if (active.length > 0) setSourceId(active[0].id)
      })
  }, [user?.customer_id])

  const selectedAccount = accounts.find(a => a.id === sourceId)

  const handleTransfer = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    setError(null)
    setSuccess(false)
    try {
      const idempotencyKey = crypto.randomUUID()
      await api.post('/api/transactions', {
        sourceAccountId: sourceId,
        destinationAccountId: destinationId,
        amount: parseFloat(amount),
        currency: 'INR'
      }, { headers: { 'Idempotency-Key': idempotencyKey } })

      setSuccess(true)
      setAmount('')
      setDestinationId('')

      const r = await api.get<{ content: Account[] }>(`/api/accounts?customerId=${user?.customer_id}`)
      setAccounts(r.data.content.filter(a => a.status === 'ACTIVE'))
      setHistoryRefreshKey((k) => k + 1)
    } catch (err: any) {
      const msg = err.response?.data?.message || err.response?.data?.errorMessage || 'Transfer failed'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-8 animate-in fade-in duration-500 max-w-2xl mx-auto">
      <div className="text-center mb-8">
        <h1 className="text-3xl font-bold text-slate-900 tracking-tight">Send Money</h1>
        <p className="text-slate-500 mt-2">Transfer funds instantly and securely.</p>
      </div>

      <div className="bg-white border border-slate-100 rounded-3xl p-8 shadow-glass">
        {success && (
          <div className="mb-8 bg-green-50 border border-green-200 text-green-800 p-5 rounded-2xl flex items-center gap-4 animate-in slide-in-from-top-2">
            <div className="bg-green-100 p-2 rounded-full"><CheckCircle2 className="w-6 h-6 text-green-600" /></div>
            <div>
              <p className="font-bold text-lg">Transfer successful</p>
              <p className="text-sm opacity-90">The funds have been moved securely.</p>
            </div>
          </div>
        )}

        {error && (
          <div className="mb-8 bg-red-50 border border-red-200 text-red-800 p-5 rounded-2xl flex items-start gap-4 animate-in slide-in-from-top-2">
            <div className="bg-red-100 p-2 rounded-full mt-0.5"><AlertCircle className="w-5 h-5 text-red-600" /></div>
            <div>
              <p className="font-bold text-lg">Transfer failed</p>
              <p className="text-sm opacity-90">{error}</p>
            </div>
          </div>
        )}

        <form onSubmit={handleTransfer} className="space-y-6">
          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">From Account</label>
            <select
              value={sourceId}
              onChange={e => setSourceId(e.target.value)}
              className="w-full border-2 border-slate-200 rounded-2xl p-4 bg-slate-50 text-slate-900 font-medium focus:border-brand-500 focus:bg-white focus:outline-none transition-colors appearance-none"
              required
            >
              {accounts.length === 0 && <option value="">No active accounts</option>}
              {accounts.map(a => (
                <option key={a.id} value={a.id}>
                  {a.accountType} (•••• {a.accountNumber.slice(-4)}) — {a.availableBalance.toFixed(2)} {a.currency}
                </option>
              ))}
            </select>
          </div>

          <div className="flex justify-center -my-3 relative z-10">
            <div className="bg-white p-3 rounded-full border-2 border-slate-100 shadow-sm text-slate-400">
              <ArrowLeftRight className="w-5 h-5" />
            </div>
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">To Account ID</label>
            <input
              type="text"
              value={destinationId}
              onChange={e => setDestinationId(e.target.value)}
              placeholder="Paste the destination UUID here"
              className="w-full border-2 border-slate-200 rounded-2xl p-4 bg-slate-50 font-mono text-sm focus:border-brand-500 focus:bg-white focus:outline-none transition-colors"
              required
            />
          </div>

          <div>
            <label className="block text-sm font-semibold text-slate-700 mb-2">Amount</label>
            <div className="relative">
              <span className="absolute left-5 top-4 text-slate-400 text-xl font-bold">₹</span>
              <input
                type="number"
                step="0.01"
                min="1"
                value={amount}
                onChange={e => setAmount(e.target.value)}
                placeholder="0.00"
                className="w-full border-2 border-slate-200 rounded-2xl p-4 pl-12 text-2xl font-bold text-slate-900 bg-slate-50 focus:border-brand-500 focus:bg-white focus:outline-none transition-colors"
                required
              />
            </div>
            {selectedAccount && (
              <p className="text-sm text-slate-500 mt-2 ml-1">
                Available: <span className="font-bold text-slate-700">{selectedAccount.availableBalance.toFixed(2)} {selectedAccount.currency}</span>
              </p>
            )}
          </div>

          <button
            type="submit"
            disabled={loading || accounts.length === 0}
            className="w-full bg-brand-600 hover:bg-brand-700 text-white font-bold text-lg py-4 rounded-2xl transition-all shadow-lg shadow-brand-500/30 disabled:opacity-50 disabled:cursor-not-allowed mt-4"
          >
            {loading ? 'Processing...' : 'Confirm Transfer'}
          </button>
        </form>
      </div>

      <RecentTransfers refreshKey={historyRefreshKey} />
    </div>
  )
}