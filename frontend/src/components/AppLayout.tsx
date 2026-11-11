import { ReactNode } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { Shield, LogOut, User as UserIcon, LayoutDashboard, CreditCard, ArrowRightLeft } from 'lucide-react'
import { useAuth } from '../lib/auth'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const isAdmin = user?.roles.includes('admin')

  const navItems = [
    { name: 'Dashboard', path: '/dashboard', icon: LayoutDashboard },
    { name: 'Accounts', path: '/accounts', icon: CreditCard },
    { name: 'Transfers', path: '/transfers', icon: ArrowRightLeft },
  ]

  return (
    <div className="min-h-screen bg-slate-50 flex">
      {/* SIDEBAR */}
      <aside className="w-64 bg-white border-r border-slate-200 flex flex-col hidden md:flex">
        <div className="p-6 flex items-center gap-3 border-b border-slate-100">
          <div className="bg-brand-600 text-white p-2 rounded-lg">
            <Shield className="w-5 h-5" />
          </div>
          <span className="text-xl font-bold text-slate-900 tracking-tight">SecureBank</span>
          {isAdmin && (
            <span className="text-[10px] bg-purple-100 text-purple-800 px-2 py-0.5 rounded-full font-bold ml-auto">
              ADMIN
            </span>
          )}
        </div>
        
        <nav className="flex-1 p-4 space-y-1">
          {navItems.map((item) => (
            <NavLink
              key={item.name}
              to={item.path}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-3 rounded-lg font-medium transition ${
                  isActive
                    ? 'bg-brand-50 text-brand-700'
                    : 'text-slate-600 hover:bg-slate-100'
                }`
              }
            >
              <item.icon className="w-5 h-5" />
              {item.name}
            </NavLink>
          ))}
        </nav>

        <div className="p-4 border-t border-slate-100">
          <div className="flex items-center gap-3 px-4 py-3 mb-2 rounded-lg bg-slate-50">
            <div className="bg-slate-200 p-2 rounded-full">
              <UserIcon className="w-4 h-4 text-slate-600" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-slate-900 truncate">
                {user?.preferred_username ?? 'User'}
              </p>
              <p className="text-xs text-slate-500 truncate">{user?.email}</p>
            </div>
          </div>
          <button
            onClick={logout}
            className="flex items-center gap-2 w-full px-4 py-2 text-sm text-red-600 hover:bg-red-50 rounded-lg transition"
          >
            <LogOut className="w-4 h-4" />
            Sign out
          </button>
        </div>
      </aside>

      {/* MAIN CONTENT */}
      <main className="flex-1 flex flex-col min-w-0">
        <header className="bg-white border-b border-slate-200 md:hidden px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-2">
             <Shield className="w-6 h-6 text-brand-600" />
             <span className="font-bold">SecureBank</span>
          </div>
          <button onClick={logout} className="text-sm text-red-600">Sign out</button>
        </header>
        
        <div className="flex-1 p-6 md:p-8 overflow-auto">
          <div className="max-w-5xl mx-auto">
            <Outlet />
          </div>
        </div>
      </main>
    </div>
  )
}