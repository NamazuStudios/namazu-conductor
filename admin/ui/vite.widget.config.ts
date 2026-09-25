import { defineConfig } from 'vite'

// Second Vite build for the standalone embeddable terminal bundle: a self-contained IIFE that
// declares `window.ConductorTerminal`. Unlike the dashboard plugin bundle it bundles xterm itself
// (the host page has no terminal runtime — only `window.React` is guaranteed there, and this bundle
// doesn't use React at all). Landed in the Element's UI directory next to plugin.bundle.js so a
// `<script src>` from /app/ui/{element-prefix}/widget/conductor-terminal.js works on any host page.
export default defineConfig({
  build: {
    outDir: '../src/main/ui/widget',
    emptyOutDir: false,
    lib: {
      entry: 'widget/main.ts',
      formats: ['iife'],
      // The global name on `window`. Note the actual `window.ConductorTerminal` assignment lives
      // inside widget/main.ts itself (the lib name only drives Rollup's wrapper object).
      name: 'ConductorTerminal',
      fileName: () => 'conductor-terminal.js',
    },
  },
})