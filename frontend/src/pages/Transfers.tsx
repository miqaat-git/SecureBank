import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { AlertTriangle, CheckCircle2, Info, Loader2, Send } from 'lucide-react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'
import RecentTransfers from '../components/RecentTransfers'

type Account = { id: string; accountNumber: string; accountType: string; status: string; currency: string; availableBalance: number }
type TransferResult = { transferId: string; status: string; errorCode?: string | null; errorMessage?: string | null }
type Feedback = { kind: 'success' | 'error' | 'info'; message: string }
type ApiErrorShape = { message?: string; code?: string; response?: { status?: number; data?: { message?: string } | string } }

function apiErrorMessage(err: unknown, fallback: string): string {
  const e = err as ApiErrorShape
  const data = e?.response?.data
  const serverMessage = typeof data === 'object' && data !== null ? data.message : undefined
  if (e?.response?.status === undefined) return `No response from the server (${e?.code ?? e?.message ?? 'network error'}).`
  return serverMessage ?? fallback
}

function newIdempotencyKey(): string { return crypto.randomUUID() }

export default function Transfers() {
  const { user } = useAuth()
  const customerId = user?.customer_id
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loadingAccounts, setLoadingAccounts] = useState(true)
  const [sourceAccountId, setSourceAccountId] = useState('')
  const [destinationAccountNumber, setDestinationAccountNumber] = useState('')
  const [amount, setAmount] = useState('')
  const [pin, setPin] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{ destination?: string; amount?: string; pin?: string; source?: string }>({})
  const [historyRefreshKey, setHistoryRefreshKey] = useState(0)
  const idempotencyKey = useRef<string>(newIdempotencyKey())

  const loadAccounts = useCallback(async () => {
    if (!customerId) { setLoadingAccounts(false); return }
    try {
      const r = await api.get<{ content: Account[] }>(`/api/accounts?customerId=${customerId}`)
      const active = r.data.content.filter((a) => a.status === 'ACTIVE')
      setAccounts(active)
      setSourceAccountId((current) => current || active[0]?.id || '')
    } catch { setFeedback({ kind: 'error', message: 'Failed to load your accounts.' }) } 
    finally { setLoadingAccounts(false) }
  }, [customerId])

  useEffect(() => { void loadAccounts() }, [loadAccounts])
  const sourceAccount = accounts.find((a) => a.id === sourceAccountId)

  const validate = (): boolean => {
    const errors: typeof fieldErrors = {}
    if (!sourceAccount) errors.source = 'Choose an account to send from.'
    if (!/^\d{13}$/.test(destinationAccountNumber.trim())) errors.destination = 'Enter a valid 13-digit account number.'
    const parsed = Number(amount)
    if (!amount || !Number.isFinite(parsed) || parsed <= 0) errors.amount = 'Enter an amount greater than zero.'
    else if (!/^\d{1,15}(\.\d{1,2})?$/.test(amount)) errors.amount = 'Use at most 2 decimal places.'
    if (!/^\d{4}$/.test(pin)) errors.pin = 'Enter your 4-digit transaction PIN.'
    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (isSubmitting) return
    setFeedback(null)
    if (!validate() || !sourceAccount) return
    setIsSubmitting(true)
    try {
      let destinationAccountId: string
      try {
        const lookup = await api.get<{ id: string }>(`/api/account-numbers/${encodeURIComponent(destinationAccountNumber.trim())}`)
        destinationAccountId = lookup.data.id
      } catch (err) {
        setFieldErrors((prev) => ({ ...prev, destination: 'Account not found' }))
        setFeedback({ kind: 'error', message: 'Destination account not found.' })
        return
      }

      const response = await api.post<TransferResult>('/api/transactions', {
        sourceAccountId: sourceAccount.id,
        destinationAccountId,
        amount: Number(amount),
        currency: sourceAccount.currency,
      }, { headers: { 'Idempotency-Key': idempotencyKey.current } })

      idempotencyKey.current = newIdempotencyKey()
      const result = response.data

      if (result.status === 'COMPLETED') {
        setFeedback({ kind: 'success', message: 'Transfer completed successfully.' })
        setDestinationAccountNumber(''); setAmount(''); setPin(''); setFieldErrors({})
        void loadAccounts()
      } else if (result.status === 'FAILED') {
        setFeedback({ kind: 'error', message: result.errorMessage ?? 'The transfer failed.' })
      } else {
        setFeedback({ kind: 'info', message: `Your transfer is still being processed (${result.status}).` })
        setDestinationAccountNumber(''); setAmount(''); setPin(''); setFieldErrors({})
      }
      setHistoryRefreshKey((k) => k + 1)
    } catch (err) {
      const status = (err as ApiErrorShape)?.response?.status
      if (status !== undefined) idempotencyKey.current = newIdempotencyKey()
      setFeedback({ kind: 'error', message: apiErrorMessage(err, 'The transfer could not be completed.') })
    } finally { setIsSubmitting(false) }
  }

  const inputClass = (invalid: boolean) => `w-full rounded-lg border px-3 py-2.5 text-sm text-slate-900 shadow-sm focus:outline-none focus:ring-2 ${invalid ? 'border-red-300 focus:ring-red-200' : 'border-slate-300 focus:ring-brand-200 focus:border-brand-500'}`

  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-900 mb-6">Transfers</h1>
      <div className="bg-white border border-slate-200 rounded-xl p-6 md:p-8 shadow-sm">
        <div className="flex items-center gap-3 mb-6">
          <div className="bg-brand-50 text-brand-600 p-2.5 rounded-lg"><Send className="w-5 h-5" /></div>
          <div>
            <h2 className="text-lg font-bold text-slate-900">Send Money</h2>
            <p className="text-sm text-slate-500">Transfer funds securely to another SecureBank account.</p>
          </div>
        </div>

        {feedback && (
          <div role={feedback.kind === 'error' ? 'alert' : 'status'} className={`mb-5 flex items-start gap-3 rounded-xl border px-4 py-3 text-sm ${feedback.kind === 'success' ? 'bg-green-50 border-green-200 text-green-800' : feedback.kind === 'info' ? 'bg-amber-50 border-amber-200 text-amber-800' : 'bg-red-50 border-red-200 text-red-800'}`}>
            {feedback.kind === 'success' ? <CheckCircle2 className="w-5 h-5 shrink-0" /> : feedback.kind === 'info' ? <Info className="w-5 h-5 shrink-0" /> : <AlertTriangle className="w-5 h-5 shrink-0" />}
            <span>{feedback.message}</span>
          </div>
        )}

        <form onSubmit={(e) => void submit(e)} className="space-y-5 max-w-xl" noValidate>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">From account</label>
            <select value={sourceAccountId} onChange={(e) => setSourceAccountId(e.target.value)} disabled={loadingAccounts || accounts.length === 0 || isSubmitting} className={inputClass(!!fieldErrors.source)}>
              {loadingAccounts && <option value="">Loading accounts…</option>}
              {!loadingAccounts && accounts.length === 0 && <option value="">No active accounts</option>}
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.accountType} · {a.accountNumber} · {a.availableBalance.toFixed(2)} {a.currency}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">To Account Number</label>
            <input type="text" inputMode="numeric" maxLength={13} value={destinationAccountNumber} onChange={(e) => setDestinationAccountNumber(e.target.value.replace(/\D/g, ''))} placeholder="13-digit account number" className={`${inputClass(!!fieldErrors.destination)} font-mono`} />
            {fieldErrors.destination && <p className="mt-1.5 text-xs text-red-600">{fieldErrors.destination}</p>}
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Amount{sourceAccount ? ` (${sourceAccount.currency})` : ''}</label>
              <input type="text" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" className={inputClass(!!fieldErrors.amount)} />
              {fieldErrors.amount && <p className="mt-1.5 text-xs text-red-600">{fieldErrors.amount}</p>}
            </div>
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1.5">Transaction PIN</label>
              <input type="password" inputMode="numeric" maxLength={4} value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} placeholder="••••" className={`${inputClass(!!fieldErrors.pin)} tracking-widest`} />
              {fieldErrors.pin && <p className="mt-1.5 text-xs text-red-600">{fieldErrors.pin}</p>}
            </div>
          </div>
          <button type="submit" disabled={isSubmitting || loadingAccounts || accounts.length === 0} className="inline-flex items-center justify-center gap-2 w-full sm:w-auto bg-brand-600 text-white px-6 py-2.5 rounded-lg text-sm font-medium hover:bg-brand-700 shadow-sm transition disabled:opacity-60">
            {isSubmitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />} Send money
          </button>
        </form>
      </div>
      <RecentTransfers refreshKey={historyRefreshKey} />
    </div>
  )
}