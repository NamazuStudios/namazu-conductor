import React from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import xtermCss from '@xterm/xterm/css/xterm.css?inline'
import { mintTerminalTicket, resolveWsRoot } from './api'

const h = React.createElement

export function injectXtermStyles() {
  if (document.getElementById('conductor-xterm-styles')) return
  const style = document.createElement('style')
  style.id = 'conductor-xterm-styles'
  style.textContent = xtermCss
  document.head.appendChild(style)
}

// Bell sound copied alongside this bundle at build time — see admin/src/main/ui/superuser/complete.oga.
// Thanks to Okiedokie24 for the "ding" sound effect.
// Resolved against the bundle's OWN script URL (captured once, synchronously, while this script is
// still `document.currentScript`), not the current page URL — we now do full-page navigations across
// three different /admin/plugin/{route} URLs, none of which are anywhere near where this bundle (and
// its co-located asset) is actually served from.
const BUNDLE_BASE_URL = (document.currentScript as HTMLScriptElement | null)?.src ?? window.location.href
const BELL_SOUND_PATH = new URL('complete.oga', BUNDLE_BASE_URL).href

const BELL_VOLUME_STORAGE_KEY = 'conductor-terminal-bell-volume'

/** `Session.ws` may still be the initial placeholder (mint-ticket failed before connect() ran). */
function runCatchingClose(ws: WebSocket | null) {
  try { ws?.close() } catch { /* already closed/never opened */ }
}

function loadBellVolume(): number {
  const raw = localStorage.getItem(BELL_VOLUME_STORAGE_KEY)
  const parsed = raw == null ? NaN : Number(raw)
  return Number.isFinite(parsed) ? Math.min(1, Math.max(0, parsed)) : 1
}

interface Session {
  key: string
  label: string
  term: Terminal
  fitAddon: FitAddon
  ws: WebSocket
  container: HTMLDivElement
  status: 'connecting' | 'open' | 'closed' | 'error'
  bellAudio: HTMLAudioElement
  hasUnseenBell: boolean
}

type Listener = () => void

/**
 * Reads the dashboard's current `bg-background`/`text-foreground` colors via computed style so the
 * terminal palette tracks whatever theme (and light/dark mode) the host dashboard is using, rather
 * than hardcoding a palette that could clash with it.
 */
function getTerminalTheme(): { background: string; foreground: string } {
  const probe = document.createElement('div')
  probe.className = 'bg-background text-foreground'
  probe.style.position = 'absolute'
  probe.style.visibility = 'hidden'
  probe.style.pointerEvents = 'none'
  document.body.appendChild(probe)
  const style = getComputedStyle(probe)
  const theme = { background: style.backgroundColor, foreground: style.color }
  document.body.removeChild(probe)
  return theme
}

/**
 * Owns live terminal sessions (xterm.js instance + WebSocket) outside of React's render tree, so
 * switching tabs re-parents an existing DOM node instead of destroying and recreating the terminal —
 * that's what keeps a session's connection and scrollback alive while another tab is active.
 */
class TerminalSessionManager {
  private sessions = new Map<string, Session>()
  private listeners = new Set<Listener>()
  private activeKey: string | null = null
  private bellVolume = loadBellVolume()
  private execCounter = 0

  // useSyncExternalStore requires getSnapshot() to return a stable reference when nothing has
  // changed — recomputing a fresh object/array on every call causes an infinite re-render loop.
  private snapshot = this.computeSnapshot()

  constructor() {
    new MutationObserver(() => this.applyTheme()).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['class'],
    })
  }

  private applyTheme() {
    const theme = getTerminalTheme()
    this.sessions.forEach((s) => { s.term.options.theme = theme })
  }

  subscribe(listener: Listener): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  private notify() {
    this.snapshot = this.computeSnapshot()
    this.listeners.forEach((l) => l())
  }

  private computeSnapshot() {
    return {
      sessions: [...this.sessions.values()].map((s) => ({
        key: s.key, label: s.label, status: s.status, hasUnseenBell: s.hasUnseenBell,
      })),
      activeKey: this.activeKey,
    }
  }

  getSnapshot() {
    return this.snapshot
  }

  getSession(key: string): Session | undefined {
    return this.sessions.get(key)
  }

  getBellVolume(): number {
    return this.bellVolume
  }

  /** Applies immediately to every currently-open session's bell, and to any opened afterward. */
  setBellVolume(volume: number) {
    this.bellVolume = Math.min(1, Math.max(0, volume))
    localStorage.setItem(BELL_VOLUME_STORAGE_KEY, String(this.bellVolume))
    this.sessions.forEach((s) => { s.bellAudio.volume = this.bellVolume })
  }

  hasOpenSessions(): boolean {
    return this.sessions.size > 0
  }

  setActive(key: string) {
    const session = this.sessions.get(key)
    if (session) {
      this.activeKey = key
      session.hasUnseenBell = false
      this.notify()
    }
  }

  /**
   * Opens (or focuses/retries, if a session already exists) a terminal for `element`/`jobId`/
   * `containerId`. `command` optionally execs a specific process for this session instead of
   * attaching to the container's own — like `kubectl exec` vs `kubectl attach`. Omitting it reuses
   * the same session key as any other default (no-command) attach to this container, so repeat clicks
   * refocus/retry the same tab; specifying a command always opens a distinct new tab, since it's a
   * semantically different request that shouldn't collide with (or silently replace) a plain attach.
   */
  open(element: string, jobId: string, containerId: string | null, label: string, command?: string[]) {
    const baseKey = `${element}:${jobId}:${containerId ?? 'primary'}`
    const key = command && command.length > 0 ? `${baseKey}:exec${this.execCounter++}` : baseKey

    const existing = this.sessions.get(key)
    if (existing) {
      if (existing.status === 'connecting' || existing.status === 'open') {
        this.setActive(key)
        return
      }
      // A dead (errored/closed) session for this exact key — dispose it and fall through to open a
      // genuinely fresh one instead of silently reactivating a tab that will never reconnect on its
      // own. Without this, clicking "Attach Terminal" again after a failure looks like nothing
      // happened at all: no new ticket minted, no new WebSocket, no request sent.
      existing.term.dispose()
      runCatchingClose(existing.ws)
      this.sessions.delete(key)
    }

    const container = document.createElement('div')
    container.style.width = '100%'
    container.style.height = '100%'

    const term = new Terminal({ convertEol: true, cursorBlink: true, theme: getTerminalTheme() })
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    term.open(container)

    const bellAudio = new Audio(BELL_SOUND_PATH)
    bellAudio.volume = this.bellVolume

    const session: Session = { key, label, term, fitAddon, ws: null as unknown as WebSocket, container, status: 'connecting', bellAudio, hasUnseenBell: false }

    term.onBell(() => {
      bellAudio.currentTime = 0
      void bellAudio.play().catch(() => {})
      // Flag it for the drawer (🕭) only while some other session is focused — a bell on the session
      // the user is already looking at doesn't need a separate visual callout.
      if (session.key !== this.activeKey) {
        session.hasUnseenBell = true
        this.notify()
      }
    })
    this.sessions.set(key, session)
    this.activeKey = key
    this.notify()

    term.writeln(`Connecting to ${label}…`)

    mintTerminalTicket(jobId, containerId, command)
      .then(({ ticket }) => this.connect(session, jobId, containerId, ticket))
      .catch((e: Error) => {
        term.writeln(`\r\nFailed to open terminal: ${e.message}`)
        session.status = 'error'
        this.notify()
      })
  }

  private async connect(session: Session, jobId: string, containerId: string | null, ticket: string) {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const wsRoot = await resolveWsRoot()
    const path = containerId ? `${wsRoot}/service/${jobId}/${containerId}` : `${wsRoot}/service/${jobId}`
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

// Warn before any full-page unload while terminals are open — clicking a host sidebar link (a hard
// navigation to a different plugin route), refreshing, or closing the tab all trigger this. There's no
// way to prevent the host's own navigation from tearing down this bundle (and every open WebSocket
// with it), so this is the most robust coverage achievable: it can't stop the host's navigation, but
// it can make the browser ask for confirmation first.
window.addEventListener('beforeunload', (event) => {
  if (!terminalSessionManager.hasOpenSessions()) return
  event.preventDefault()
  event.returnValue = ''
})

/** Plays the terminal bell sound once, at the current volume — a manual test/utility affordance. */
export function playBell() {
  const audio = new Audio(BELL_SOUND_PATH)
  audio.volume = terminalSessionManager.getBellVolume()
  void audio.play().catch(() => {})
}

export function BellControls() {
  const [volume, setVolume] = React.useState(() => terminalSessionManager.getBellVolume())
  const previousVolumeRef = React.useRef(volume > 0 ? volume : 1)

  function handleVolumeChange(e: React.ChangeEvent<HTMLInputElement>) {
    const next = Number(e.target.value) / 100
    if (next > 0) previousVolumeRef.current = next
    setVolume(next)
    terminalSessionManager.setBellVolume(next)
  }

  function toggleMute() {
    if (volume > 0) {
      previousVolumeRef.current = volume
      setVolume(0)
      terminalSessionManager.setBellVolume(0)
    } else {
      const restored = previousVolumeRef.current || 1
      setVolume(restored)
      terminalSessionManager.setBellVolume(restored)
    }
  }

  return h('div', { className: 'flex items-center gap-3' },
    h('button', {
      className: 'px-2.5 py-1 rounded border text-xs hover:bg-muted transition-colors',
      onClick: () => playBell(),
      title: 'Play the terminal bell sound',
    }, '🔔'),
    h('label', { className: 'flex items-center gap-1.5 text-xs text-muted-foreground' },
      h('button', {
        type: 'button',
        onClick: toggleMute,
        className: 'hover:opacity-70 transition-opacity',
        title: volume === 0 ? 'Unmute terminal bell' : 'Mute terminal bell',
      }, volume === 0 ? '🔇' : '🔊'),
      h('input', {
        type: 'range',
        min: 0,
        max: 100,
        value: Math.round(volume * 100),
        onChange: handleVolumeChange,
        className: 'w-24',
        title: 'Terminal bell volume',
      })))
}

function useTerminalSnapshot() {
  return React.useSyncExternalStore(
    (listener) => terminalSessionManager.subscribe(listener),
    () => terminalSessionManager.getSnapshot(),
  )
}

function statusDotClass(status: 'connecting' | 'open' | 'closed' | 'error'): string {
  return status === 'open' ? 'bg-green-500' : status === 'connecting' ? 'bg-yellow-500 animate-pulse' : 'bg-destructive'
}

const MIN_DRAWER_WIDTH = 140
const MAX_DRAWER_WIDTH = 400
const MIN_PANEL_HEIGHT = 240
const PANEL_BOTTOM_MARGIN = 16

/**
 * Collapsible, resizable left drawer (session list) + main viewport for all open terminal sessions,
 * with an empty state when none are open. Switching the active session re-parents its existing DOM
 * node into the host div rather than remounting it, so other open sessions keep running in the
 * background.
 */
export function TerminalTabs() {
  const { sessions, activeKey } = useTerminalSnapshot()
  const hostRef = React.useRef<HTMLDivElement | null>(null)
  const panelRef = React.useRef<HTMLDivElement | null>(null)
  const [drawerOpen, setDrawerOpen] = React.useState(true)
  const [drawerWidth, setDrawerWidth] = React.useState(220)
  const [isResizingDrawer, setIsResizingDrawer] = React.useState(false)
  const [panelHeight, setPanelHeight] = React.useState(420)

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

  // Pins the panel's bottom to the bottom of the page instead of a fixed height that leaves a gap (or
  // gets cramped) depending on how much the running-jobs list above takes up. There's no browser event
  // for "an ancestor's layout changed height" short of watching the whole document, so a lightweight
  // poll (alongside the real resize listener) keeps this accurate as jobs above are expanded/collapsed.
  React.useEffect(() => {
    function recompute() {
      const el = panelRef.current
      if (!el) return
      const top = el.getBoundingClientRect().top
      setPanelHeight(Math.max(MIN_PANEL_HEIGHT, Math.floor(window.innerHeight - top - PANEL_BOTTOM_MARGIN)))
    }
    recompute()
    window.addEventListener('resize', recompute)
    const interval = window.setInterval(recompute, 500)
    return () => { window.removeEventListener('resize', recompute); window.clearInterval(interval) }
  }, [])

  function startDrawerResize(e: React.MouseEvent) {
    e.preventDefault()
    setIsResizingDrawer(true)
    const startX = e.clientX
    const startWidth = drawerWidth
    function onMove(ev: MouseEvent) {
      setDrawerWidth(Math.min(MAX_DRAWER_WIDTH, Math.max(MIN_DRAWER_WIDTH, startWidth + (ev.clientX - startX))))
    }
    function onUp() {
      setIsResizingDrawer(false)
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
    }
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
  }

  const indicator = h('span', { className: 'inline-flex items-center gap-1.5 text-xs text-muted-foreground' },
    h('span', {
      className: `w-1.5 h-1.5 rounded-full ${sessions.length > 0 ? 'bg-green-500' : 'bg-muted-foreground/40'}`,
    }),
    `${sessions.length} open`)

  if (sessions.length === 0) {
    return h('div', { className: 'space-y-2' },
      h('div', { className: 'flex items-center gap-2' },
        h('h2', { className: 'text-lg font-semibold' }, 'Terminals'),
        indicator),
      h('p', { className: 'text-sm text-muted-foreground' },
        'No terminals are currently open. Attach one from a running job’s container list.'))
  }

  const activeSession = sessions.find((s) => s.key === activeKey) ?? null

  return h('div', {
    ref: panelRef,
    className: 'rounded-lg border bg-background flex',
    style: { height: `${panelHeight}px` },
  },
    h('div', {
      className: 'shrink-0 bg-muted/30 flex flex-col overflow-hidden',
      style: { width: drawerOpen ? `${drawerWidth}px` : '0px', transition: isResizingDrawer ? 'none' : 'width 200ms ease' },
    },
      h('div', { className: 'flex items-center gap-2 px-3 py-2 border-b whitespace-nowrap' },
        h('h2', { className: 'text-sm font-semibold flex-1' }, 'Terminals'),
        indicator),
      h('div', { className: 'flex-1 overflow-y-auto' },
        sessions.map((s) => h('div', {
          key: s.key,
          onClick: () => terminalSessionManager.setActive(s.key),
          title: s.label,
          className: `flex items-center gap-1.5 px-3 py-2 text-xs font-mono cursor-pointer border-l-2 whitespace-nowrap ${
            s.key === activeKey ? 'border-primary bg-background text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground hover:bg-muted/50'
          }`,
        },
          h('span', { className: `w-1.5 h-1.5 rounded-full shrink-0 ${statusDotClass(s.status)}` }),
          h('span', { className: 'truncate flex-1' }, s.hasUnseenBell ? '🕭 ' : null, s.label),
          h('button', {
            className: 'shrink-0 text-muted-foreground hover:text-destructive',
            onClick: (e: React.MouseEvent) => { e.stopPropagation(); terminalSessionManager.close(s.key) },
          }, '✕'),
        ))),
    ),
    drawerOpen && h('div', {
      className: 'shrink-0 w-1 cursor-col-resize hover:bg-primary/40 transition-colors',
      onMouseDown: startDrawerResize,
      title: 'Drag to resize',
    }),
    h('button', {
      className: 'shrink-0 w-5 border-l border-r flex items-center justify-center text-xs text-muted-foreground hover:bg-muted transition-colors',
      onClick: () => setDrawerOpen((v) => !v),
      title: drawerOpen ? 'Collapse terminal list' : 'Expand terminal list',
    }, drawerOpen ? '‹' : '›'),
    h('div', { className: 'flex-1 flex flex-col min-w-0 min-h-0' },
      h('div', { className: 'flex items-center gap-2 px-3 py-2 border-b' },
        h('span', { className: `w-1.5 h-1.5 rounded-full shrink-0 ${activeSession ? statusDotClass(activeSession.status) : 'bg-muted-foreground/40'}` }),
        h('span', { className: 'text-sm font-mono font-medium truncate' }, activeSession?.label ?? 'No terminal selected')),
      h('div', { ref: hostRef, className: 'flex-1 p-2 min-h-0 overflow-hidden' })))
}
