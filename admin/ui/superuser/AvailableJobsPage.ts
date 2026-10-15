import React from 'react'
import { executeJob, fetchProfiles, fetchScopes, getSessionSecret } from './api'
import {
  Accordion, CollapsibleMarkdown, ErrorBoundary, LinkPills, MetadataPills,
  ShowHiddenCheckbox, StatusIndicator, metadataFlag, useShowHidden,
} from './ui'
import { ScopeTree } from './ScopeTree'
import { RunForm, deriveMetadataOverrides } from './RunForm'
import { defaultAdvancedOptions, deriveEnvironment, deriveContainerEnvironment, derivePlacementList, deriveSessionSecretEnv } from './runOptions'
import type { AdvancedOptions } from './runOptions'
import type { JobProfile } from './types'

const h = React.createElement

/** Shallow-merges two `containerEnvironment` maps; [b]'s entries win on container- and key-level
 * collisions (the session secret must not be clobbered by an empty side). */
function mergeContainerEnv(
  a: Record<string, Record<string, string>> | undefined,
  b: Record<string, Record<string, string>> | undefined
): Record<string, Record<string, string>> {
  const out: Record<string, Record<string, string>> = {}
  for (const [container, env] of Object.entries(a ?? {})) out[container] = { ...env }
  for (const [container, env] of Object.entries(b ?? {})) out[container] = { ...(out[container] ?? {}), ...env }
  return out
}

interface FlatProfile {
  element: string
  jobSetLabel: string
  profile: JobProfile
}

function ProfileRow(props: { item: FlatProfile; isExpanded: boolean; onToggle: () => void }) {
  const { element, jobSetLabel, profile } = props.item
  const [detailsExpanded, setDetailsExpanded] = React.useState(false)
  const [advancedExpanded, setAdvancedExpanded] = React.useState(false)
  const [starting, setStarting] = React.useState(false)
  const [startError, setStartError] = React.useState<string | null>(null)
  const [startedId, setStartedId] = React.useState<string | null>(null)
  const isTerminalJob = Boolean(profile.terminalJob)
  // Cosmetic namazu.conductor flags — see the vocabulary in AGENTS.md. `agent` gets the 🤖 badge
  // (a terminal job that is an agent, not an ordinary terminal job); `hidden` rows render only
  // under "Show hidden", and badge themselves when they do so the operator knows why they were
  // formerly invisible.
  const isAgent = metadataFlag(profile.metadata, 'agent')
  const isHiddenProfile = metadataFlag(profile.metadata, 'hidden')
  const sessionSecretEnvVar = typeof profile.sessionSecretEnv === 'string' && profile.sessionSecretEnv.trim()
    ? profile.sessionSecretEnv.trim()
    : undefined
  const [advanced, setAdvanced] = React.useState<AdvancedOptions>(() =>
    defaultAdvancedOptions(isTerminalJob, Boolean(profile.sessionSecretEnabledByDefault)))
  const hasProfileMetadata = Object.keys(profile.metadata ?? {}).some((k) => k.trim())

  function handleStart() {
    setStarting(true); setStartError(null); setStartedId(null)

    const { environment: secretEnv, containerEnvironment: secretContainerEnv } = deriveSessionSecretEnv(
      advanced.injectSessionSecret,
      advanced.sessionSecretContainers,
      sessionSecretEnvVar ?? '',
      getSessionSecret() ?? null)

    // Undefined (omitted) when the operator added nothing — mirrors command/metadata, keeps the
    // request body honest about what was overridden. Secret entries win on key collision, as they
    // did when this was a single flat map.
    const environment = { ...deriveEnvironment(advanced.env), ...secretEnv }
    const containerEnvironment = mergeContainerEnv(deriveContainerEnvironment(advanced.env), secretContainerEnv)

    executeJob({
      element,
      profileId: profile.id,
      args: advanced.args.map((s) => s.trim()).filter(Boolean),
      // undefined (omitted), not [], when never touched — lets the server's "imply terminal command"
      // default (request.command ?? TERMINAL_COMMAND_SUGGESTION) still kick in for terminal-job
      // profiles run via the simple path.
      command: advanced.command.length > 0 ? advanced.command.map((s) => s.trim()).filter(Boolean) : undefined,
      environment,
      containerEnvironment: Object.keys(containerEnvironment).length > 0 ? containerEnvironment : undefined,
      placement: derivePlacementList(advanced.placement),
      tty: advanced.tty,
      // undefined when the operator added no overrides — see deriveMetadataOverrides.
      metadata: deriveMetadataOverrides(advanced.metadata),
    })
      .then((execution) => { setStarting(false); setStartedId(execution.id) })
      .catch((e: Error) => { setStartError(e.message || 'Failed to start job'); setStarting(false) })
  }

  const detailKeys = Object.keys(profile).filter((k) =>
    !['id', 'name', 'description', 'terminalJob', 'containers', 'metadata', 'sessionSecretEnv', 'sessionSecretEnabledByDefault'].includes(k))

  const header = h('div', { className: 'flex items-center gap-3 flex-wrap' },
    isAgent && h('span', { title: 'Agent job', className: 'text-base' }, '🤖'),
    h('span', { className: 'font-mono text-sm font-medium' }, profile.name?.trim() || profile.id),
    h('span', {
      className: 'text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary',
      title: jobSetLabel !== element ? `Element: ${element}` : undefined,
    }, jobSetLabel),
    isTerminalJob && h('span', { className: 'text-xs px-2 py-0.5 rounded-full bg-muted text-muted-foreground' }, 'terminal job'),
    isHiddenProfile && h('span', {
      className: 'text-xs px-2 py-0.5 rounded-full bg-muted text-muted-foreground italic',
      title: 'Declared hidden by the profile; only visible while "Show hidden" is checked',
    }, 'hidden'),
    h(LinkPills, { metadata: profile.metadata }),
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
    // Declared metadata, verbatim. Capped at 4 with a "+N more" affordance rather than a full
    // key/value table: on Kubernetes this is every annotation on the PodTemplate, which is routinely
    // a dozen entries and would bury the description and Run button.
    hasProfileMetadata && h('div', { className: 'mb-3' },
      h('div', { className: 'text-xs font-medium text-muted-foreground mb-1' }, 'Metadata'),
      h(MetadataPills, { metadata: profile.metadata, max: 4 })),
    detailKeys.length > 0 &&
      h('div', { className: 'mb-3' },
        h(Accordion, {
          isExpanded: detailsExpanded,
          onToggle: () => setDetailsExpanded((v) => !v),
          header: h('span', { className: 'text-sm font-medium' }, 'Details'),
        },
          h('div', { className: 'grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5' },
            detailKeys.flatMap((k) => {
              const val = profile[k]
              const display = val == null ? h('span', { className: 'text-muted-foreground' }, '—')
                : typeof val === 'object' ? JSON.stringify(val) : String(val)
              return [
                h('span', { key: `${k}_k`, className: 'text-xs font-medium text-muted-foreground whitespace-nowrap' }, k),
                h('span', { key: `${k}_v`, className: 'text-xs font-mono break-all' }, display),
              ]
            })))),
    h('div', { className: 'border-t pt-3' },
      h(Accordion, {
        isExpanded: advancedExpanded,
        onToggle: () => setAdvancedExpanded((v) => !v),
        header: h('span', { className: 'text-sm font-medium' }, 'Advanced Run Options'),
      },
        h(RunForm, { value: advanced, onChange: setAdvanced, sessionSecretEnvVar, containers: profile.containers }))))
}

export function AvailableJobsPage() {
  const [scopesState, setScopesState] = React.useState<{ loading: boolean; status: string | null; error: string | null }>({
    loading: true, status: null, error: null,
  })
  const [expandedId, setExpandedId] = React.useState<string | null>(null)
  const [showHidden, setShowHidden] = useShowHidden()

  const renderRow = React.useCallback((row: JobProfile, ctx: { element: string; jobSetName?: string | null }) =>
    h(ErrorBoundary, { key: `${ctx.element}:${row.id}` },
      h(ProfileRow, {
        item: { element: ctx.element, jobSetLabel: ctx.jobSetName ?? ctx.element, profile: row },
        isExpanded: expandedId === `${ctx.element}:${row.id}`,
        onToggle: () => setExpandedId((v) => (v === `${ctx.element}:${row.id}` ? null : `${ctx.element}:${row.id}`)),
      })), [expandedId])

  return h('div', { className: 'p-6 space-y-6' },
    h('div', { className: 'flex items-center justify-between' },
      h('h1', { className: 'text-2xl font-bold' }, 'Available Jobs & Services'),
      h('div', { className: 'flex items-center gap-4' },
        h(ShowHiddenCheckbox, { showHidden, onChange: setShowHidden }),
        h(StatusIndicator, { loading: scopesState.loading, status: scopesState.status, error: scopesState.error }))),
    h(ScopeTree<JobProfile>, {
      fetchScopes,
      // The tree needs a uniform per-provider row shape; normalize the profiles response into it.
      fetchScopeRows: async (scope) => {
        const body = await fetchProfiles(scope)
        return {
          status: body.status,
          providers: body.providers.map((p) => ({
            element: p.element,
            error: p.error,
            rows: p.profiles ?? null,
            jobSetName: p.jobSetName ?? null,
          })),
        }
      },
      renderRow,
      rowKey: (row, ctx) => `${ctx.element}:${row.id}`,
      isVisible: (profile) => showHidden || !metadataFlag(profile.metadata, 'hidden'),
      emptyMessage: 'No job profiles available.',
      onScopesState: setScopesState,
    }))
}
