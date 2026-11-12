import { useCallback, useEffect, useState } from 'react'
import { ArrowDownLeft, ArrowUpRight, ArrowRightLeft, RefreshCw } from 'lucide-react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'

type TransferResponse = {
  transferId: string
  amount: number | string
  currency: string
  status: string
  createdAt: string
  sourceAccountId: string
  destinationAccountId: string
}

type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

type OwnAccount = { id: string }

function formatDate(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

function statusBadgeClass(status: string): string {
  switch (status) {
    case 'COMPLETED': return 'bg-green-100 text-green-800'
    case 'FAILED': return 'bg-red-100 text-red-800'
    default: return 'bg-amber-100 text-amber-800'
  }
}

function shortId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id
}

export default function RecentTransfers({ refreshKey = 0 }: { refreshKey?: number }) {
  const { user } = useAuth()
  const customerId = user?.customer_id

  const [transfers, setTransfers] = useState<TransferResponse[]>([])
  const [loadingHistory, setLoadingHistory] = useState(true)
  const [historyError, setHistoryError] = useState<string | null>(null)
  const [ownAccountIds, setOwnAccountIds] = useState<Set<string>>(new Set())

  const loadHistory = useCallback(async () => {
    setLoadingHistory(true)
    setHistoryError(null)
    try {
      const r = await api.get<PageResponse<TransferResponse>>('/api/transactions', {
        params: { page: 0, size: 10 },
      })
      setTransfers(r.data.content ?? [])
    } catch {
      setHistoryError('Failed to load recent transfers. Please try again.')
    } finally {
      setLoadingHistory(false)
    }
  }, [])

  useEffect(() => {
    if (!customerId) return
    let cancelled = false
    api.get<{ content: OwnAccount[] }>(`/api/accounts?customerId=${customerId}`)
      .then((r) => { if (!cancelled) setOwnAccountIds(new Set(r.data.content.map((a) => a.id))) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [customerId])

  useEffect(() => {
    void loadHistory()
  }, [loadHistory, refreshKey])

  const direction = (t: TransferResponse): 'Sent' | 'Received' | 'Transfer' => {
    const fromMe = ownAccountIds.has(t.sourceAccountId)
    const toMe = ownAccountIds.has(t.destinationAccountId)
    if (fromMe && !toMe) return 'Sent'
    if (toMe && !fromMe) return 'Received'
    return 'Transfer'
  }

  return (
    <section className="mt-8" aria-labelledby="recent-transfers-title">
      <div className="flex items-center justify-between mb-4">
        <h2 id="recent-transfers-title" className="text-lg font-bold text-slate-900">Recent Transfers</h2>
        <button
          type="button"
          onClick={() => void loadHistory()}
          disabled={loadingHistory}
          className="inline-flex items-center gap-2 bg-white border border-slate-300 text-slate-700 px-3 py-1.5 rounded-lg text-sm font-medium hover:bg-slate-50 transition disabled:opacity-60"
        >
          <RefreshCw className={`w-4 h-4 ${loadingHistory ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {historyError && (
        <div role="alert" className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          {historyError}
        </div>
      )}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse min-w-[560px]">
            <thead>
              <tr className="bg-slate-50 text-slate-500 text-xs uppercase tracking-wider border-b border-slate-200">
                <th className="px-6 py-4 font-medium">Date</th>
                <th className="px-6 py-4 font-medium">Type</th>
                <th className="px-6 py-4 font-medium text-right">Amount</th>
                <th className="px-6 py-4 font-medium">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loadingHistory && transfers.length === 0 && (
                <tr><td colSpan={4} className="px-6 py-8 text-center text-slate-500">Loading transfers...</td></tr>
              )}
              {!loadingHistory && !historyError && transfers.length === 0 && (
                <tr><td colSpan={4} className="px-6 py-8 text-center text-slate-500">No transfers yet. Your recent transfers will appear here.</td></tr>
              )}
              {transfers.map((t) => {
                const dir = direction(t)
                const Icon = dir === 'Sent' ? ArrowUpRight : dir === 'Received' ? ArrowDownLeft : ArrowRightLeft
                const iconClass = dir === 'Sent' ? 'bg-slate-100 text-slate-600' : dir === 'Received' ? 'bg-green-100 text-green-700' : 'bg-brand-50 text-brand-600'
                const amount = Number(t.amount)
                return (
                  <tr key={t.transferId} className="hover:bg-slate-50 transition">
                    <td className="px-6 py-4 text-sm text-slate-600 whitespace-nowrap">{formatDate(t.createdAt)}</td>
                    <td className="px-6 py-4">
                      <div className="flex items-center gap-3">
                        <span className={`p-1.5 rounded-lg ${iconClass}`}><Icon className="w-4 h-4" /></span>
                        <div>
                          <p className="font-medium text-slate-900">{dir}</p>
                          <p className="font-mono text-xs text-slate-400">{shortId(t.sourceAccountId)} → {shortId(t.destinationAccountId)}</p>
                        </div>
                      </div>
                    </td>
                    <td className="px-6 py-4 text-right font-bold text-slate-900 whitespace-nowrap">
                      {Number.isNaN(amount) ? t.amount : amount.toFixed(2)} {t.currency}
                    </td>
                    <td className="px-6 py-4">
                      <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${statusBadgeClass(t.status)}`}>{t.status}</span>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}