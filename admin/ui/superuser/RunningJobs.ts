import React from 'react'
import { fetchJobs, stopJob } from './api'
import { Accordion, DetailGrid } from './ui'
import { terminalSessionManager } from './terminal'
import type { JobExecution, ProviderExecutionsResult } from './types'

const h = React.createElement

function statusColorClasses(status: string): string {
  if (status === 'RUNNING') return 'text-green-700 bg-green-50'
  if (status === 'PENDING') return 'text-yellow-700 bg-yellow-50'
  if (status === 'FAILED') return 'text-destructive bg-destructive/10'
  return 'text-muted-foreground bg-muted'
}

/**
 * Per-container "Attach Terminal" row, with an optional command override — like `kubectl exec` vs
 * `kubectl attach`. Left blank, attaches with the provider's default (e.g. a shell); a naive
 * whitespace-split of whatever's typed otherwise, which is an intentional simplification for this
 * advanced/power-user path (not full shell quoting/parsing).
 */
function ContainerAttachRow(props: { element: string; jobId: string; running: boolean; container: { id: string; name: string; primary: boolean; defaultCommand?: string[] }; label: string }) {
  const { container: c } = props
  const [command, setCommand] = React.useState(() => (c.defaultCommand ?? []).join(' '))

  function handleAttach() {
    const tokens = command.trim().split(/\s+/).filter(Boolean)
    terminalSessionManager.open(props.element, props.jobId, c.primary ? null : c.id, props.label, tokens.length > 0 ? tokens : undefined)
  }

  return h('div', { className: 'flex items-center gap-2 text-xs' },
    h('span', { className: 'font-mono flex-1' }, c.name, c.primary && h('span', { className: 'ml-1.5 text-muted-foreground' }, '(primary)')),
    h('input', {
      value: command,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => setCommand(e.target.value),
      placeholder: '/bin/sh (optional)',
      className: 'w-40 rounded border bg-background px-1.5 py-0.5 text-xs font-mono focus:outline-none focus:ring-1 focus:ring-primary',
      title: 'Command to exec instead of the container’s default shell',
    }),
    h('button', {
      disabled: !props.running,
      className: 'shrink-0 px-2.5 py-1 rounded border text-xs hover:bg-muted disabled:opacity-50 transition-colors',
      onClick: handleAttach,
      title: `Attach a terminal to container '${c.name}'`,
    }, '▤ Attach Terminal'))
}

function RunningJobRow(props: { execution: JobExecution; element: string; onRefresh: () => void; isExpanded: boolean; onToggle: () => void }) {
  const ex = props.execution
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

  const header = h('div', { className: 'flex items-center gap-3 flex-wrap' },
    h('span', { className: 'font-mono text-xs break-all flex-1 min-w-0' }, ex.id),
    h('span', { className: `text-xs font-medium px-2 py-0.5 rounded-full shrink-0 ${statusColorClasses(ex.status)}` }, ex.status),
    ex.endpoints && ex.endpoints.length > 0 &&
      h('span', { className: 'font-mono text-xs text-muted-foreground shrink-0' },
        ex.endpoints.map((ep) => `${ep.host}:${ep.port}/${ep.protocol}`).join(', ')),
    h('button', {
      disabled: isStopping,
      onClick: (e: React.MouseEvent) => { e.stopPropagation(); handleStop() },
      className: 'shrink-0 px-2.5 py-1 rounded border text-xs text-destructive border-destructive/40 hover:bg-destructive/10 disabled:opacity-50 transition-colors',
    }, isStopping ? 'Stopping…' : 'Stop'))

  return h(Accordion, { isExpanded: props.isExpanded, onToggle: props.onToggle, header },
    stopError && h('p', { className: 'text-xs text-destructive mb-2' }, stopError),
    containers.length > 0 &&
      h('div', { className: 'space-y-1.5 mb-3' },
        h('div', { className: 'text-xs font-medium text-muted-foreground' }, 'Containers'),
        containers.map((c) => h(ContainerAttachRow, {
          key: c.id,
          element: props.element,
          jobId: ex.id,
          running: ex.status === 'RUNNING',
          container: c,
          label: containers.length > 1 ? `${ex.id}/${c.name}` : ex.id,
        }))),
    Boolean(ex.details) && h(DetailGrid, { obj: ex.details }))
}

export function RunningJobsSection() {
  const [data, setData] = React.useState<{ loading: boolean; providers: ProviderExecutionsResult[]; error: string | null }>({
    loading: true, providers: [], error: null,
  })
  const [expandedIds, setExpandedIds] = React.useState<Set<string>>(new Set())
  const [sectionExpanded, setSectionExpanded] = React.useState(true)

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

  function toggle(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  const header = h('div', { className: 'flex items-center justify-between gap-3' },
    h('h2', { className: 'text-lg font-semibold' }, 'Running Jobs'),
    h('button', {
      onClick: (e: React.MouseEvent) => { e.stopPropagation(); load() },
      disabled: data.loading,
      className: 'flex items-center gap-1.5 px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors disabled:opacity-50',
    }, refreshIcon, data.loading ? ' Refreshing…' : ' Refresh'))

  return h(Accordion, { isExpanded: sectionExpanded, onToggle: () => setSectionExpanded((v) => !v), header },
    data.error && h('p', { className: 'text-xs text-destructive mb-2' }, data.error),
    !data.loading && allExecutions.length === 0 &&
      h('p', { className: 'text-sm text-muted-foreground' }, 'No active jobs found.'),
    allExecutions.length > 0 &&
      h('div', { className: 'space-y-2' },
        allExecutions.map((item) => h('div', { key: `${item.element}:${item.execution.id}` },
          h('div', { className: 'text-xs text-muted-foreground mb-1 font-mono' }, item.element),
          h(RunningJobRow, {
            execution: item.execution,
            element: item.element,
            onRefresh: load,
            isExpanded: expandedIds.has(item.execution.id),
            onToggle: () => toggle(item.execution.id),
          })))))
}
