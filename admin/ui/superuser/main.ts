import React from 'react'
import xtermCss from '@xterm/xterm/css/xterm.css?inline'
import { fetchProfiles } from './api'
import { StatusIndicator } from './ui'
import { ProviderSection } from './Profiles'
import { RunningJobsSection } from './RunningJobs'
import { TerminalTabs } from './terminal'
import type { ProviderProfilesResult } from './types'

declare global {
  interface Window {
    __elementsPlugins?: { register: (route: string, component: React.ComponentType) => void }
  }
}

const h = React.createElement

function injectXtermStyles() {
  if (document.getElementById('conductor-xterm-styles')) return
  const style = document.createElement('style')
  style.id = 'conductor-xterm-styles'
  style.textContent = xtermCss
  document.head.appendChild(style)
}

function ConductorAdmin() {
  const [data, setData] = React.useState<{ loading: boolean; status: string | null; error: string | null; providers: ProviderProfilesResult[] }>({
    loading: true, status: null, error: null, providers: [],
  })

  React.useEffect(() => {
    injectXtermStyles()
  }, [])

  React.useEffect(() => {
    fetchProfiles()
      .then((body) => setData({ loading: false, status: body.status, error: null, providers: body.providers || [] }))
      .catch((e: Error) => setData({ loading: false, status: 'error', error: e.message, providers: [] }))
  }, [])

  return h('div', { className: 'p-6 max-w-3xl space-y-8' },
    h('div', { className: 'space-y-6' },
      h('div', { className: 'flex items-center justify-between' },
        h('h1', { className: 'text-2xl font-bold' }, 'Namazu Conductor Jobs'),
        h(StatusIndicator, { loading: data.loading, status: data.status, error: data.error })),
      !data.loading && data.status === 'error' && data.providers.length === 0 &&
        h('div', { className: 'rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive' },
          data.error || 'Failed to load profiles.'),
      !data.loading && data.providers.length > 0 &&
        h('div', { className: 'space-y-6' },
          data.providers.map((p) => h(ProviderSection, { key: p.element, provider: p })))),
    h('hr', { className: 'border-border' }),
    h(RunningJobsSection),
    h('hr', { className: 'border-border' }),
    h(TerminalTabs))
}

window.__elementsPlugins?.register('conductor-admin', ConductorAdmin)
