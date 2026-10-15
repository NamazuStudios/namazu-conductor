import React from 'react'
import { Accordion, MarkdownBlock, Pagination } from './ui'
import {
  PAGE_SIZE, isSingleUnscoped, parseScopeKey, scopeKey, scopeLabel, selectVisibleRows,
} from './scopeTreeModel'
import type { ProviderScopesResult } from './types'

const h = React.createElement

export { PAGE_SIZE, isSingleUnscoped, scopeKey, scopeLabel, selectVisibleRows }

/** GET /scopes response body. */
export interface ScopesBody {
  status: string
  providers: ProviderScopesResult[]
}

/** Normalized per-provider row set the tree renders — the pages adapt their provider results to this. */
export interface ScopeRowsProvider<R> {
  element: string
  error?: string | null
  rows?: R[] | null
  jobSetName?: string | null
}

export interface ScopeRowsBody<R> {
  status: string
  providers: ScopeRowsProvider<R>[]
}

interface ScopeRowsState<R> {
  loading: boolean
  error: string | null
  providers: ScopeRowsProvider<R>[]
}

/**
 * The scope tree (issue #52): Provider element → scope → rows. The tree itself is built from the
 * cheap `GET /scopes` call; a scope's rows are fetched on demand when its node is first expanded
 * (server-side `?scope=` filtering on `/profiles` / `/jobs`), so the panel never renders — or even
 * fetches — one global unbounded list. Refresh/polling only touches the scopes the operator has
 * actually opened.
 *
 * Row rendering is delegated to the caller via [props.renderRow]; visibility filtering (e.g. the
 * "Show hidden" opt-in) via [props.isVisible], applied before pagination so a hidden row never
 * occupies a page slot.
 */
export function ScopeTree<R>(props: {
  fetchScopes: () => Promise<ScopesBody>
  fetchScopeRows: (scope: string | null) => Promise<ScopeRowsBody<R>>
  renderRow: (row: R, ctx: { element: string; scope: string | null; jobSetName?: string | null }) => React.ReactNode
  rowKey: (row: R, ctx: { element: string; scope: string | null }) => string
  isVisible?: (row: R) => boolean
  emptyMessage: string
  /** Bump to force a reload of the scope tree and every expanded scope's rows (refresh button, polling). */
  refreshToken?: number
  /** Reports the scope fetch's state so the page header can render a StatusIndicator. */
  onScopesState?: (state: { loading: boolean; status: string | null; error: string | null }) => void
}) {

  const [scopes, setScopes] = React.useState<LoadedScopes>({ loading: true, error: null, status: null, providers: [] })
  const [rowsByScope, setRowsByScope] = React.useState<Record<string, ScopeRowsState<R>>>({})
  const [expandedProviders, setExpandedProviders] = React.useState<Set<string>>(new Set())
  const [expandedScopes, setExpandedScopes] = React.useState<Set<string>>(new Set())
  const [pages, setPages] = React.useState<Record<string, number>>({})

  const { fetchScopes, fetchScopeRows, isVisible } = props

  // Latest fetcher without retriggering the load effect when the pages pass a new closure.
  const fetchRowsRef = React.useRef(fetchScopeRows)
  fetchRowsRef.current = fetchScopeRows

  // Mirror of expandedScopes so the refresh effect can re-fetch open scopes without adding them
  // (and their row payloads) to the effect's dependency set.
  const expandedScopesRef = React.useRef(expandedScopes)
  expandedScopesRef.current = expandedScopes

  function fetchRowsInto(key: string) {
    const { scope } = parseScopeKey(key)
    setRowsByScope((current) => ({ ...current, [key]: { loading: true, error: null, providers: current[key]?.providers ?? [] } }))
    fetchRowsRef.current(scope)
      .then((body) => setRowsByScope((current) => ({
        ...current,
        [key]: { loading: false, error: null, providers: body.providers || [] },
      })))
      .catch((e: Error) => setRowsByScope((current) => ({
        ...current,
        [key]: { loading: false, error: e.message, providers: [] },
      })))
  }

  React.useEffect(() => {
    props.onScopesState?.({ loading: true, status: null, error: null })
    fetchScopes()
      .then((body) => {
        const providers = body.providers || []
        setScopes({ loading: false, error: null, status: body.status, providers })
        // Providers start expanded — they are few, and expanding them is what makes the scope
        // level (the thing the operator drills into) visible without extra clicks.
        setExpandedProviders(new Set(providers.map((p) => p.element)))
        props.onScopesState?.({ loading: false, status: body.status, error: null })
        // Single-unscoped providers render their rows inline under an already-expanded node —
        // fetch them now, on the same first-expand path the toggles use.
        providers.filter(isSingleUnscoped).forEach((p) => fetchRowsInto(scopeKey(p.element, null)))
      })
      .catch((e: Error) => {
        setScopes({ loading: false, error: e.message, status: 'error', providers: [] })
        props.onScopesState?.({ loading: false, status: 'error', error: e.message })
      })

    // Reload every already-expanded scope's rows too (refresh button / polling).
    expandedScopesRef.current.forEach(fetchRowsInto)
  }, [fetchScopes, props.refreshToken])

  function toggleScope(key: string) {
    const expanding = !expandedScopes.has(key)
    setExpandedScopes((current) => {
      const next = new Set(current)
      if (expanding) next.add(key); else next.delete(key)
      return next
    })
    // Load on first expand; re-expanding a previously-loaded scope shows the cached rows
    // until the next explicit refresh/poll.
    if (expanding && !rowsByScope[key]) fetchRowsInto(key)
  }

  function toggleProvider(element: string, singleUnscoped: boolean) {
    const expanding = !expandedProviders.has(element)
    setExpandedProviders((current) => {
      const next = new Set(current)
      if (expanding) next.add(element); else next.delete(element)
      return next
    })
    // A single-unscoped provider renders its (sole) scope's rows inline — same fetch path.
    if (expanding && singleUnscoped && !rowsByScope[scopeKey(element, null)]) fetchRowsInto(scopeKey(element, null))
  }

  function setPage(key: string, page: number) {
    setPages((current) => ({ ...current, [key]: page }))
  }

  function renderScopeRows(element: string, scope: string | null): React.ReactNode {
    const key = scopeKey(element, scope)
    const state = rowsByScope[key]
    if (!state || state.loading) return h('p', { className: 'text-xs text-muted-foreground py-2' }, 'Loading…')
    if (state.error) return h('p', { className: 'text-xs text-destructive py-2' }, state.error)

    // A provider that failed for this scope contributes its error line; the rest contribute rows.
    const errorLines = state.providers
      .filter((p) => p.element === element && p.error)
      .map((p) => `${p.element}: ${p.error}`)
    const rows = state.providers
      .filter((p) => p.element === element && !p.error)
      .flatMap((p) => p.rows ?? [])

    if (rows.length === 0 && errorLines.length === 0) {
      return h('p', { className: 'text-sm text-muted-foreground py-2' }, props.emptyMessage)
    }

    const { pageRows, hiddenCount, pageCount } = selectVisibleRows(rows, (r) => (isVisible?.(r) ?? true), pages[key] ?? 0)
    const page = pages[key] ?? 0

    return h('div', { className: 'space-y-2' },
      hiddenCount > 0 && h('p', { className: 'text-xs text-muted-foreground' }, `${hiddenCount} hidden`),
      errorLines.map((line) => h('div', {
        key: line,
        className: 'rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive',
      }, line)),
      pageRows.map((row) => h('div', { key: props.rowKey(row, { element, scope }) },
        props.renderRow(row, { element, scope, jobSetName: state.providers.find((p) => p.element === element)?.jobSetName }))),
      h(Pagination, { page, pageCount, onChange: (p: number) => setPage(key, p) }))
  }

  return h('div', { className: 'space-y-2' },
    scopes.loading && h('p', { className: 'text-sm text-muted-foreground' }, 'Loading scopes…'),
    scopes.error && h('div', { className: 'rounded-lg border border-destructive/50 bg-destructive/10 p-4 text-sm text-destructive' },
      scopes.error),
    !scopes.loading && scopes.providers.map((node) => {
      const singleUnscoped = isSingleUnscoped(node)
      const providerExpanded = expandedProviders.has(node.element)
      return h('div', { key: node.element },
        h(Accordion, {
          isExpanded: providerExpanded,
          onToggle: () => toggleProvider(node.element, singleUnscoped),
          header: h('div', { className: 'flex items-center gap-3' },
            h('span', { className: 'text-sm font-semibold' }, node.jobSetName ?? node.element)),
        },
          node.jobSetDescription && h('div', { className: 'mb-3 rounded border bg-muted/30 p-3' },
            h('div', { className: 'text-xs font-semibold mb-1' }, node.jobSetName ?? node.element),
            h(MarkdownBlock, { markdown: node.jobSetDescription as string, className: 'text-sm' })),
          node.scopes == null
            ? h('div', { className: 'rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive' },
                `${node.element}: ${node.error ?? 'No scope information'}`)
            : singleUnscoped
              ? renderScopeRows(node.element, null)
              : h('div', { className: 'space-y-2' },
                  node.scopes.map((sc) => {
                    const key = scopeKey(node.element, sc.scope)
                    const scopeExpanded = expandedScopes.has(key)
                    return h(Accordion, {
                      key,
                      isExpanded: scopeExpanded,
                      onToggle: () => toggleScope(key),
                      header: h('div', { className: 'flex items-center gap-3' },
                        h('span', { className: 'font-mono text-sm' }, scopeLabel(sc.scope)),
                        h('span', { className: 'text-xs text-muted-foreground' },
                          [sc.profiles > 0 && `${sc.profiles} profile${sc.profiles === 1 ? '' : 's'}`,
                           sc.executions > 0 && `${sc.executions} running`].filter(Boolean).join(', '))),
                    },
                      scopeExpanded ? renderScopeRows(node.element, sc.scope) : null)
                  }),
                  node.scopes.length === 0 &&
                    h('p', { className: 'text-sm text-muted-foreground py-2' }, props.emptyMessage)))
      )
    }))
}

interface LoadedScopes {
  loading: boolean
  error: string | null
  status: string | null
  providers: ProviderScopesResult[]
}
