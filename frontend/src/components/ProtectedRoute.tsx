import { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../lib/auth'

export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { authenticated, initializing } = useAuth()

  if (initializing) {
    return (
      <div className="min-h-screen flex items-center justify-center">
        <div className="text-slate-500">Loading…</div>
      </div>
    )
  }

  if (!authenticated) {
    return <Navigate to="/" replace />
  }

  return <>{children}</>
}