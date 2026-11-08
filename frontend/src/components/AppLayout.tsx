import { ReactNode } from 'react'
import { Shield, LogOut, User as UserIcon } from 'lucide-react'
import { useAuth } from '../lib/auth'

export default function AppLayout({ children }: { children: ReactNode }) {
  const { user, logout } = useAuth()
  const isAdmin = user?.roles.includes('admin')

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200">
        <div className="max-w-6xl mx-auto px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="bg-brand-600 text-white p-2 rounded-lg">
              <Shield className="w-5 h-5" />
            </div>
            <span className="text-xl font-bold text-slate-900">SecureBank</span>
            {isAdmin && (
              <span className="text-xs bg-purple-100 text-purple-800 px-2 py-1 rounded-full font-medium">
                ADMIN
              </span>
            )}
          </div>
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2 text-sm text-slate-600">
              <UserIcon className="w-4 h-4" />
              <span>{user?.email ?? user?.preferred_username ?? 'Unknown'}</span>
            </div>
            <button
              onClick={logout}
              className="flex items-center gap-1.5 text-sm text-slate-600 hover:text-red-600 transition"
            >
              <LogOut className="w-4 h-4" />
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="max-w-6xl mx-auto px-6 py-8">{children}</main>
    </div>
  )
}