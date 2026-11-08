import { Shield, LogIn } from 'lucide-react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'

export default function Landing() {
  const { authenticated, initializing, login } = useAuth()

  if (initializing) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-slate-500">Loading…</div>
      </div>
    )
  }

  if (authenticated) {
    return <Navigate to="/dashboard" replace />
  }

  return (
    <div className="min-h-screen flex items-center justify-center p-8 bg-slate-50">
      <div className="max-w-md w-full bg-white rounded-2xl shadow-lg p-10 text-center">
        <div className="flex items-center justify-center gap-3 mb-6">
          <div className="bg-brand-600 text-white p-3 rounded-xl">
            <Shield className="w-8 h-8" />
          </div>
          <h1 className="text-3xl font-bold text-slate-900">SecureBank</h1>
        </div>
        <p className="text-slate-500 mb-8">Your secure digital banking companion</p>
        <button
          onClick={login}
          className="inline-flex items-center gap-2 bg-brand-600 hover:bg-brand-700 text-white font-medium px-6 py-3 rounded-lg transition"
        >
          <LogIn className="w-5 h-5" />
          Sign In
        </button>
        <p className="text-xs text-slate-400 mt-8">
          Sign in with your customer or admin credentials
        </p>
      </div>
    </div>
  )
}