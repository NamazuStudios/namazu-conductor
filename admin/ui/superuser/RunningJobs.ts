import React from 'react'
import { fetchJobs, stopJob } from './api'
import { DetailGrid } from './ui'
import { terminalSessionManager } from './terminal'
import type { JobExecution, ProviderExecutionsResult } from './types'

const h = React.createElement

function statusColorClasses(status: string): string {
  if (status === 'RUNNING') return 'text-green-700 bg-green-50'
  if (status === 'PENDING') return 'text-yellow-700 bg-yellow-50'
  if (status === 'FAILED') return 'text-destructive bg-destructive/10'
  return 'text-muted-foreground bg-muted'
}

function RunningJobRow(props: { execution: JobExecution; element: string; onRefresh: () => void }) {
  const ex = props.execution
  const [isExpanded, setExpanded] = React.useState(false)
  const [isStopping, setStopping] = React.useState(false)
  const [stopError, setStopError] = React.useState<string | null>(null)

  function handleStop() {
    setStopping(true)
    setStopError(null)
    stopJob(props.element, ex.id)
      .then(props.onRefresh)
      .catch((e: Error) => setStopError(e.message || 'Stop failed'))
      .finally(() => setStopping(false))
  }

  const containers = ex.containers ?? []

  return h('div', { className: 'rounded-lg border bg-card' },
    h('div', { className: 'flex items-center gap-3 px-4 py-2.5 flex-wrap' },
      h('button', { className: 'text-xs opacity-50 shrink-0', onClick: () => setExpanded((v) => !v) }, isExpanded ? '▼' : '▶'),
      h('span', { className: 'font-mono text-xs break-all flex-1 min-w-0' }, ex.id),
      h('span', { className: `text-xs font-medium px-2 py-0.5 rounded-full shrink-0 ${statusColorClasses(ex.status)}` }, ex.status),
      ex.endpoints && ex.endpoints.length > 0 &&
        h('span', { className: 'font-mono text-xs text-muted-foreground shrink-0' },
          ex.endpoints.map((ep) => `${ep.host}:${ep.port}/${ep.protocol}`).join(', ')),
      ex.status === 'RUNNING' && containers.map((c) => h('button', {
        key: c.id,
        className: 'shrink-0 px-2.5 py-1 rounded border text-xs hover:bg-muted disabled:opacity-50 transition-colors',
        onClick: () => terminalSessionManager.open(
          props.element, ex.id, c.primary ? null : c.id,
          containers.length > 1 ? `${ex.id.slice(0, 8)}/${c.name}` : ex.id.slice(0, 8),
        ),
        title: `Attach a terminal to container '${c.name}'`,
      }, `▤ ${containers.length > 1 ? c.name : 'Terminal'}`)),
      h('button', {
        disabled: isStopping,
        onClick: handleStop,
        className: 'shrink-0 px-2.5 py-1 rounded border text-xs text-destructive border-destructive/40 hover:bg-destructive/10 disabled:opacity-50 transition-colors',
      }, isStopping ? 'Stopping…' : 'Stop')),
    stopError && h('div', { className: 'border-t px-4 py-2 text-xs text-destructive font-mono' }, stopError),
    isExpanded && Boolean(ex.details) && h('div', { className: 'border-t px-4 py-3' }, h(DetailGrid, { obj: ex.details })))
}

export function RunningJobsSection() {
  const [data, setData] = React.useState<{ loading: boolean; providers: ProviderExecutionsResult[]; error: string | null }>({
    loading: true, providers: [], error: null,
  })

  const load = React.useCallback(() => {
    fetchJobs()
      .then((body) => setData({ loading: false, providers: body.providers || [], error: null }))
      .catch((e: Error) => setData({ loading: false, providers: [], error: e.message }))
  }, [])

  React.useEffect(() => {
    load()
    const interval = setInterval(load, 10000)
    return () => clearInterval(interval)
  }, [load])

  const allExecutions = data.providers.flatMap((p) => (p.executions ?? []).map((execution) => ({ element: p.element, execution })))

  const refreshIcon: React.ReactNode = data.loading
    ? h('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse inline-block' })
    : '↺'

  return h('div', { className: 'space-y-3' },
    h('div', { className: 'flex items-center justify-between' },
      h('h2', { className: 'text-lg font-semibold' }, 'Running Jobs'),
      h('button', {
        onClick: load,
        disabled: data.loading,
        className: 'flex items-center gap-1.5 px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors disabled:opacity-50',
      }, refreshIcon, data.loading ? ' Refreshing…' : ' Refresh')),
    data.error && h('p', { className: 'text-xs text-destructive' }, data.error),
    !data.loading && allExecutions.length === 0 &&
      h('p', { className: 'text-sm text-muted-foreground' }, 'No active jobs found.'),
    allExecutions.length > 0 &&
      h('div', { className: 'space-y-2' },
        allExecutions.map((item) => h('div', { key: `${item.element}:${item.execution.id}` },
          h('div', { className: 'text-xs text-muted-foreground mb-1 font-mono' }, item.element),
          h(RunningJobRow, { execution: item.execution, element: item.element, onRefresh: load })))))
}
