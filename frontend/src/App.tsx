import { useEffect, useState } from 'react'
import axios from 'axios'
import { Shield, CheckCircle2, XCircle, Loader2 } from 'lucide-react'

type Health = 'checking' | 'UP' | 'DOWN' | 'unreachable'

export default function App() {
  const [health, setHealth] = useState<Health>('checking')
  const [error, setError] = useState<string>('')

  useEffect(() => {
    axios.get<{ status: string }>('/actuator/health')
      .then(r => setHealth(r.data.status === 'UP' ? 'UP' : 'DOWN'))
      .catch(e => {
        setHealth('unreachable')
        setError(e.message ?? 'unknown error')
      })
  }, [])

  const badge = {
    checking:    { cls: 'bg-yellow-100 text-yellow-800', icon: <Loader2 className="w-4 h-4 animate-spin" />, label: 'checking...' },
    UP:          { cls: 'bg-green-100 text-green-800',   icon: <CheckCircle2 className="w-4 h-4" />,      label: 'UP' },
    DOWN:        { cls: 'bg-red-100 text-red-800',       icon: <XCircle className="w-4 h-4" />,           label: 'DOWN' },
    unreachable: { cls: 'bg-red-100 text-red-800',       icon: <XCircle className="w-4 h-4" />,           label: 'unreachable' },
  }[health]

  return (
    <div className="min-h-full flex items-center justify-center p-8">
      <div className="max-w-lg w-full bg-white rounded-xl shadow-lg p-8">
        <div className="flex items-center gap-3 mb-1">
          <div className="bg-brand-600 text-white p-2 rounded-lg">
            <Shield className="w-6 h-6" />
          </div>
          <h1 className="text-3xl font-bold text-slate-900">SecureBank</h1>
        </div>
        <p className="text-slate-500 text-sm mb-8">Phase 9A - frontend scaffold</p>

        <div className="border-t border-slate-200 pt-6">
          <div className="flex items-center justify-between mb-3">
            <span className="font-medium text-slate-700">Backend health</span>
            <span className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium ${badge.cls}`}>
              {badge.icon}
              {badge.label}
            </span>
          </div>
          {error && <p className="text-xs text-red-600 mt-1">{error}</p>}
          <p className="text-xs text-slate-500 mt-6 leading-relaxed">
            Proves the Vite dev proxy routes /actuator/health to the gateway on localhost:8080.
            Green badge = backend reachable.
          </p>
        </div>
      </div>
    </div>
  )
}