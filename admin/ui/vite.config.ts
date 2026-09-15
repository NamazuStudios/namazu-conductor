import { defineConfig } from 'vite'

// Builds a single, self-contained IIFE bundle that reads React off `window.React` (the dashboard
// host provides it — see CLAUDE.md's "Dashboard UI Plugins" section) rather than bundling it.
// Output lands directly in the packaged Element's UI directory, matching plugin.json's bundlePath.
export default defineConfig({
  build: {
    outDir: '../src/main/ui/superuser',
    emptyOutDir: false,
    lib: {
      entry: 'superuser/main.ts',
      formats: ['iife'],
      name: '__conductorAdminBundle',
      fileName: () => 'plugin.bundle.js',
    },
    rollupOptions: {
      external: ['react'],
      output: {
        globals: { react: 'React' },
      },
    },
  },
})
