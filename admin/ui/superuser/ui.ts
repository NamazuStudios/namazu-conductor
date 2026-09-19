import React from 'react'
import { marked } from 'marked'
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

let markdownStylesInjected = false

/**
 * `prose`/`prose-sm` (Tailwind Typography) do nothing unless the *host* dashboard's own Tailwind
 * build happens to have that plugin enabled — this bundle has no say over the host's build, and it
 * may well not. Without it, Tailwind's preflight reset (which strips all default heading/list/
 * paragraph styling specifically so consumers opt back in via `prose`) leaves every element `marked`
 * produces looking like identical unstyled text — headings, bullets, bold, etc. all render, but
 * visually indistinguishable. These rules restore basic markdown formatting unconditionally, so
 * rendering doesn't depend on anything the host's Tailwind config happens to include.
 */
function injectMarkdownStyles() {
  if (markdownStylesInjected) return
  markdownStylesInjected = true
  const style = document.createElement('style')
  style.id = 'conductor-markdown-styles'
  style.textContent = `
    .conductor-markdown { max-width: none; }
    .conductor-markdown > *:first-child { margin-top: 0; }
    .conductor-markdown > *:last-child { margin-bottom: 0; }
    .conductor-markdown h1, .conductor-markdown h2, .conductor-markdown h3,
    .conductor-markdown h4, .conductor-markdown h5, .conductor-markdown h6 {
      font-weight: 600; line-height: 1.3; margin: 1em 0 0.5em;
    }
    .conductor-markdown h1 { font-size: 1.35em; }
    .conductor-markdown h2 { font-size: 1.2em; }
    .conductor-markdown h3 { font-size: 1.1em; }
    .conductor-markdown h4, .conductor-markdown h5, .conductor-markdown h6 { font-size: 1em; }
    .conductor-markdown p { margin: 0.5em 0; line-height: 1.5; }
    .conductor-markdown ul, .conductor-markdown ol { margin: 0.5em 0; padding-left: 1.5em; }
    .conductor-markdown ul { list-style: disc; }
    .conductor-markdown ol { list-style: decimal; }
    .conductor-markdown li { margin: 0.25em 0; }
    .conductor-markdown li > ul, .conductor-markdown li > ol { margin: 0.25em 0; }
    .conductor-markdown strong { font-weight: 700; }
    .conductor-markdown em { font-style: italic; }
    .conductor-markdown a { color: rgb(59 130 246); text-decoration: underline; text-underline-offset: 2px; }
    .conductor-markdown a:hover { opacity: 0.85; }
    .conductor-markdown code {
      font-family: ui-monospace, monospace; font-size: 0.9em; padding: 0.15em 0.35em;
      border-radius: 0.25em; background: rgba(127, 127, 127, 0.15);
    }
    .conductor-markdown pre {
      margin: 0.5em 0; padding: 0.75em; border-radius: 0.5em;
      background: rgba(127, 127, 127, 0.12); overflow-x: auto;
    }
    .conductor-markdown pre code { background: none; padding: 0; }
    .conductor-markdown blockquote {
      margin: 0.5em 0; padding-left: 1em; border-left: 3px solid rgba(127, 127, 127, 0.35); opacity: 0.85;
    }
    .conductor-markdown hr { margin: 1em 0; border: none; border-top: 1px solid rgba(127, 127, 127, 0.25); }
    .conductor-markdown table { border-collapse: collapse; margin: 0.5em 0; }
    .conductor-markdown th, .conductor-markdown td {
      border: 1px solid rgba(127, 127, 127, 0.25); padding: 0.35em 0.6em; text-align: left;
    }
    .conductor-markdown img { max-width: 100%; }
  `
  document.head.appendChild(style)
}

/**
 * Renders provider/profile-authored Markdown (job descriptions, job-set notes). `breaks: true` turns
 * single newlines into `<br>` — these strings typically come from a Kubernetes annotation or ECS tag
 * value authored with plain newlines (no blank-line-separated paragraphs), which without this option
 * `marked` collapses into one run-on paragraph, making the source's line structure disappear entirely.
 */
export function MarkdownBlock(props: { markdown: string; className?: string }) {
  React.useEffect(() => { injectMarkdownStyles() }, [])
  const html = React.useMemo(
    () => marked.parse(props.markdown, { async: false, breaks: true, gfm: true }) as string,
    [props.markdown],
  )
  return h('div', {
    className: `conductor-markdown ${props.className ?? ''}`,
    dangerouslySetInnerHTML: { __html: html },
  })
}

/** A `MarkdownBlock` behind its own `Accordion` toggle, independent of any enclosing row's expand state. */
export function CollapsibleMarkdown(props: { markdown: string; label?: string; defaultExpanded?: boolean }) {
  const [expanded, setExpanded] = React.useState(props.defaultExpanded ?? true)
  return h(Accordion, {
    isExpanded: expanded,
    onToggle: () => setExpanded((v) => !v),
    header: h('span', { className: 'text-xs font-medium text-muted-foreground' }, props.label ?? 'Description'),
  }, h(MarkdownBlock, { markdown: props.markdown, className: 'text-sm' }))
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
