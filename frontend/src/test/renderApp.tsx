import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'

import type { Role } from '../api/types'
import { clearSession } from '../auth/session'
import { ToastProvider } from '../components/ui/Toast'
import { TooltipProvider } from '../components/ui/Tooltip'
import { routes } from '../routes'
import { session } from './server'

/**
 * Mounts the real routes at `path`. `as` decides whether the refresh cookie restores a session (and for whom);
 * omit it to start signed out.
 */
export function renderApp(path: string, as?: Role) {
  clearSession()
  session.role = as ?? null
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <ToastProvider>
          <RouterProvider router={router} />
        </ToastProvider>
      </TooltipProvider>
    </QueryClientProvider>,
  )
  return { ...utils, router }
}
