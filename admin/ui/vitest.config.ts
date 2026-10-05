import { defineConfig } from 'vitest/config'

// Separate from vite.config.ts / vite.widget.config.ts (lib bundle builds) — this one drives the
// core's unit tests. `jsdom` supplies the DOM the core touches (`document.createElement` for the
// terminal host); xterm itself and the two addons are mocked in the tests so no real terminal
// surface is needed.
export default defineConfig({
  test: {
    environment: 'jsdom',
  },
})
