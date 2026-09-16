// The host dashboard exposes each plugin.json entry at /admin/plugin/{route} (confirmed empirically —
// CLAUDE.md's "Dashboard UI Plugins" section only documents the route key, not this /admin prefix,
// which may itself be reconfigurable per deployment).

export const ROUTES = {
  available: 'conductor-available',
  running: 'conductor-running',
} as const
