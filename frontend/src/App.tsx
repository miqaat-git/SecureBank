import { Routes, Route, Navigate } from 'react-router-dom'
import Landing from './pages/Landing'
import Dashboard from './pages/Dashboard'
import Accounts from './pages/Accounts'
import Transfers from './pages/Transfers'
import NotFound from './pages/NotFound'
import ProtectedRoute from './components/ProtectedRoute'
import AppLayout from './components/AppLayout'

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Landing />} />
      
      {/* Protected Application Routes */}
      <Route
        element={
          <ProtectedRoute>
            <AppLayout />
          </ProtectedRoute>
        }
      >
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/accounts" element={<Accounts />} />
        <Route path="/transfers" element={<Transfers />} />
      </Route>

      <Route path="*" element={<NotFound />} />
    </Routes>
  )
}