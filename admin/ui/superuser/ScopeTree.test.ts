/** Unit tests for the scope-tree selection rules (issue #52: tree-based scope/namespace navigation). */

import { describe, expect, it } from 'vitest'
import { PAGE_SIZE, isSingleUnscoped, scopeLabel, scopeKey, parseScopeKey, selectVisibleRows } from './scopeTreeModel'
import type { ProviderScopesResult } from './types'

describe('scopeLabel', () => {
  it('labels null scope as "default"', () => {
    expect(scopeLabel(null)).toBe('default')
  })

  it('labels a named scope verbatim', () => {
    expect(scopeLabel('tenant-a')).toBe('tenant-a')
  })
})

describe('scopeKey / parseScopeKey round-trip', () => {
  it('round-trips a null scope', () => {
    expect(parseScopeKey(scopeKey('elem', null))).toEqual({ element: 'elem', scope: null })
  })

  it('round-trips a scope containing colons (Kubernetes ids are colon-delimited)', () => {
    expect(parseScopeKey(scopeKey('elem', 'ns:x:y'))).toEqual({ element: 'elem', scope: 'ns:x:y' })
  })
})

describe('isSingleUnscoped', () => {
  it('is true for exactly one null scope', () => {
    expect(isSingleUnscoped({ element: 'e', scopes: [{ scope: null, profiles: 1, executions: 0 }] })).toBe(true)
  })

  it('is false for a named scope, multiple scopes, an error, or missing scopes', () => {
    expect(isSingleUnscoped({ element: 'e', scopes: [{ scope: 'ns', profiles: 1, executions: 0 }] })).toBe(false)
    expect(isSingleUnscoped({
      element: 'e',
      scopes: [{ scope: null, profiles: 1, executions: 0 }, { scope: 'ns', profiles: 0, executions: 0 }],
    })).toBe(false)
    expect(isSingleUnscoped({ element: 'e', scopes: [], error: null })).toBe(false)
    expect(isSingleUnscoped({ element: 'e', scopes: [{ scope: null, profiles: 0, executions: 0 }], error: 'boom' })).toBe(false)
    expect(isSingleUnscoped({ element: 'e', scopes: null, error: 'boom' })).toBe(false)
    expect(isSingleUnscoped({ element: 'e', scopes: undefined })).toBe(false)
  })
})

describe('selectVisibleRows', () => {
  // Two full pages' worth: evens 2..PAGE_SIZE*4 filtered from 1..PAGE_SIZE*4.
  const rows = Array.from({ length: PAGE_SIZE * 4 }, (_, i) => i + 1)
  const evens = rows.filter((n) => n % 2 === 0)
  const evenOnly = (n: number) => n % 2 === 0

  it('filters before paginating so a hidden row never occupies a page slot', () => {
    const { pageRows, visibleCount, hiddenCount, pageCount } = selectVisibleRows(rows, evenOnly, 0)
    expect(pageRows).toEqual(evens.slice(0, PAGE_SIZE))
    expect(visibleCount).toBe(evens.length)
    expect(hiddenCount).toBe(rows.length - evens.length)
    expect(pageCount).toBe(2)
  })

  it('serves the second page of the filtered set', () => {
    expect(selectVisibleRows(rows, evenOnly, 1).pageRows).toEqual(evens.slice(PAGE_SIZE))
  })

  it('clamps an out-of-range page to the last', () => {
    expect(selectVisibleRows(rows, evenOnly, 99).pageRows).toEqual(evens.slice(PAGE_SIZE))
  })

  it('renders a single empty page when nothing is visible', () => {
    const { pageRows, pageCount } = selectVisibleRows(rows, () => false, 0)
    expect(pageRows).toEqual([])
    expect(pageCount).toBe(1)
  })

  it('defaults to one page when everything fits', () => {
    expect(selectVisibleRows([1, 2, 3], () => true, 0)).toEqual({
      pageRows: [1, 2, 3],
      visibleCount: 3,
      hiddenCount: 0,
      pageCount: 1,
    })
  })

  it('matches the scope tree against ProviderScopesResult rows (smoke: types line up)', () => {
    const provider: ProviderScopesResult = { element: 'e', scopes: [{ scope: 'ns', profiles: 1, executions: 0 }] }
    const { pageRows } = selectVisibleRows([provider], () => true, 0)
    expect(pageRows[0].element).toBe('e')
  })
})
