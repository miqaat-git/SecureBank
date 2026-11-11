import { NavLink, Outlet } from 'react-router-dom'
import { Shield, LogOut, User as UserIcon, LayoutDashboard, CreditCard, ArrowRightLeft, ShieldCheck } from 'lucide-react'
import { useAuth } from '../lib/auth'

export default function AppLayout() {
  const { user, logout } = useAuth()
  const isAdmin = user?.roles.includes('admin')

  const navItems = [
    { name: 'Overview', path: '/dashboard', icon: LayoutDashboard },
    { name: 'Accounts', path: '/accounts', icon: CreditCard },
    { name: 'Transfers', path: '/transfers', icon: ArrowRightLeft },
    ...(isAdmin ? [{ name: 'Admin Portal', path: '/admin/accounts', icon: ShieldCheck }] : []),
  ]

  return (
    <div className="min-h-screen bg-slate-50 flex">
      {/* DARK SIDEBAR */}
      <aside className="w-72 bg-slate-900 flex flex-col hidden md:flex shrink-0">
        <div className="p-8 flex items-center gap-3">
          <div className="bg-brand-500 text-white p-2 rounded-xl shadow-lg shadow-brand-500/20">
            <Shield className="w-6 h-6" />
          </div>
          <span className="text-xl font-bold text-white tracking-tight">SecureBank</span>
          {isAdmin && (
            <span className="text-[10px] bg-purple-500/20 text-purple-300 px-2 py-1 rounded-md font-bold ml-auto border border-purple-500/30">
              ADMIN
            </span>
          )}
        </div>
        
        <nav className="flex-1 px-4 space-y-2 mt-4">
          {navItems.map((item) => (
            <NavLink
              key={item.name}
              to={item.path}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-3.5 rounded-xl font-medium transition-all duration-200 ${
                  isActive
                    ? 'bg-slate-800 text-white shadow-sm'
                    : 'text-slate-400 hover:bg-slate-800/50 hover:text-slate-200'
                }`
              }
            >
              <item.icon className={`w-5 h-5`} />
              {item.name}
            </NavLink>
          ))}
        </nav>

        <div className="p-6 mt-auto">
          <div className="flex items-center gap-3 px-4 py-3 mb-4 rounded-xl bg-slate-800/50 border border-slate-700/50">
            <div className="bg-slate-700 p-2 rounded-full">
              <UserIcon className="w-4 h-4 text-slate-300" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-sm font-medium text-white truncate">
                {user?.preferred_username ?? 'User'}
              </p>
              <p className="text-xs text-slate-400 truncate">{user?.email}</p>
            </div>
          </div>
          <button
            onClick={logout}
            className="flex items-center justify-center gap-2 w-full px-4 py-3 text-sm text-slate-400 hover:bg-slate-800 hover:text-red-400 rounded-xl transition"
          >
            <LogOut className="w-4 h-4" />
            Sign out
          </button>
        </div>
      </aside>

      {/* MAIN CONTENT AREA */}
      <main className="flex-1 flex flex-col min-w-0 h-screen overflow-hidden">
        <div className="flex-1 overflow-auto p-6 md:p-10">
          <div className="max-w-6xl mx-auto">
            <Outlet />
          </div>
        </div>
      </main>
    </div>
  )
}