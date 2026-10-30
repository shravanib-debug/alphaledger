import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// The API is always reached on the same origin as the app (docs/ui-plan.md §9, docs/auth-session.md §6): Vite
// proxies /api to the gateway in development; nginx does the same in the Docker image.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    testTimeout: 30_000, // flow tests type into real forms and open Radix menus
  },
})
