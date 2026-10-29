import { createBrowserRouter, Navigate, RouterProvider } from 'react-router'

import { TooltipProvider } from './components/ui/Tooltip'
import { StyleGuidePage } from './features/style-guide/StyleGuidePage'

// M1: the style guide only (development builds). The app shell, login and screens arrive in M2 (docs/ui-plan.md §11).
const router = createBrowserRouter([
  ...(import.meta.env.DEV ? [{ path: '/style-guide', element: <StyleGuidePage /> }] : []),
  { path: '*', element: import.meta.env.DEV ? <Navigate to="/style-guide" replace /> : <Placeholder /> },
])

function Placeholder() {
  return (
    <main className="mx-auto max-w-[720px] px-4 py-24">
      <p className="type-label text-burgundy">AlphaLedger</p>
      <h1 className="type-headline mt-3">The dashboard is being built.</h1>
    </main>
  )
}

export function App() {
  return (
    <TooltipProvider>
      <RouterProvider router={router} />
    </TooltipProvider>
  )
}
