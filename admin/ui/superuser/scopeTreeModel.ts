/**
 * The scope tree's pure selection rules (issue #52), split from the React component in
 * `ScopeTree.ts` so they are unit-testable without a React runtime (the bundle reads React off
 * `window.React` — the host dashboard provides it, and nothing installs it here).
 */
import type { ProviderScopesResult } from './types'

export const PAGE_SIZE = 10

/** The display label for a resolved scope — `null` rows group under the "default" node. */
export function scopeLabel(scope: string | null): string {
  return scope ?? 'default'
}

/**
 * True when a provider reported exactly one scope and it is the unscoped one — such a provider
 * (e.g. EdgeGap, or an ECS deployment whose families carry no `namazu.conductor:scope` tag) has no
 * meaningful scope level, so the tree renders its rows directly under the provider node instead of
 * a lone nested "default" accordion.
 */
export function isSingleUnscoped(node: ProviderScopesResult): boolean {
  return !node.error && Array.isArray(node.scopes) && node.scopes.length === 1 && node.scopes[0].scope === null
}

/**
 * The rows of one scope, visibility-filtered and paginated. This is the whole row-selection rule:
 * hidden-filter first, then slice the requested page of the *filtered* set.
 */
export function selectVisibleRows<R>(
  rows: R[],
  isVisible: (row: R) => boolean,
  page: number,
): { pageRows: R[]; visibleCount: number; hiddenCount: number; pageCount: number } {
  const visible = rows.filter(isVisible)
  const pageCount = Math.max(1, Math.ceil(visible.length / PAGE_SIZE))
  const safePage = Math.min(page, pageCount - 1)
  return {
    pageRows: visible.slice(safePage * PAGE_SIZE, safePage * PAGE_SIZE + PAGE_SIZE),
    visibleCount: visible.length,
    hiddenCount: rows.length - visible.length,
    pageCount,
  }
}

/**
 * The tree key for an (element, scope) pair. Scope values are provider-native strings (they may
 * themselves contain `:` — Kubernetes job ids do), so the element boundary is a literal `::` and
 * the scope is everything after it, recoverable verbatim.
 */
export function scopeKey(element: string, scope: string | null): string {
  return `${element}::${scope ?? ''}`
}

/** Inverse of [scopeKey]. */
export function parseScopeKey(key: string): { element: string; scope: string | null } {
  const idx = key.indexOf('::')
  const element = key.slice(0, idx)
  const rest = key.slice(idx + 2)
  return { element, scope: rest === '' ? null : rest }
}
