import React from 'react'
import { executeJob, fetchProfiles } from './api'
import { Accordion, CollapsibleMarkdown, ErrorBoundary, MarkdownBlock, Pagination, StatusIndicator } from './ui'
import { RunForm, defaultAdvancedOptions, derivePlacementList } from './RunForm'
import type { AdvancedOptions } from './RunForm'
import type { JobProfile, ProviderProfilesResult } from './types'

const h = React.createElement
const PAGE_SIZE = 10

interface FlatProfile {
  element: string
  jobSetLabel: string
  profile: JobProfile
}

function ProfileRow(props: { item: FlatProfile; isExpanded: boolean; onToggle: () => void }) {
  const { element, jobSetLabel, profile } = props.item
  const [advancedExpanded, setAdvancedExpanded] = React.useState(false)
  const [starting, setStarting] = React.useState(false)
  const [startError, setStartError] = React.useState<string | null>(null)
  const [startedId, setStartedId] = React.useState<string | null>(null)
  const isTerminalJob = Boolean(profile.terminalJob)
  const [advanced, setAdvanced] = React.useState<AdvancedOptions>(() => defaultAdvancedOptions(isTerminalJob))

  function handleStart() {
    setStarting(true); setStartError(null); setStartedId(null)

    const environment: Record<string, string> = {}
    advanced.env.forEach((pair) => { if (pair.key.trim()) environment[pair.key.trim()] = pair.value })

    executeJob({
      element,
      profileId: profile.id,
      args: advanced.args.map((s) => s.trim()).filter(Boolean),
      // undefined (omitted), not [], when never touched — lets the server's "imply terminal command"
      // default (request.command ?? TERMINAL_COMMAND_SUGGESTION) still kick in for terminal-job
      // profiles run via the simple path.
      command: advanced.command.length > 0 ? advanced.command.map((s) => s.trim()).filter(Boolean) : undefined,
      environment,
      placement: derivePlacementList(advanced.placement),
      tty: advanced.tty,
    })
      .then((execution) => { setStarting(false); setStartedId(execution.id) })
      .catch((e: Error) => { setStartError(e.message || 'Failed to start job'); setStarting(false) })
  }

  const detailKeys = Object.keys(profile).filter((k) => !['id', 'description', 'terminalJob', 'containers'].includes(k))

  const header = h('div', { className: 'flex items-center gap-3 flex-wrap' },
    h('span', { className: 'font-mono text-sm font-medium' }, profile.id),
    h('span', {
      className: 'text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary',
      title: jobSetLabel !== element ? `Element: ${element}` : undefined,
    }, jobSetLabel),
    isTerminalJob && h('span', { className: 'text-xs px-2 py-0.5 rounded-full bg-muted text-muted-foreground' }, 'terminal job'),
    h('span', { className: 'flex-1' }),
    h('button', {
      disabled: starting,
      onClick: (e: React.MouseEvent) => { e.stopPropagation(); handleStart() },
      className: 'px-3 py-1 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50 transition-colors',
    }, starting ? 'Starting…' : 'Run ▶'))

  return h(Accordion, { isExpanded: props.isExpanded, onToggle: props.onToggle, header },
    startError && h('p', { className: 'text-xs text-destructive mb-2' }, startError),
    startedId && h('p', { className: 'text-xs text-green-700 mb-2 font-mono' },
      `Started ✓ ${startedId} — see Running Jobs & Services.`),
    profile.description && h('div', { className: 'mb-3' },
      h(CollapsibleMarkdown, { markdown: profile.description, label: 'Description' })),
    detailKeys.length > 0 &&
      h('div', { className: 'grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5 mb-3' },
        detailKeys.flatMap((k) => {
          const val = profile[k]
          const display = val == null ? h('span', { className: 'text-muted-foreground' }, '—')
            : typeof val === 'object' ? JSON.stringify(val) : String(val)
          return [
            h('span', { key: `${k}_k`, className: 'text-xs font-medium text-muted-foreground whitespace-nowrap' }, k),
            h('span', { key: `${k}_v`, className: 'text-xs font-mono break-all' }, display),
          ]
        })),
    h('div', { className: 'border-t pt-3' },
      h(Accordion, {
        isExpanded: advancedExpanded,
        onToggle: () => setAdvancedExpanded((v) => !v),
        header: h('span', { className: 'text-sm font-medium' }, 'Advanced Run Options'),
      },
        h(RunForm, { value: advanced, onChange: setAdvanced }))))
}

export function AvailableJobsPage() {
  const [data, setData] = React.useState<{ loading: boolean; status: string | null; error: string | null; providers: ProviderProfilesResult[] }>({
    loading: true, status: null, error: null, providers: [],
  })
  const [page, setPage] = React.useState(0)
  const [expandedId, setExpandedId] = React.useState<string | null>(null)

  React.useEffect(() => {
    fetchProfiles()
      .then((body) => setData({ loading: false, status: body.status, error: null, providers: body.providers || [] }))
      .catch((e: Error) => setData({ loading: false, status: 'error', error: e.message, providers: [] }))
  }, [])

  const flat: FlatProfile[] = data.providers.flatMap((p) =>
    (p.profiles ?? []).map((profile) => ({ element: p.element, jobSetLabel: p.jobSetName ?? p.element, profile })))

  const providerErrors = data.providers.filter((p) => p.error)

  const providerNotes = data.providers.filter((p) => p.jobSetDescription)

  const pageCount = Math.max(1, Math.ceil(flat.length / PAGE_SIZE))
  const pageItems = flat.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE)

  return h('div', { className: 'p-6 space-y-6' },
    h('div', { className: 'flex items-center justify-between' },
      h('h1', { className: 'text-2xl font-bold' }, 'Available Jobs & Services'),
      h(StatusIndicator, { loading: data.loading, status: data.status, error: data.error })),
    !data.loading && data.status === 'error' && flat.length === 0 &&
      h('div', { className: 'rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive' },
        data.error || 'Failed to load profiles.'),
    providerErrors.map((p) => h('div', {
      key: p.element,
      className: 'rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive',
    }, `${p.element}: ${p.error}`)),
    providerNotes.map((p) => h('div', {
      key: `jobset-${p.element}`,
      className: 'rounded-lg border bg-muted/30 p-3',
    },
      h('div', { className: 'text-xs font-semibold mb-1' }, p.jobSetName ?? p.element),
      h(MarkdownBlock, { markdown: p.jobSetDescription as string, className: 'text-sm' }))),
    !data.loading && flat.length === 0 && providerErrors.length === 0 &&
      h('p', { className: 'text-sm text-muted-foreground' }, 'No job profiles available.'),
    flat.length > 0 &&
      h('div', { className: 'space-y-2' },
        pageItems.map((item) => h(ErrorBoundary, { key: `${item.element}:${item.profile.id}` },
          h(ProfileRow, {
            item,
            isExpanded: expandedId === item.profile.id,
            onToggle: () => setExpandedId((v) => (v === item.profile.id ? null : item.profile.id)),
          })))),
    h(Pagination, { page, pageCount, onChange: setPage }))
}
