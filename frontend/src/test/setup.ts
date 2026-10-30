import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'

import { server } from './server'

configure({ asyncUtilTimeout: 8000 })

// Browser APIs jsdom lacks, used by Radix (pointer capture, scrolling) and Recharts (ResizeObserver).
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver
Element.prototype.hasPointerCapture ??= () => false
Element.prototype.setPointerCapture ??= () => undefined
Element.prototype.releasePointerCapture ??= () => undefined
Element.prototype.scrollIntoView ??= () => undefined

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
})
afterAll(() => server.close())
