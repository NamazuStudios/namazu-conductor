import React from 'react'
import { fetchJobs, fetchScopes, stopJob } from './api'
import {
  Accordion, CollapsibleMarkdown, conductorKeySuffix, containerMetadataFlag, DetailGrid, LinkPills,
  MetadataPills, ShowHiddenCheckbox, metadataFlag, useShowHidden,
} from './ui'
import { ScopeTree } from './ScopeTree'
import { terminalSessionManager } from './terminal'
import type { JobExecution } from './types'

const h = React.createElement

function statusColorClasses(status: string): string {
  if (status === 'RUNNING') return 'text-green-700 bg-green-50'
  if (status === 'PENDING') return 'text-yellow-700 bg-yellow-50'
  if (status === 'FAILED') return 'text-destructive bg-destructive/10'
  return 'text-muted-foreground bg-muted'
}

/**
 * Per-container "Launch Terminal" row, with an optional command override — like `kubectl exec` vs
 * `kubectl attach`. Left blank, attaches with the provider's default (e.g. a shell); a naive
 * whitespace-split of whatever's typed otherwise, which is an intentional simplification for this
 * advanced/power-user path (not full shell quoting/parsing).
 */
function ContainerAttachRow(props: { element: string; jobId: string; running: boolean; container: { id: string; name: string; primary: boolean; defaultCommand?: string[] }; label: string; isAgent?: boolean }) {
  const { container: c } = props
  const [command, setCommand] = React.useState(() => (c.defaultCommand ?? []).join(' '))

  function handleLaunch() {
    const tokens = command.trim().split(/\s+/).filter(Boolean)
    terminalSessionManager.open(props.element, props.jobId, c.primary ? null : c.id, props.label, tokens.length > 0 ? tokens : undefined)
  }

  return h('div', { className: 'flex items-center gap-2 text-xs' },
    props.isAgent && h('span', { title: 'Agent container', className: 'text-base shrink-0' }, '🤖'),
    h('span', { className: 'font-mono flex-1' }, c.name, c.primary && h('span', { className: 'ml-1.5 text-muted-foreground' }, '(primary)')),
    h('input', {
      value: command,
      onChange: (e: React.ChangeEvent<HTMLInputElement>) => setCommand(e.target.value),
      onKeyDown: (e: React.KeyboardEvent<HTMLInputElement>) => { if (e.key === 'Enter' && props.running) handleLaunch() },
      placeholder: '/bin/sh (optional)',
      className: 'w-40 rounded border bg-background px-1.5 py-0.5 text-xs font-mono focus:outline-none focus:ring-1 focus:ring-primary',
      title: 'Command to exec instead of the container’s default shell',
    }),
    h('button', {
      disabled: !props.running,
      className: 'shrink-0 px-2.5 py-1 rounded border text-xs hover:bg-muted disabled:opacity-50 transition-colors',
      onClick: handleLaunch,
      title: `Launch a terminal for container '${c.name}'`,
    }, '🚀 Launch Terminal'))
}

function RunningJobRow(props: { execution: JobExecution; element: string; onRefresh: () => void; isExpanded: boolean; onToggle: () => void; showHidden: boolean }) {
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

  const metadataEntries = Object.entries(ex.metadata ?? {}).filter(([k]) => k.trim())

  // The profile's Markdown description (namazu.conductor/description — either separator form),
  // carried verbatim in the execution's metadata by every provider, so an operator expanding a
  // running job sees what the job is without going back to the profile list. Per-run overrides and
  // runtime annotation updates surface here through the same read-back.
  const description = Object.entries(ex.metadata ?? {})
    .find(([k]) => conductorKeySuffix(k) === 'description')?.[1]

  // Container-scoped cosmetic flags — namazu.conductor/hidden.{container} and
  // namazu.conductor/agent.{container} (see AGENTS.md). Hidden attach rows vanish unless the
  // operator opted into Show hidden — the same toggle as hidden jobs, so one opt-in reveals both.
  const containers = (ex.containers ?? []).filter((c) =>
    props.showHidden || !containerMetadataFlag(ex.metadata, 'hidden', c.name))
  const containerAgents = new Set((ex.containers ?? [])
    .filter((c) => containerMetadataFlag(ex.metadata, 'agent', c.name))
    .map((c) => c.name))
  const isAgent = metadataFlag(ex.metadata, 'agent')

  const header = h('div', { className: 'flex items-center gap-3 flex-wrap' },
    isAgent && h('span', { title: 'Agent job', className: 'text-base shrink-0' }, '🤖'),
    h('span', { className: 'font-mono text-xs break-all flex-1 min-w-0' }, ex.id),
    h('span', { className: `text-xs font-medium px-2 py-0.5 rounded-full shrink-0 ${statusColorClasses(ex.status)}` }, ex.status),
    h(LinkPills, { metadata: ex.metadata }),
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
    description && h('div', { className: 'mb-3' },
      h(CollapsibleMarkdown, { markdown: description, label: 'Description' })),
    // What the workload actually carries, not what was requested — see JobExecution.metadata in the
    // api module. Only rendered when non-empty, since most executions declare nothing.
    metadataEntries.length > 0 &&
      h('div', { className: 'mb-3' },
        h('div', { className: 'text-xs font-medium text-muted-foreground mb-1' }, 'Metadata'),
        h(MetadataPills, { metadata: ex.metadata })),
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
          isAgent: containerAgents.has(c.name),
        }))),
    Boolean(ex.details) && h(DetailGrid, { obj: ex.details }))
}

export function RunningJobsSection() {
  const [expandedIds, setExpandedIds] = React.useState<Set<string>>(new Set())
  const [sectionExpanded, setSectionExpanded] = React.useState(true)
  const [scopesState, setScopesState] = React.useState<{ loading: boolean; status: string | null; error: string | null }>({
    loading: true, status: null, error: null,
  })
  const [refreshToken, setRefreshToken] = React.useState(0)
  const [showHidden, setShowHidden] = useShowHidden()

  // Poll the (expanded) scopes every 10s by bumping the tree's refresh token; the tree reloads
  // the scope list and every open scope's rows, never the full unfiltered listing.
  React.useEffect(() => {
    const interval = setInterval(() => setRefreshToken((v) => v + 1), 10000)
    return () => clearInterval(interval)
  }, [])

  const refresh = React.useCallback(() => setRefreshToken((v) => v + 1), [])

  const renderRow = React.useCallback((row: JobExecution, ctx: { element: string; jobSetName?: string | null }) =>
    h(RunningJobRow, {
      execution: row,
      element: ctx.element,
      onRefresh: refresh,
      isExpanded: expandedIds.has(row.id),
      onToggle: () => toggle(row.id),
      showHidden,
    }), [expandedIds, refresh, showHidden])

  function toggle(id: string) {
    setExpandedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id); else next.add(id)
      return next
    })
  }

  const refreshIcon: React.ReactNode = scopesState.loading
    ? h('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse inline-block' })
    : '↺'

  const header = h('div', { className: 'flex items-center justify-between gap-3' },
    h('div', { className: 'flex items-center gap-4' },
      h('h2', { className: 'text-lg font-semibold' }, 'Running Jobs'),
      h(ShowHiddenCheckbox, { showHidden, onChange: setShowHidden })),
    h('button', {
      onClick: (e: React.MouseEvent) => { e.stopPropagation(); refresh() },
      disabled: scopesState.loading,
      className: 'flex items-center gap-1.5 px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors disabled:opacity-50',
    }, refreshIcon, scopesState.loading ? ' Refreshing…' : ' Refresh'))

  return h(Accordion, { isExpanded: sectionExpanded, onToggle: () => setSectionExpanded((v) => !v), header },
    h(ScopeTree<JobExecution>, {
      fetchScopes,
      fetchScopeRows: async (scope) => {
        const body = await fetchJobs(scope)
        return {
          status: body.status,
          providers: body.providers.map((p) => ({
            element: p.element,
            error: p.error,
            rows: p.executions ?? null,
            jobSetName: p.jobSetName ?? null,
          })),
        }
      },
      renderRow,
      rowKey: (row, ctx) => `${ctx.element}:${row.id}`,
      isVisible: (execution) => showHidden || !metadataFlag(execution.metadata, 'hidden'),
      emptyMessage: 'No active jobs found.',
      refreshToken,
      onScopesState: setScopesState,
    }))
}
