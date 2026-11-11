import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { CheckCircle2, XCircle, X, AlertTriangle, RefreshCw, ShieldCheck, Loader2 } from 'lucide-react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'

type PendingAccount = {
  id: string
  customerId: string
  accountNumber: string
  accountType: string
  status: string
  currency: string
  availableBalance: number
  createdAt: string
}

type PageResponse<T> = {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

type Toast = { kind: 'success' | 'error'; message: string }

function errorMessage(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message ?? e?.message ?? fallback
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

const FOCUSABLE =
  'button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [href], select:not([disabled]), [tabindex]:not([tabindex="-1"])'

type RejectModalProps = {
  account: PendingAccount
  submitting: boolean
  onClose: () => void
  onConfirm: (reason: string) => void
}

function RejectModal({ account, submitting, onClose, onConfirm }: RejectModalProps) {
  const [reason, setReason] = useState('')
  const [touched, setTouched] = useState(false)
  const dialogRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const trimmed = reason.trim()
  const invalid = trimmed.length === 0

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    textareaRef.current?.focus()

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) {
        e.preventDefault()
        onClose()
        return
      }
      if (e.key !== 'Tab' || !dialogRef.current) return
      const nodes = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (nodes.length === 0) return
      const first = nodes[0]
      const last = nodes[nodes.length - 1]
      const active = document.activeElement
      if (e.shiftKey && (active === first || !dialogRef.current.contains(active))) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && (active === last || !dialogRef.current.contains(active))) {
        e.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.body.style.overflow = prevOverflow
      previouslyFocused?.focus?.()
    }
  }, [onClose, submitting])

  const submit = (e: FormEvent) => {
    e.preventDefault()
    setTouched(true)
    if (invalid || submitting) return
    onConfirm(trimmed)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm animate-in fade-in"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !submitting) onClose()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="reject-title"
        className="w-full max-w-md bg-white rounded-2xl shadow-2xl border border-slate-200 animate-in zoom-in-95"
      >
        <div className="flex items-start justify-between p-6 border-b border-slate-100">
          <div className="flex items-start gap-3">
            <div className="bg-red-100 text-red-600 p-2 rounded-lg">
              <AlertTriangle className="w-5 h-5" />
            </div>
            <div>
              <h2 id="reject-title" className="text-lg font-bold text-slate-900">
                Reject application
              </h2>
              <p className="text-sm text-slate-500 mt-0.5">
                {account.accountType} account <span className="font-mono">{account.accountNumber}</span>
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            aria-label="Close dialog"
            className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition disabled:opacity-50"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={submit} className="p-6 space-y-4" noValidate>
          <div>
            <label htmlFor="reject-reason" className="block text-sm font-medium text-slate-700 mb-1.5">
              Reason for rejection <span className="text-red-600">*</span>
            </label>
            <textarea
              id="reject-reason"
              ref={textareaRef}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              onBlur={() => setTouched(true)}
              rows={4}
              maxLength={500}
              aria-invalid={touched && invalid}
              placeholder="Explain why this application is being rejected…"
              className={`w-full rounded-lg border px-3 py-2 text-sm text-slate-900 shadow-sm focus:outline-none focus:ring-2 ${
                touched && invalid
                  ? 'border-red-300 focus:ring-red-200'
                  : 'border-slate-300 focus:ring-brand-200 focus:border-brand-500'
              }`}
            />
            {touched && invalid && (
              <p role="alert" className="mt-1.5 text-xs text-red-600">
                A reason is required to reject an application.
              </p>
            )}
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="px-4 py-2 rounded-lg text-sm font-medium text-slate-700 bg-white border border-slate-300 hover:bg-slate-50 transition disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white bg-red-600 hover:bg-red-700 transition disabled:opacity-60"
            >
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />}
              Reject application
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function AdminDashboard() {
  const { user } = useAuth()
  const isAdmin = user?.roles.includes('admin') ?? false

  const [accounts, setAccounts] = useState<PendingAccount[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [toast, setToast] = useState<Toast | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [rejectTarget, setRejectTarget] = useState<PendingAccount | null>(null)
  const toastTimer = useRef<number | undefined>(undefined)

  const showToast = useCallback((t: Toast) => {
    setToast(t)
    window.clearTimeout(toastTimer.current)
    toastTimer.current = window.setTimeout(() => setToast(null), 6000)
  }, [])

  useEffect(() => () => window.clearTimeout(toastTimer.current), [])

  const load = useCallback(async () => {
    setLoading(true)
    setLoadError(null)
    try {
      const r = await api.get<PageResponse<PendingAccount>>('/api/admin/accounts', {
        params: { status: 'PENDING' },
      })
      setAccounts(r.data.content ?? [])
    } catch (err) {
      setLoadError(errorMessage(err, 'Failed to load pending applications.'))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (isAdmin) void load()
  }, [isAdmin, load])

  const approve = async (acc: PendingAccount) => {
    setBusyId(acc.id)
    try {
      await api.post(`/api/admin/accounts/${acc.id}/approve`, { reason: 'Approved by Admin' })
      setAccounts((list) => list.filter((a) => a.id !== acc.id))
      showToast({ kind: 'success', message: `Account ${acc.accountNumber} approved and is now ACTIVE.` })
    } catch (err) {
      showToast({ kind: 'error', message: errorMessage(err, 'Failed to approve account.') })
    } finally {
      setBusyId(null)
    }
  }

  const reject = async (reason: string) => {
    if (!rejectTarget) return
    const acc = rejectTarget
    setBusyId(acc.id)
    try {
      await api.post(`/api/admin/accounts/${acc.id}/reject`, { reason })
      setAccounts((list) => list.filter((a) => a.id !== acc.id))
      setRejectTarget(null)
      showToast({ kind: 'success', message: `Account ${acc.accountNumber} rejected.` })
    } catch (err) {
      setRejectTarget(null)
      showToast({ kind: 'error', message: errorMessage(err, 'Failed to reject account.') })
    } finally {
      setBusyId(null)
    }
  }

  const closeModal = useCallback(() => setRejectTarget(null), [])

  if (user && !isAdmin) return <Navigate to="/dashboard" replace />

  return (
    <div className="animate-in fade-in duration-500">
      <div className="flex flex-wrap justify-between items-center gap-3 mb-6">
        <div className="flex items-center gap-3">
          <div className="bg-purple-100 text-purple-700 p-2 rounded-lg">
            <ShieldCheck className="w-5 h-5" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-slate-900">Admin Portal</h1>
            <p className="text-sm text-slate-500">Pending account applications</p>
          </div>
        </div>
        <button
          onClick={() => void load()}
          disabled={loading}
          className="inline-flex items-center gap-2 bg-white border border-slate-300 text-slate-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-slate-50 transition disabled:opacity-60 shadow-sm"
        >
          <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
          Refresh
        </button>
      </div>

      {toast && (
        <div
          role={toast.kind === 'error' ? 'alert' : 'status'}
          className={`mb-4 flex items-start gap-3 rounded-xl border px-4 py-3 text-sm shadow-sm animate-in slide-in-from-top-2 ${
            toast.kind === 'success'
              ? 'bg-green-50 border-green-200 text-green-800'
              : 'bg-red-50 border-red-200 text-red-800'
          }`}
        >
          {toast.kind === 'success' ? <CheckCircle2 className="w-5 h-5 shrink-0" /> : <AlertTriangle className="w-5 h-5 shrink-0" />}
          <span className="flex-1">{toast.message}</span>
          <button onClick={() => setToast(null)} aria-label="Dismiss notification" className="opacity-60 hover:opacity-100">
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
          <table className="w-full text-left border-collapse min-w-[720px]">
            <thead>
              <tr className="bg-slate-50 text-slate-500 text-xs uppercase tracking-wider border-b border-slate-200">
                <th className="px-6 py-4 font-medium">Account Type</th>
                <th className="px-6 py-4 font-medium">Account Number</th>
                <th className="px-6 py-4 font-medium">Customer ID</th>
                <th className="px-6 py-4 font-medium">Applied</th>
                <th className="px-6 py-4 font-medium">Status</th>
                <th className="px-6 py-4 font-medium text-right">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading && <tr><td colSpan={6} className="px-6 py-8 text-center text-slate-500">Loading applications...</td></tr>}
              {!loading && accounts.length === 0 && !loadError && <tr><td colSpan={6} className="px-6 py-8 text-center text-slate-500">No pending applications.</td></tr>}
              {!loading && accounts.map((acc) => {
                  const busy = busyId === acc.id
                  return (
                    <tr key={acc.id} className="hover:bg-slate-50 transition">
                      <td className="px-6 py-4 font-medium text-slate-900">{acc.accountType}</td>
                      <td className="px-6 py-4 font-mono text-sm text-slate-600">{acc.accountNumber}</td>
                      <td className="px-6 py-4 font-mono text-xs text-slate-500" title={acc.customerId}>{acc.customerId.slice(0, 8)}…</td>
                      <td className="px-6 py-4 text-sm text-slate-600 whitespace-nowrap">{formatDate(acc.createdAt)}</td>
                      <td className="px-6 py-4">
                        <span className="text-xs px-2.5 py-1 rounded-full font-bold bg-amber-100 text-amber-800">{acc.status}</span>
                      </td>
                      <td className="px-6 py-4">
                        <div className="flex justify-end gap-2">
                          <button
                            onClick={() => void approve(acc)}
                            disabled={busy}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-white bg-green-600 hover:bg-green-700 shadow-sm transition disabled:opacity-60"
                          >
                            {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Approve
                          </button>
                          <button
                            onClick={() => setRejectTarget(acc)}
                            disabled={busy}
                            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-red-700 bg-red-50 border border-red-200 hover:bg-red-100 transition disabled:opacity-60"
                          >
                            <XCircle className="w-4 h-4" /> Reject
                          </button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
            </tbody>
          </table>
        </div>
      </div>

      {rejectTarget && (
        <RejectModal
          account={rejectTarget}
          submitting={busyId === rejectTarget.id}
          onClose={closeModal}
          onConfirm={(r) => void reject(r)}
        />
      )}
    </div>
  )
}