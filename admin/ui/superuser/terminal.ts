import React from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { mintTerminalTicket } from './api'

const h = React.createElement

// Bell sound copied alongside this bundle at build time — see admin/src/main/ui/superuser/complete.oga.
const BELL_SOUND_PATH = './complete.oga'

interface Session {
  key: string
  label: string
  term: Terminal
  fitAddon: FitAddon
  ws: WebSocket
  container: HTMLDivElement
  status: 'connecting' | 'open' | 'closed' | 'error'
  bellAudio: HTMLAudioElement
}

type Listener = () => void

/**
 * Owns live terminal sessions (xterm.js instance + WebSocket) outside of React's render tree, so
 * switching tabs re-parents an existing DOM node instead of destroying and recreating the terminal —
 * that's what keeps a session's connection and scrollback alive while another tab is active.
 */
class TerminalSessionManager {
  private sessions = new Map<string, Session>()
  private listeners = new Set<Listener>()
  private activeKey: string | null = null

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private notify() {
    this.listeners.forEach((l) => l())
  }

  getSnapshot() {
    return {
      sessions: [...this.sessions.values()].map((s) => ({ key: s.key, label: s.label, status: s.status })),
      activeKey: this.activeKey,
    }
  }

  getSession(key: string): Session | undefined {
    return this.sessions.get(key)
  }

  setActive(key: string) {
    if (this.sessions.has(key)) {
      this.activeKey = key
      this.notify()
    }
  }

  /** Opens (or focuses, if already open) a terminal for `element`/`jobId`/`containerId`. */
  open(element: string, jobId: string, containerId: string | null, label: string) {
    const key = `${element}:${jobId}:${containerId ?? 'primary'}`
    if (this.sessions.has(key)) {
      this.setActive(key)
      return
    }

    const container = document.createElement('div')
    container.style.width = '100%'
    container.style.height = '100%'

    const term = new Terminal({ convertEol: true, cursorBlink: true })
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    term.open(container)

    const bellAudio = new Audio(BELL_SOUND_PATH)
    term.onBell(() => { bellAudio.currentTime = 0; void bellAudio.play().catch(() => {}) })

    const session: Session = { key, label, term, fitAddon, ws: null as unknown as WebSocket, container, status: 'connecting', bellAudio }
    this.sessions.set(key, session)
    this.activeKey = key
    this.notify()

    term.writeln(`Connecting to ${label}…`)

    mintTerminalTicket(jobId, containerId)
      .then(({ ticket }) => this.connect(session, jobId, containerId, ticket))
      .catch((e: Error) => {
        term.writeln(`\r\nFailed to open terminal: ${e.message}`)
        session.status = 'error'
        this.notify()
      })
  }

  private connect(session: Session, jobId: string, containerId: string | null, ticket: string) {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const path = containerId ? `/conductor/admin/service/${jobId}/${containerId}` : `/conductor/admin/service/${jobId}`
    const url = `${scheme}://${window.location.host}${path}?ticket=${encodeURIComponent(ticket)}`

    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    session.ws = ws

    ws.onopen = () => {
      session.status = 'open'
      session.term.reset()
      this.notify()
    }
    ws.onmessage = (event) => {
      if (event.data instanceof ArrayBuffer) session.term.write(new Uint8Array(event.data))
    }
    ws.onclose = (event) => {
      session.status = 'closed'
      session.term.writeln(`\r\n[connection closed${event.reason ? `: ${event.reason}` : ''}]`)
      this.notify()
    }
    ws.onerror = () => {
      session.status = 'error'
      this.notify()
    }

    session.term.onData((data) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(data))
    })
    session.term.onResize(({ cols, rows }) => {
      if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'resize', cols, rows }))
    })
  }

  close(key: string) {
    const session = this.sessions.get(key)
    if (!session) return
    session.ws.close()
    session.term.dispose()
    this.sessions.delete(key)
    if (this.activeKey === key) {
      const remaining = [...this.sessions.keys()]
      this.activeKey = remaining.length > 0 ? remaining[remaining.length - 1] : null
    }
    this.notify()
  }
}

export const terminalSessionManager = new TerminalSessionManager()

function useTerminalSnapshot() {
  return React.useSyncExternalStore(
    (listener) => terminalSessionManager.subscribe(listener),
    () => terminalSessionManager.getSnapshot(),
  )
}

/**
 * Tab strip + host viewport for all open terminal sessions. Renders nothing when no session is
 * open. Switching the active tab re-parents the target session's existing DOM node into the host
 * div rather than remounting it, so other open sessions keep running in the background.
 */
export function TerminalTabs() {
  const { sessions, activeKey } = useTerminalSnapshot()
  const hostRef = React.useRef<HTMLDivElement | null>(null)

  React.useEffect(() => {
    const host = hostRef.current
    if (!host || !activeKey) return
    const session = terminalSessionManager.getSession(activeKey)
    if (!session) return
    host.replaceChildren(session.container)
    session.fitAddon.fit()
    session.term.focus()

    const observer = new ResizeObserver(() => session.fitAddon.fit())
    observer.observe(host)
    return () => observer.disconnect()
  }, [activeKey])

  if (sessions.length === 0) return null

  return h('div', { className: 'space-y-2' },
    h('h2', { className: 'text-lg font-semibold' }, 'Terminals'),
    h('div', { className: 'flex items-center gap-1 flex-wrap border-b' },
      sessions.map((s) => h('div', {
        key: s.key,
        className: `flex items-center gap-1.5 px-3 py-1.5 text-xs font-mono cursor-pointer border-b-2 ${
          s.key === activeKey ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'
        }`,
      },
        h('span', { onClick: () => terminalSessionManager.setActive(s.key) }, s.label),
        h('span', {
          className: `w-1.5 h-1.5 rounded-full ${
            s.status === 'open' ? 'bg-green-500' : s.status === 'connecting' ? 'bg-yellow-500 animate-pulse' : 'bg-destructive'
          }`,
        }),
        h('button', {
          className: 'text-muted-foreground hover:text-destructive ml-1',
          onClick: (e: React.MouseEvent) => { e.stopPropagation(); terminalSessionManager.close(s.key) },
        }, '✕'),
      ))),
    h('div', {
      ref: hostRef,
      className: 'rounded-lg border bg-black p-2',
      style: { height: '384px' },
    }))
}
