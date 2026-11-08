import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div className="min-h-screen flex items-center justify-center">
      <div className="text-center">
        <h1 className="text-6xl font-bold text-slate-300 mb-4">404</h1>
        <p className="text-slate-500 mb-6">Page not found</p>
        <Link to="/" className="text-brand-600 hover:underline">Back to home</Link>
      </div>
    </div>
  )
}