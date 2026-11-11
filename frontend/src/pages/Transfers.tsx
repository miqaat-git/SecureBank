export default function Transfers() {
  return (
    <div>
      <h1 className="text-2xl font-bold text-slate-900 mb-6">Transfers</h1>
      <div className="bg-white border border-slate-200 rounded-xl p-8 text-center shadow-sm">
        <div className="bg-brand-50 text-brand-600 w-16 h-16 rounded-full flex items-center justify-center mx-auto mb-4">
           <svg className="w-8 h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
             <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7h12m0 0l-4-4m4 4l-4 4m0 6H4m0 0l4 4m-4-4l4-4" />
           </svg>
        </div>
        <h2 className="text-lg font-bold text-slate-900 mb-2">Send Money</h2>
        <p className="text-slate-500 mb-6 max-w-sm mx-auto">Transfer funds securely between your accounts or to another SecureBank customer.</p>
        <button className="bg-brand-600 text-white px-6 py-2.5 rounded-lg font-medium hover:bg-brand-700 transition opacity-50 cursor-not-allowed">
          Coming in Phase 9D
        </button>
      </div>
    </div>
  )
}