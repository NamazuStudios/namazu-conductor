// The host dashboard exposes each plugin.json entry at /admin/plugin/{route} (confirmed empirically —
// CLAUDE.md's "Dashboard UI Plugins" section only documents the route key, not this /admin prefix,
// which may itself be reconfigurable per deployment). We don't have access to the host's own
// router/module-lifecycle implementation, so navigation here is a best-effort plain URL change rather
// than an SPA-aware transition: it works whether the host does a hard reload or an in-place route
// swap, since terminalSessionManager.open() is safe to call again for an already-running job (it
// re-mints a ticket and reconnects).

export const ROUTES = {
  available: 'conductor-available',
  running: 'conductor-running',
  terminals: 'conductor-terminals',
} as const

export function navigateTo(route: string, params?: Record<string, string>) {
  const query = params ? `?${new URLSearchParams(params).toString()}` : ''
  window.location.assign(`/admin/plugin/${route}${query}`)
}

export function readAndClearParams(): URLSearchParams | null {
  const params = new URLSearchParams(window.location.search)
  if ([...params.keys()].length === 0) return null
  // Clear so a refresh of the Terminals page doesn't re-open the same session request.
  window.history.replaceState(null, '', window.location.pathname)
  return params
}
