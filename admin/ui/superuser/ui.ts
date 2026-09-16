import React from 'react'
import type { JobExecution } from './types'

const h = React.createElement

/**
 * Catches a render error in its subtree and shows an inline message instead of blanking the whole
 * page — e.g. one malformed profile's Markdown description shouldn't take down the entire list.
 */
export class ErrorBoundary extends React.Component<{ children?: React.ReactNode }, { error: Error | null }> {
  constructor(props: { children?: React.ReactNode }) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  render() {
    if (this.state.error) {
      return h('div', { className: 'rounded-lg border border-destructive/50 bg-destructive/10 p-3 text-xs text-destructive' },
        `Failed to render: ${this.state.error.message}`)
    }
    return this.props.children
  }
}

export function Accordion(props: {
  isExpanded: boolean
  onToggle: () => void
  header: React.ReactNode
  children?: React.ReactNode
}) {
  return h('div', { className: 'rounded-lg border bg-card' },
    h('div', { className: 'flex items-center gap-3 px-4 py-3' },
      h('button', { className: 'text-xs opacity-50 shrink-0', onClick: props.onToggle }, props.isExpanded ? '▼' : '▶'),
      h('div', { className: 'flex-1 min-w-0' }, props.header)),
    props.isExpanded && props.children &&
      h('div', { className: 'border-t px-4 py-3' }, props.children))
}

export function Pagination(props: { page: number; pageCount: number; onChange: (page: number) => void }) {
  if (props.pageCount <= 1) return null
  return h('div', { className: 'flex items-center justify-center gap-3 pt-2' },
    h('button', {
      disabled: props.page <= 0,
      onClick: () => props.onChange(props.page - 1),
      className: 'px-3 py-1 rounded border text-sm hover:bg-muted disabled:opacity-40 transition-colors',
    }, '← Prev'),
    h('span', { className: 'text-xs text-muted-foreground' }, `Page ${props.page + 1} of ${props.pageCount}`),
    h('button', {
      disabled: props.page >= props.pageCount - 1,
      onClick: () => props.onChange(props.page + 1),
      className: 'px-3 py-1 rounded border text-sm hover:bg-muted disabled:opacity-40 transition-colors',
    }, 'Next →'))
}

export function StatusIndicator(props: { loading: boolean; status: string | null; error: string | null }) {
  let dot: React.ReactNode
  let label: string
  let labelClass: string
  if (props.loading) {
    dot = h('span', { className: 'w-3 h-3 rounded-full bg-gray-400 animate-pulse' })
    label = 'Checking…'; labelClass = 'text-muted-foreground'
  } else if (props.status === 'ok') {
    dot = h('span', { className: 'w-3 h-3 rounded-full bg-green-500' })
    label = 'Ready'; labelClass = 'text-green-700 font-medium'
  } else if (props.status === 'partial') {
    dot = h('span', { className: 'w-3 h-3 rounded-full bg-yellow-500' })
    label = 'Partial'; labelClass = 'text-yellow-700 font-medium'
  } else {
    dot = h('span', { className: 'w-3 h-3 rounded-full bg-red-500' })
    label = props.error || 'Unavailable'; labelClass = 'text-destructive font-medium'
  }
  return h('span', { className: 'inline-flex items-center gap-2 text-sm' },
    dot, h('span', { className: labelClass }, label))
}

export function ExecutionResult(props: { execution: JobExecution }) {
  const ex = props.execution
  const statusColor = ex.status === 'RUNNING' ? 'text-green-700'
    : ex.status === 'PENDING' ? 'text-yellow-700'
    : ex.status === 'FAILED' ? 'text-destructive'
    : 'text-muted-foreground'
  return h('div', { className: 'rounded-lg border bg-muted/30 p-3 space-y-2 text-sm mt-3' },
    h('div', { className: 'flex items-center gap-3 flex-wrap' },
      h('span', { className: 'font-semibold' }, 'Job launched'),
      h('span', { className: 'font-mono text-xs bg-muted px-2 py-0.5 rounded' }, ex.id),
      h('span', { className: `font-medium ${statusColor}` }, ex.status),
    ),
    ex.endpoints && ex.endpoints.length > 0 &&
      h('div', { className: 'space-y-1' },
        ex.endpoints.map((ep, i) => h('div', { key: i, className: 'font-mono text-xs text-muted-foreground' },
          `${ep.host}:${ep.port}/${ep.protocol}`))),
  )
}

export function DetailGrid(props: { obj: unknown }) {
  const obj = props.obj
  if (!obj || typeof obj !== 'object') return null
  const entries = Object.entries(obj as Record<string, unknown>).filter(([, v]) => v != null)
  if (entries.length === 0) return null
  return h('div', {
    className: 'grid gap-x-6 gap-y-1 text-xs font-mono mt-2',
    style: { gridTemplateColumns: 'auto 1fr' },
  }, entries.flatMap(([k, v]) => [
    h('span', { key: `${k}_k`, className: 'text-muted-foreground whitespace-nowrap' }, k),
    h('span', { key: `${k}_v`, className: 'break-all' }, typeof v === 'object' ? JSON.stringify(v) : String(v)),
  ]))
}

const listInputClass = 'flex-1 rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary'

export function ListEditor(props: { items: string[]; onChange: (i: number, v: string) => void; onAdd: () => void; onRemove: (i: number) => void }) {
  return h('div', { className: 'space-y-1' },
    props.items.map((val, i) => h('div', { key: i, className: 'flex items-center gap-1' },
      h('input', { className: listInputClass, value: val, onChange: (e: React.ChangeEvent<HTMLInputElement>) => props.onChange(i, e.target.value) }),
      h('button', { type: 'button', className: 'text-muted-foreground hover:text-destructive px-1', onClick: () => props.onRemove(i) }, '✕'))),
    h('button', { type: 'button', className: 'text-xs text-primary hover:underline', onClick: props.onAdd }, '+ Add'))
}

export interface KVPair { key: string; value: string }

export function KVEditor(props: {
  items: KVPair[]
  onChangeKey: (i: number, v: string) => void
  onChangeValue: (i: number, v: string) => void
  onAdd: () => void
  onRemove: (i: number) => void
}) {
  return h('div', { className: 'space-y-1' },
    props.items.map((pair, i) => h('div', { key: i, className: 'flex items-center gap-1' },
      h('input', { className: listInputClass, value: pair.key, onChange: (e: React.ChangeEvent<HTMLInputElement>) => props.onChangeKey(i, e.target.value) }),
      h('span', { className: 'text-muted-foreground text-sm px-1' }, '='),
      h('input', { className: listInputClass, value: pair.value, onChange: (e: React.ChangeEvent<HTMLInputElement>) => props.onChangeValue(i, e.target.value) }),
      h('button', { type: 'button', className: 'text-muted-foreground hover:text-destructive px-1', onClick: () => props.onRemove(i) }, '✕'))),
    h('button', { type: 'button', className: 'text-xs text-primary hover:underline', onClick: props.onAdd }, '+ Add'))
}
