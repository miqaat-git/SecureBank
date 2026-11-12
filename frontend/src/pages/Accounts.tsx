import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { AlertTriangle, CheckCircle2, Landmark, Loader2, PiggyBank, Plus, X } from 'lucide-react'
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

type AccountType = 'SAVINGS' | 'CURRENT'

const ACCOUNT_OPTIONS: { value: AccountType; label: string; description: string; icon: typeof PiggyBank }[] = [
  {
    value: 'SAVINGS',
    label: 'Savings',
    description: 'Grow your money and set funds aside for the future.',
    icon: PiggyBank,
  },
  {
    value: 'CURRENT',
    label: 'Current',
    description: 'Everyday banking for frequent payments and transfers.',
    icon: Landmark,
  },
]

const FOCUSABLE =
  'button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [href], select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function statusBadgeClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'bg-green-100 text-green-800'
    case 'PENDING':
      return 'bg-amber-100 text-amber-800'
    default:
      return 'bg-red-100 text-red-800'
  }
}

function extractError(err: unknown): string {
  const e = err as { response?: { status?: number; data?: { message?: string } } }
  const status = e?.response?.status
  if (status === 409) return 'You already have a pending application for this account type.'
  if (status === 400) {
    return e?.response?.data?.message ?? 'Your application could not be submitted. Please check your details and try again.'
  }
  return e?.response?.data?.message ?? 'Something went wrong while submitting your application. Please try again.'
}

type OpenAccountModalProps = {
  accountType: AccountType
  isSubmitting: boolean
  error: string | null
  onSelect: (type: AccountType) => void
  onClose: () => void
  onSubmit: () => void
}

function OpenAccountModal({ accountType, isSubmitting, error, onSelect, onClose, onSubmit }: OpenAccountModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const first = dialogRef.current?.querySelector<HTMLElement>('input[type="radio"]:checked')
    first?.focus()

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isSubmitting) {
        e.preventDefault()
        onClose()
        return
      }
      if (e.key !== 'Tab' || !dialogRef.current) return
      const nodes = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (nodes.length === 0) return
      const firstNode = nodes[0]
      const lastNode = nodes[nodes.length - 1]
      const active = document.activeElement
      const outside = !dialogRef.current.contains(active)
      if (e.shiftKey && (active === firstNode || outside)) {
        e.preventDefault()
        lastNode.focus()
      } else if (!e.shiftKey && (active === lastNode || outside)) {
        e.preventDefault()
        firstNode.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = prevOverflow
    }
  }, [onClose, isSubmitting])

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    if (!isSubmitting) onSubmit()
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !isSubmitting) onClose()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="open-account-title"
        className="w-full sm:max-w-lg max-h-[95vh] overflow-y-auto bg-white rounded-t-2xl sm:rounded-2xl shadow-2xl border border-slate-200 animate-in slide-in-from-bottom-4 sm:zoom-in-95"
      >
        <div className="flex items-start justify-between p-5 sm:p-6 border-b border-slate-100">
          <div className="flex items-start gap-3">
            <div className="bg-brand-50 text-brand-600 p-2 rounded-lg">
              <Plus className="w-5 h-5" />
            </div>
            <div>
              <h2 id="open-account-title" className="text-lg font-bold text-slate-900">
                Open New Account
              </h2>
              <p className="text-sm text-slate-500 mt-0.5">
                Choose an account type. Applications are reviewed before activation.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isSubmitting}
            aria-label="Close dialog"
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition disabled:opacity-50"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-5 sm:p-6 space-y-4">
          <fieldset>
            <legend className="sr-only">Account type</legend>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
              {ACCOUNT_OPTIONS.map((opt) => {
                const selected = accountType === opt.value
                return (
                  <label
                    key={opt.value}
                    className={`relative flex flex-col gap-2 rounded-xl border-2 p-4 cursor-pointer transition focus-within:ring-2 focus-within:ring-brand-200 ${
                      selected
                        ? 'border-brand-600 bg-brand-50'
                        : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50'
                    }`}
                  >
                    <input
                      type="radio"
                      name="accountType"
                      value={opt.value}
                      checked={selected}
                      onChange={() => onSelect(opt.value)}
                      disabled={isSubmitting}
                      className="sr-only"
                    />
                    <div className="flex items-center justify-between">
                      <span
                        className={`p-2 rounded-lg ${selected ? 'bg-brand-600 text-white' : 'bg-slate-100 text-slate-600'}`}
                      >
                        <opt.icon className="w-5 h-5" />
                      </span>
                      {selected && <CheckCircle2 className="w-5 h-5 text-brand-600" />}
                    </div>
                    <span className="font-bold text-slate-900">{opt.label}</span>
                    <span className="text-xs text-slate-500 leading-relaxed">{opt.description}</span>
                  </label>
                )
              })}
            </div>
          </fieldset>

          {error && (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800"
            >
              <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <div className="flex flex-col-reverse sm:flex-row sm:justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="px-4 py-2.5 rounded-lg text-sm font-medium text-slate-700 bg-white border border-slate-300 hover:bg-slate-50 transition disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="inline-flex items-center justify-center gap-2 px-4 py-2.5 rounded-lg text-sm font-medium text-white bg-brand-600 hover:bg-brand-700 shadow-sm transition disabled:opacity-60"
            >
              {isSubmitting && <Loader2 className="w-4 h-4 animate-spin" />}
              Submit application
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function Accounts() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [isModalOpen, setIsModalOpen] = useState(false)
  const [accountType, setAccountType] = useState<AccountType>('SAVINGS')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const triggerRef = useRef<HTMLButtonElement>(null)
  const successTimer = useRef<number | undefined>(undefined)

  const customerId = user?.customer_id

  const load = useCallback(async () => {
    if (!customerId) {
      setLoading(false)
      return
    }
    setLoadError(null)
    try {
      const r = await api.get<{ content: Account[] }>(`/api/accounts?customerId=${customerId}`)
      setAccounts(r.data.content)
    } catch {
      setLoadError('Failed to load accounts. Please try again.')
    } finally {
      setLoading(false)
    }
  }, [customerId])

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => () => window.clearTimeout(successTimer.current), [])

  const openModal = () => {
    setAccountType('SAVINGS')
    setError(null)
    setIsModalOpen(true)
  }

  const closeModal = useCallback(() => {
    setIsModalOpen(false)
    setError(null)
    window.setTimeout(() => triggerRef.current?.focus(), 0)
  }, [])

  const selectType = useCallback((type: AccountType) => {
    setAccountType(type)
    setError(null)
  }, [])

  const submit = async () => {
    if (!customerId) {
      setError('Your profile is not linked to a customer record yet, so an account cannot be opened.')
      return
    }
    setIsSubmitting(true)
    setError(null)
    try {
      await api.post('/api/accounts', { customerId, accountType })
      setIsModalOpen(false)
      window.setTimeout(() => triggerRef.current?.focus(), 0)
      setSuccess(`Your ${accountType} account application was submitted and is pending approval.`)
      window.clearTimeout(successTimer.current)
      successTimer.current = window.setTimeout(() => setSuccess(null), 6000)
      await load()
    } catch (err) {
      setError(extractError(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="animate-in fade-in duration-500">
      <div className="flex justify-between items-center mb-6 gap-3">
        <h1 className="text-2xl font-bold text-slate-900">My Accounts</h1>
        <button
          ref={triggerRef}
          onClick={openModal}
          className="inline-flex items-center gap-2 bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700 transition shadow-sm"
        >
          <Plus className="w-4 h-4" />
          Open New Account
        </button>
      </div>

      {success && (
        <div role="status" className="mb-4 flex items-start gap-3 rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-800 shadow-sm animate-in slide-in-from-top-2">
          <CheckCircle2 className="w-5 h-5 shrink-0" />
          <span className="flex-1">{success}</span>
          <button onClick={() => setSuccess(null)} aria-label="Dismiss notification" className="opacity-60 hover:opacity-100">
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {loadError && (
        <div role="alert" className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          {loadError}
        </div>
      )}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse min-w-[560px]">
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
                    <span className={`text-xs px-2.5 py-1 rounded-full font-bold ${statusBadgeClass(acc.status)}`}>
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

      {isModalOpen && (
        <OpenAccountModal
          accountType={accountType}
          isSubmitting={isSubmitting}
          error={error}
          onSelect={selectType}
          onClose={closeModal}
          onSubmit={() => void submit()}
        />
      )}
    </div>
  )
}