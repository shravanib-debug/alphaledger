import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'

import { accessToken } from '../auth/session'
import { renderApp } from './renderApp'
import { API, BREAK, server, session, TRADE, VALUATION } from './server'

describe('sign-in and the browser session', () => {
  it('sends a signed-out user to the login page, then back after signing in with the cookie-mode header', async () => {
    let loginHeaders: Headers | undefined
    server.use(
      http.post(`${API}/auth/login`, async ({ request }) => {
        loginHeaders = request.headers
        session.role = 'PM'
        return HttpResponse.json({ accessToken: 'access-PM', tokenType: 'Bearer', expiresIn: 900, user: { id: 2, email: 'priya.pm@alphaledger.test', fullName: 'Priya Patel', role: 'PM', status: 'ACTIVE', createdAt: '' } })
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp('/trades')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/trades')}`)

    await user.type(screen.getByLabelText('Email'), 'priya.pm@alphaledger.test')
    await user.type(screen.getByLabelText('Password'), 'Demo@AlphaLedger2026')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Trade blotter.' })).toBeInTheDocument()
    expect(loginHeaders?.get('X-Client')).toBe('web')
    // The refresh token never reaches script; only the access token is held, in memory.
    expect(accessToken()).toBe('access-PM')
  })

  it('shows a clear message for wrong credentials', async () => {
    const user = userEvent.setup()
    renderApp('/login')
    await user.type(await screen.findByLabelText('Email'), 'priya.pm@alphaledger.test')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Email or password is incorrect.')).toBeInTheDocument()
  })

  it('restores the session from the cookie on load and renews once when concurrent calls hit a 401', async () => {
    let refreshes = 0
    let rejectOnce = true
    server.use(
      http.post(`${API}/auth/refresh`, ({ request }) => {
        refreshes++
        expect(request.headers.get('X-Client')).toBe('web')
        expect(request.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f]{32}$/)
        return HttpResponse.json({ accessToken: `access-${refreshes}`, tokenType: 'Bearer', expiresIn: 900, user: { id: 2, email: 'priya.pm@alphaledger.test', fullName: 'Priya Patel', role: 'PM', status: 'ACTIVE', createdAt: '' } })
      }),
      // The first valuation and trades calls carry the restored token and get 401 (it "expired"); both must wait
      // for one renewal and retry with the new token.
      http.get(`${API}/portfolios/:id/valuation`, ({ request }) => {
        if (rejectOnce && request.headers.get('Authorization') === 'Bearer access-1') {
          return HttpResponse.json({ status: 401, error: 'UNAUTHORIZED', message: 'expired' }, { status: 401 })
        }
        return HttpResponse.json(VALUATION)
      }),
      http.get(`${API}/trades`, ({ request }) => {
        if (request.headers.get('Authorization') === 'Bearer access-1') {
          rejectOnce = false
          return HttpResponse.json({ status: 401, error: 'UNAUTHORIZED', message: 'expired' }, { status: 401 })
        }
        return HttpResponse.json({ content: [TRADE], page: 0, size: 5, totalElements: 1, totalPages: 1 })
      }),
    )
    renderApp('/', 'PM')

    expect(await screen.findByRole('heading', { name: 'Your portfolios, at a glance.' })).toBeInTheDocument()
    expect(await screen.findByText('₹ 3.00 L')).toBeInTheDocument() // NAV 2,99,949.04 rounds to 3.00 L
    await waitFor(() => expect(refreshes).toBe(2)) // one on load, one shared renewal for the concurrent 401s
    expect(accessToken()).toBe('access-2')
  })

  it('returns to the login page when the session cannot be renewed', async () => {
    renderApp('/valuation', undefined)
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
  })
})

describe('role-aware navigation', () => {
  it('hides reconciliation and administration from a portfolio manager and redirects a direct visit', async () => {
    const { router } = renderApp('/recon', 'PM')
    expect(await screen.findByRole('heading', { name: 'Your portfolios, at a glance.' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/')
    const nav = screen.getAllByRole('navigation', { name: 'Main' })[0]!
    expect(within(nav).queryByRole('link', { name: /Reconciliation/ })).not.toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: /Administration/ })).not.toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: /Trades/ })).toBeInTheDocument()
  })

  it('shows an operations analyst the reconciliation menu and the unread count', async () => {
    renderApp('/', 'OPS_ANALYST')
    const nav = (await screen.findAllByRole('navigation', { name: 'Main' }))[0]!
    expect(within(nav).getByRole('link', { name: /Reconciliation/ })).toBeInTheDocument()
    expect(await within(nav).findByText('unread:', { exact: false })).toBeInTheDocument()
  })

  it('gives an auditor read-only screens: no trade booking, no break actions', async () => {
    renderApp('/trades', 'AUDITOR')
    expect(await screen.findByRole('heading', { name: 'Trade blotter.' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /New trade/ })).not.toBeInTheDocument()
  })
})

describe('positions show their price source', () => {
  it('marks a stale price in words', async () => {
    renderApp('/positions', 'AUDITOR')
    expect(await screen.findByText('Embassy Office Parks REIT')).toBeInTheDocument()
    expect(screen.getAllByText('stale').length).toBeGreaterThan(0)
    expect(screen.getByText('NSE')).toBeInTheDocument()
  })
})

describe('reconciliation break workflow', () => {
  it('assigns a break with a required comment and shows the new state', async () => {
    let assignBody: unknown
    server.use(
      http.post(`${API}/recon/breaks/:id/assign`, async ({ request }) => {
        assignBody = await request.json()
        return HttpResponse.json({ ...BREAK, status: 'IN_REVIEW', assigned_to: 3 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/recon/breaks/7', 'OPS_ANALYST')

    expect(await screen.findByRole('heading', { name: 'Quantity mismatch on TR-101.' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Assign' }))
    const dialog = await screen.findByRole('dialog')
    const submit = within(dialog).getByRole('button', { name: 'Assign' })
    expect(submit).toBeDisabled() // a comment is required

    await user.type(within(dialog).getByLabelText('Comment'), 'Taking this one; custodian shows 490.')
    await user.click(submit)
    await waitFor(() => expect(assignBody).toEqual({ comment: 'Taking this one; custodian shows 490.' }))
    expect(await screen.findByText('Assigned to Rohan Mehta.')).toBeInTheDocument()
  })

  it('does not offer break actions to an auditor', async () => {
    renderApp('/recon/breaks/7', 'AUDITOR')
    expect(await screen.findByRole('heading', { name: 'Quantity mismatch on TR-101.' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign' })).not.toBeInTheDocument()
  })
})

describe('cash', () => {
  it("shows the service's refusal when a withdrawal exceeds the balance", async () => {
    server.use(
      http.post(`${API}/portfolios/:id/cash`, () =>
        HttpResponse.json({ status: 422, error: 'BUSINESS_RULE_VIOLATION', message: 'Withdrawal of 90000 exceeds the cash balance of 85000.00' }, { status: 422 }),
      ),
    )
    const user = userEvent.setup()
    renderApp('/valuation?portfolio=2', 'OPS_ANALYST')
    await user.click(await screen.findByRole('tab', { name: 'Cash' }))
    await user.click(await screen.findByRole('button', { name: 'Withdraw' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Amount (₹)'), '90000')
    await user.click(within(dialog).getByRole('button', { name: 'Record' }))
    expect(await within(dialog).findByText('Withdrawal of 90000 exceeds the cash balance of 85000.00')).toBeInTheDocument()
  })
})

describe('trade booking', () => {
  it('shows a rejected trade as a result with its reason', async () => {
    server.use(
      http.post(`${API}/trades`, () =>
        HttpResponse.json({ ...TRADE, id: 102, tradeRef: 'TR-102', side: 'SELL', quantity: 900, status: 'REJECTED', rejectionReason: 'SELL quantity 900 exceeds the current holding of 500' }, { status: 201 }),
      ),
    )
    const user = userEvent.setup()
    renderApp('/trades/new', 'PM')
    expect(await screen.findByRole('heading', { name: 'New trade.' })).toBeInTheDocument()
    // The one portfolio Priya manages is preselected.
    expect(await screen.findByLabelText('Portfolio')).toHaveTextContent('AlphaLedger Bharat Real Assets Fund')

    await user.click(screen.getByLabelText('Instrument'))
    await user.click(await screen.findByRole('option', { name: /Embassy Office Parks REIT/ }))
    await user.type(screen.getByLabelText('Quantity'), '900')
    await user.type(screen.getByLabelText('Price (₹)'), '440')
    await user.clear(screen.getByLabelText('Trade date'))
    await user.type(screen.getByLabelText('Trade date'), '2026-10-15')
    await user.click(screen.getByRole('button', { name: /Book/ }))

    expect(await screen.findByText(/SELL quantity 900 exceeds the current holding of 500/)).toBeInTheDocument()
  })
})
