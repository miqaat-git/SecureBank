import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { CheckCircle2, XCircle, X, AlertTriangle, RefreshCw, ShieldCheck, Loader2, Wallet } from 'lucide-react'
import { useAuth } from '../lib/auth'
import api from '../lib/api'

type PendingAccount = { id: string; customerId: string; accountNumber: string; accountType: string; status: string; currency: string; availableBalance: number; createdAt: string }
type PageResponse<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
type Toast = { kind: 'success' | 'error'; message: string }
const FOCUSABLE = 'button:not([disabled]), textarea:not([disabled]), input:not([disabled]), [href], select:not([disabled]), [tabindex]:not([tabindex="-1"])'

function errorMessage(err: unknown, fallback: string): string {
  const e = err as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message ?? e?.message ?? fallback
}
function formatDate(iso: string): string {
  const d = new Date(iso); return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
}

type RejectModalProps = { account: PendingAccount; submitting: boolean; onClose: () => void; onConfirm: (reason: string) => void }
function RejectModal({ account, submitting, onClose, onConfirm }: RejectModalProps) {
  const [reason, setReason] = useState(''); const [touched, setTouched] = useState(false)
  const dialogRef = useRef<HTMLDivElement>(null); const textareaRef = useRef<HTMLTextAreaElement>(null)
  const trimmed = reason.trim(); const invalid = trimmed.length === 0
  useEffect(() => {
    textareaRef.current?.focus()
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) { e.preventDefault(); onClose(); return }
      if (e.key !== 'Tab' || !dialogRef.current) return
      const nodes = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (nodes.length === 0) return
      const first = nodes[0]; const last = nodes[nodes.length - 1]; const active = document.activeElement
      if (e.shiftKey && (active === first || !dialogRef.current.contains(active))) { e.preventDefault(); last.focus() } 
      else if (!e.shiftKey && (active === last || !dialogRef.current.contains(active))) { e.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKeyDown); document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKeyDown); document.body.style.overflow = '' }
  }, [onClose, submitting])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/50 backdrop-blur-sm" onMouseDown={(e) => { if (e.target === e.currentTarget && !submitting) onClose() }}>
      <div ref={dialogRef} role="dialog" className="w-full max-w-md bg-white rounded-2xl shadow-2xl border border-slate-200">
        <div className="flex items-start justify-between p-6 border-b border-slate-100">
          <div className="flex items-start gap-3"><div className="bg-red-100 text-red-600 p-2 rounded-lg"><AlertTriangle className="w-5 h-5" /></div><div><h2 className="text-lg font-bold text-slate-900">Reject application</h2><p className="text-sm text-slate-500 mt-0.5">{account.accountType} <span className="font-mono">{account.accountNumber}</span></p></div></div>
          <button type="button" onClick={onClose} disabled={submitting} className="p-1.5 text-slate-400 hover:text-slate-600"><X className="w-5 h-5" /></button>
        </div>
        <form onSubmit={(e) => { e.preventDefault(); setTouched(true); if (!invalid && !submitting) onConfirm(trimmed) }} className="p-6 space-y-4">
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">Reason <span className="text-red-600">*</span></label>
            <textarea ref={textareaRef} value={reason} onChange={(e) => setReason(e.target.value)} onBlur={() => setTouched(true)} rows={4} maxLength={500} placeholder="Explain why..." className={`w-full rounded-lg border px-3 py-2 text-sm focus:ring-2 ${touched && invalid ? 'border-red-300 focus:ring-red-200' : 'border-slate-300 focus:ring-brand-200'}`} />
            {touched && invalid && <p className="mt-1.5 text-xs text-red-600">A reason is required.</p>}
          </div>
          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onClose} disabled={submitting} className="px-4 py-2 rounded-lg text-sm font-medium bg-white border border-slate-300 hover:bg-slate-50">Cancel</button>
            <button type="submit" disabled={submitting} className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white bg-red-600 hover:bg-red-700">{submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : <XCircle className="w-4 h-4" />} Reject</button>
          </div>
        </form>
      </div>
    </div>
  )
}

type FundingResult = { status: string; errorMessage?: string | null }
type FundModalProps = { onClose: () => void; onResult: (t: Toast) => void }
function newFundingReference(): string { return `FUND-${crypto.randomUUID()}` }

function FundModal({ onClose, onResult }: FundModalProps) {
  const [targetAccountId, setTargetAccountId] = useState(''); const [amount, setAmount] = useState(''); const [currency, setCurrency] = useState('INR')
  const [fundingReference, setFundingReference] = useState(newFundingReference); const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false); const [error, setError] = useState<string | null>(null)
  const dialogRef = useRef<HTMLDivElement>(null); const firstFieldRef = useRef<HTMLInputElement>(null)
  const idempotencyKey = useRef<string>(crypto.randomUUID())

  useEffect(() => {
    firstFieldRef.current?.focus()
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) { e.preventDefault(); onClose(); return }
      if (e.key !== 'Tab' || !dialogRef.current) return
      const nodes = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
      if (nodes.length === 0) return
      const first = nodes[0]; const last = nodes[nodes.length - 1]; const active = document.activeElement
      if (e.shiftKey && (active === first || !dialogRef.current.contains(active))) { e.preventDefault(); last.focus() } 
      else if (!e.shiftKey && (active === last || !dialogRef.current.contains(active))) { e.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKeyDown); document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKeyDown); document.body.style.overflow = '' }
  }, [onClose, submitting])

  const validate = (): string | null => {
    if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(targetAccountId.trim())) return 'Target Account ID must be a valid UUID.'
    if (!/^\d{1,15}(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) return 'Amount must be > 0.'
    if (!/^[A-Za-z]{3}$/.test(currency)) return 'Currency must be 3 letters.'
    if (!/^[A-Za-z0-9_.:-]{8,64}$/.test(fundingReference)) return "Ref must be 8-64 chars of letters, digits, '_', '-', '.', ':'."
    if (reason.trim().length === 0) return 'A reason is required.'; return null
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault(); if (submitting) return
    const problem = validate(); if (problem) { setError(problem); return }
    setError(null); setSubmitting(true)
    try {
      const r = await api.post<FundingResult>('/api/admin/funding', { targetAccountId: targetAccountId.trim(), amount: Number(amount), currency: currency.toUpperCase(), fundingReference, reason: reason.trim() }, { headers: { 'Idempotency-Key': idempotencyKey.current } })
      idempotencyKey.current = crypto.randomUUID()
      if (r.data.status === 'COMPLETED') { onResult({ kind: 'success', message: `Funded ${amount} ${currency} to ${targetAccountId.trim()}.` }); onClose() } 
      else if (r.data.status === 'FAILED') { setError(r.data.errorMessage ?? 'Funding failed.') } 
      else { onResult({ kind: 'success', message: `Funding still processing (${r.data.status}).` }); onClose() }
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status; if (status !== undefined) idempotencyKey.current = crypto.randomUUID()
      setError(errorMessage(err, 'Funding failed.'))
    } finally { setSubmitting(false) }
  }

  const inputClass = 'w-full rounded-lg border border-slate-300 px-3 py-2 text-sm text-slate-900 focus:outline-none focus:ring-2 focus:ring-brand-200 focus:border-brand-500'
  return (
    <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-slate-900/50 backdrop-blur-sm" onMouseDown={(e) => { if (e.target === e.currentTarget && !submitting) onClose() }}>
      <div ref={dialogRef} role="dialog" className="w-full sm:max-w-lg max-h-[95vh] overflow-y-auto bg-white rounded-t-2xl sm:rounded-2xl shadow-2xl border border-slate-200">
        <div className="flex items-start justify-between p-5 sm:p-6 border-b border-slate-100">
          <div className="flex items-start gap-3"><div className="bg-brand-50 text-brand-600 p-2 rounded-lg"><Wallet className="w-5 h-5" /></div><div><h2 className="text-lg font-bold text-slate-900">Fund Account</h2><p className="text-sm text-slate-500 mt-0.5">Add simulated funds to an account.</p></div></div>
          <button type="button" onClick={onClose} disabled={submitting} className="p-1.5 text-slate-400 hover:text-slate-600"><X className="w-5 h-5" /></button>
        </div>
        <form onSubmit={(e) => void submit(e)} className="p-5 sm:p-6 space-y-4">
          <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Target Account ID</label><input ref={firstFieldRef} value={targetAccountId} onChange={(e) => setTargetAccountId(e.target.value)} placeholder="UUID" className={`${inputClass} font-mono`} /></div>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Amount</label><input value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" className={inputClass} /></div>
            <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Currency</label><input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} className={inputClass} /></div>
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-700 mb-1.5">Funding Reference</label>
            <div className="flex gap-2"><input value={fundingReference} onChange={(e) => setFundingReference(e.target.value)} className={`${inputClass} font-mono text-xs`} /><button type="button" onClick={() => setFundingReference(newFundingReference())} className="px-3 border border-slate-300 rounded-lg"><RefreshCw className="w-4 h-4" /></button></div>
          </div>
          <div><label className="block text-sm font-medium text-slate-700 mb-1.5">Reason</label><textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} className={inputClass} /></div>
          {error && <div className="rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800"><AlertTriangle className="w-4 h-4 inline mr-1" /> {error}</div>}
          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onClose} disabled={submitting} className="px-4 py-2 rounded-lg text-sm font-medium bg-white border border-slate-300">Cancel</button>
            <button type="submit" disabled={submitting} className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium text-white bg-brand-600 hover:bg-brand-700">{submitting && <Loader2 className="w-4 h-4 animate-spin" />} Fund account</button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default function AdminDashboard() {
  const { user } = useAuth(); const isAdmin = user?.roles.includes('admin') ?? false
  const [accounts, setAccounts] = useState<PendingAccount[]>([]); const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null); const [toast, setToast] = useState<Toast | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null); const [rejectTarget, setRejectTarget] = useState<PendingAccount | null>(null)
  const [fundOpen, setFundOpen] = useState(false); const fundTriggerRef = useRef<HTMLButtonElement>(null); const toastTimer = useRef<number | undefined>(undefined)

  const showToast = useCallback((t: Toast) => { setToast(t); window.clearTimeout(toastTimer.current); toastTimer.current = window.setTimeout(() => setToast(null), 6000) }, [])
  const load = useCallback(async () => {
    setLoading(true); setLoadError(null)
    try { const r = await api.get<PageResponse<PendingAccount>>('/api/admin/accounts', { params: { status: 'PENDING' } }); setAccounts(r.data.content ?? []) } 
    catch (err) { setLoadError(errorMessage(err, 'Failed to load.')) } finally { setLoading(false) }
  }, [])

  useEffect(() => { if (isAdmin) void load() }, [isAdmin, load])

  const approve = async (acc: PendingAccount) => {
    setBusyId(acc.id)
    try { await api.post(`/api/admin/accounts/${acc.id}/approve`, { reason: 'Approved' }); setAccounts((l) => l.filter((a) => a.id !== acc.id)); showToast({ kind: 'success', message: `Account ${acc.accountNumber} approved.` }) } 
    catch (err) { showToast({ kind: 'error', message: errorMessage(err, 'Failed.') }) } finally { setBusyId(null) }
  }

  const reject = async (reason: string) => {
    if (!rejectTarget) return; const acc = rejectTarget; setBusyId(acc.id)
    try { await api.post(`/api/admin/accounts/${acc.id}/reject`, { reason }); setAccounts((l) => l.filter((a) => a.id !== acc.id)); setRejectTarget(null); showToast({ kind: 'success', message: `Rejected.` }) } 
    catch (err) { setRejectTarget(null); showToast({ kind: 'error', message: errorMessage(err, 'Failed.') }) } finally { setBusyId(null) }
  }

  if (user && !isAdmin) return <Navigate to="/dashboard" replace />
  return (
    <div>
      <div className="flex flex-wrap justify-between items-center gap-3 mb-6">
        <div className="flex items-center gap-3"><div className="bg-purple-100 text-purple-700 p-2 rounded-lg"><ShieldCheck className="w-5 h-5" /></div><div><h1 className="text-2xl font-bold text-slate-900">Admin Portal</h1><p className="text-sm text-slate-500">Pending applications</p></div></div>
        <div className="flex items-center gap-2">
          <button ref={fundTriggerRef} onClick={() => setFundOpen(true)} className="inline-flex items-center gap-2 bg-brand-600 text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-brand-700"><Wallet className="w-4 h-4" /> Fund Account</button>
          <button onClick={() => void load()} disabled={loading} className="inline-flex items-center gap-2 bg-white border border-slate-300 text-slate-700 px-4 py-2 rounded-lg text-sm font-medium hover:bg-slate-50"><RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} /> Refresh</button>
        </div>
      </div>

      {toast && <div className={`mb-4 flex items-start gap-3 rounded-xl border px-4 py-3 text-sm shadow-sm ${toast.kind === 'success' ? 'bg-green-50 border-green-200 text-green-800' : 'bg-red-50 border-red-200 text-red-800'}`}>{toast.kind === 'success' ? <CheckCircle2 className="w-5 h-5 shrink-0" /> : <AlertTriangle className="w-5 h-5 shrink-0" />}<span className="flex-1">{toast.message}</span><button onClick={() => setToast(null)}><X className="w-4 h-4" /></button></div>}
      {loadError && <div className="mb-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{loadError}</div>}

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-left border-collapse min-w-[720px]">
            <thead><tr className="bg-slate-50 text-slate-500 text-xs uppercase border-b border-slate-200"><th className="px-6 py-4 font-medium">Type</th><th className="px-6 py-4 font-medium">Number</th><th className="px-6 py-4 font-medium">Customer</th><th className="px-6 py-4 font-medium">Applied</th><th className="px-6 py-4 font-medium">Status</th><th className="px-6 py-4 font-medium text-right">Actions</th></tr></thead>
            <tbody className="divide-y divide-slate-100">
              {loading && <tr><td colSpan={6} className="px-6 py-8 text-center text-slate-500">Loading...</td></tr>}
              {!loading && accounts.length === 0 && <tr><td colSpan={6} className="px-6 py-8 text-center text-slate-500">No pending applications.</td></tr>}
              {!loading && accounts.map((acc) => (
                <tr key={acc.id} className="hover:bg-slate-50">
                  <td className="px-6 py-4 font-medium text-slate-900">{acc.accountType}</td>
                  <td className="px-6 py-4 font-mono text-sm text-slate-600">{acc.accountNumber}</td>
                  <td className="px-6 py-4 font-mono text-xs text-slate-500">{acc.customerId.slice(0, 8)}…</td>
                  <td className="px-6 py-4 text-sm text-slate-600">{formatDate(acc.createdAt)}</td>
                  <td className="px-6 py-4"><span className="text-xs px-2.5 py-1 rounded-full font-bold bg-amber-100 text-amber-800">{acc.status}</span></td>
                  <td className="px-6 py-4">
                    <div className="flex justify-end gap-2">
                      <button onClick={() => void approve(acc)} disabled={busyId === acc.id} className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-white bg-green-600 hover:bg-green-700">{busyId === acc.id ? <Loader2 className="w-4 h-4 animate-spin" /> : <CheckCircle2 className="w-4 h-4" />} Approve</button>
                      <button onClick={() => setRejectTarget(acc)} disabled={busyId === acc.id} className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium text-red-700 bg-red-50 border border-red-200 hover:bg-red-100"><XCircle className="w-4 h-4" /> Reject</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {fundOpen && <FundModal onClose={() => { setFundOpen(false); window.setTimeout(() => fundTriggerRef.current?.focus(), 0) }} onResult={showToast} />}
      {rejectTarget && <RejectModal account={rejectTarget} submitting={busyId === rejectTarget.id} onClose={() => setRejectTarget(null)} onConfirm={(r) => void reject(r)} />}
    </div>
  )
}