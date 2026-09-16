import React from 'react'
import { RunningJobsSection } from './RunningJobs'
import { readAndClearParams } from './nav'
import { TerminalTabs, BellControls, injectXtermStyles, terminalSessionManager } from './terminal'

const h = React.createElement

function useHasOpenTerminals(): boolean {
  const snapshot = React.useSyncExternalStore(
    (listener) => terminalSessionManager.subscribe(listener),
    () => terminalSessionManager.getSnapshot(),
  )
  return snapshot.sessions.length > 0
}

export function RunningJobsPage() {
  const [highlightId] = React.useState(() => readAndClearParams()?.get('highlight') ?? null)
  const hasOpenTerminals = useHasOpenTerminals()

  React.useEffect(() => { injectXtermStyles() }, [])

  return h('div', { className: 'p-6 space-y-6' },
    h('div', { className: 'flex items-center justify-between' },
      h('h1', { className: 'text-2xl font-bold' }, 'Running Jobs & Services'),
      h(BellControls)),
    h(RunningJobsSection, { highlightId }),
    hasOpenTerminals && h(TerminalTabs))
}
