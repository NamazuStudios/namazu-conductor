import React from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import xtermCss from '@xterm/xterm/css/xterm.css?inline'
import { resolveWsRoot } from './api'

const h = React.createElement

export function injectXtermStyles() {
  if (document.getElementById('conductor-xterm-styles')) return
  const style = document.createElement('style')
  style.id = 'conductor-xterm-styles'
  style.textContent = xtermCss
  document.head.appendChild(style)
}

// Sounds copied alongside this bundle at build time — see admin/src/main/ui/superuser/{complete,message}.oga.
// Thanks to Okiedokie24 for the "ding" bell sound effect.
// Resolved against the bundle's OWN script URL (captured once, synchronously, while this script is
// still `document.currentScript`), not the current page URL — we now do full-page navigations across
// three different /admin/plugin/{route} URLs, none of which are anywhere near where this bundle (and
// its co-located assets) is actually served from.
const BUNDLE_BASE_URL = (document.currentScript as HTMLScriptElement | null)?.src ?? window.location.href
const BELL_SOUND_PATH = new URL('complete.oga', BUNDLE_BASE_URL).href
const MESSAGE_SOUND_PATH = new URL('message.oga', BUNDLE_BASE_URL).href

const BELL_VOLUME_STORAGE_KEY = 'conductor-terminal-bell-volume'

// Custom OSC (Operating System Command) escape sequence a container/job can write to its own stdout
// to surface a toast in the operator's dashboard — e.g. `printf '\e]9001;Build finished\a'`. This
// travels in-band over the *existing* raw binary pty stream (same channel `onBell()` below already
// relies on for the BEL control character), so — unlike the resize control message, which is a
// dedicated JSON WS text frame — it needs no server-side changes at all: xterm's own parser already
// correctly reassembles OSC sequences split across WebSocket frames/pty reads, exactly as it already
// does for BEL and title-change (OSC 0/2) sequences. 9001 is an arbitrary, unassigned OSC number
// (see https://github.com/NamazuStudios/namazu-conductor/issues/37) chosen to avoid colliding with
// established conventions (OSC 9 iTerm2 growl, OSC 777 konsole/xterm notify, OSC 1337 iTerm2 proprietary).
const TOAST_OSC_IDENT = 9001

// Shared across all sessions (see TerminalSessionManager.toastHistory) per issue #37 — history
// survives switching tabs; no need to persist across page reloads.
const MAX_TOAST_HISTORY = 200
const TOAST_POPUP_DURATION_MS = 6000

// Lets a remote job/container direct the operator to a URL (a generated report, a status page, an
// OAuth-style consent screen, etc.) over the same in-band pty stream the toast mechanism above uses
// — the container has no local browser to shell out to the way e.g. `gh`'s OAuth flow does, since it
// runs on a remote node, not the operator's machine. Per the revised design in
// https://github.com/NamazuStudios/namazu-conductor/issues/34, this deliberately mirrors iTerm2's
// existing proprietary OSC 1337 sequence (`OpenURL=` subcommand) rather than inventing a new ident —
// e.g. `printf '\033]1337;OpenURL=%s\007' "https://example.com"`. A terminal that doesn't recognize a
// given OSC sequence just silently ignores it, which is exactly why reusing an established one here
// is safe. Only ever surfaces http(s) URLs (parseAllowedUrl) — anything else is dropped.
//
// Attempts `window.open()` immediately (openUrl below) — no confirmation step — but that's not
// guaranteed to work: browsers only let `window.open()` escape the popup blocker when it's the direct
// result of a user gesture (a click), and this fires from an async WebSocket message, so most
// browsers will silently block it (`window.open` just returns `null`, no exception). Every attempt
// also drops a toast with a real "Open ↗" button either way, since an actual click always satisfies
// the gesture requirement — that's the reliable fallback, not a confirmation gate.
const OPEN_URL_OSC_IDENT = 1337
const OPEN_URL_OSC_PREFIX = 'OpenURL='

// OSC 52 ("Manipulate Selection Data") — the de facto standard clipboard-copy sequence (tmux, vim,
// many CLIs use it) so a remote process can copy text to the *operator's* clipboard, not the
// container's. xterm.js has no built-in handler for it (registerOscHandler is only wired up for 4, 8,
// 10-12, 104, 110-112 in its own InputHandler.ts — 52 is just a comment), so without this it's
// silently dropped. Wire format: `Ps ; Pc ; Pd` where Pc is a clipboard selector (ignored — there's
// only ever one, the operator's browser clipboard) and Pd is base64, or `?` for a query (a read-back
// request, which this — a write-only relay — doesn't support and just ignores).
// Same gesture caveat as OPEN_URL_OSC_IDENT above: `navigator.clipboard.writeText()` from an async
// message can get rejected (especially in Firefox) without a user gesture, so this also attempts the
// write immediately *and* drops a toast with a "Copy" button as the reliable fallback.
const CLIPBOARD_OSC_IDENT = 52

interface ToastAction {
  kind: 'open-url' | 'copy'
  /** The URL to (re-)open, or the text to (re-)copy, depending on `kind`. */
  value: string
}

interface ToastEntry {
  id: string
  timestamp: number
  message: string
  sessionKey: string
  sessionLabel: string
  action?: ToastAction
}

/** Only http(s) URLs are ever surfaced to the operator — defense in depth, since this channel is
 * otherwise an open redirect from container-controlled output. */
function parseAllowedUrl(raw: string): string | null {
  try {
    const parsed = new URL(raw)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}

/** Decodes an OSC 52 payload (base64 of UTF-8 bytes) back to text; `null` if it's not valid base64. */
function decodeBase64Utf8(base64: string): string | null {
  try {
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return new TextDecoder('utf-8').decode(bytes)
  } catch {
    return null
  }
}

const URL_PATTERN = /https?:\/\/[^\s<>"']+/g

/**
 * Renders `text` as plain text with any http(s) URLs turned into real, clickable anchors — used for
 * every toast message (arbitrary job-authored text may or may not contain a link) and for terminal
 * output itself (see WebLinksAddon in TerminalSessionManager.open). A real anchor click always
 * satisfies the browser's user-gesture requirement, unlike a script-issued `window.open()`/clipboard
 * write from an async message — see OPEN_URL_OSC_IDENT/CLIPBOARD_OSC_IDENT above.
 */
function linkifyText(text: string): React.ReactNode[] {
  const parts: React.ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  let key = 0
  URL_PATTERN.lastIndex = 0
  while ((match = URL_PATTERN.exec(text)) !== null) {
    if (match.index > lastIndex) parts.push(text.slice(lastIndex, match.index))
    parts.push(h('a', {
      key: key++,
      href: match[0],
      target: '_blank',
      rel: 'noopener noreferrer',
      className: 'underline hover:opacity-80',
    }, match[0]))
    lastIndex = match.index + match[0].length
  }
  if (lastIndex < text.length) parts.push(text.slice(lastIndex))
  return parts
}

/** The button rendered alongside a toast's message when it carries a completable action — lets the
 * operator retry (or simply trigger, if the automatic attempt got blocked) with a real click. */
function ToastActionButton(props: { action: ToastAction }) {
  const { action } = props
  return h('button', {
    className: 'shrink-0 px-2 py-0.5 rounded border text-muted-foreground hover:bg-muted hover:text-foreground transition-colors',
    title: action.kind === 'open-url' ? action.value : 'Copy to clipboard',
    onClick: () => {
      if (action.kind === 'open-url') window.open(action.value, '_blank', 'noopener,noreferrer')
      else void navigator.clipboard?.writeText(action.value).catch(() => {})
    },
  }, action.kind === 'open-url' ? 'Open ↗' : 'Copy')
}

/** `Session.ws` may still be the initial placeholder (connect() hasn't run yet, or threw before assigning it). */
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
  private toastCounter = 0
  private toastHistory: ToastEntry[] = []
  private transientToasts: ToastEntry[] = []

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
      toastHistory: this.toastHistory,
      transientToasts: this.transientToasts,
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

  getToastHistory(): ToastEntry[] {
    return this.toastHistory
  }

  clearToastHistory() {
    this.toastHistory = []
    this.notify()
  }

  /** Removes a single history entry (e.g. the user dismissed just that one) without touching the rest. */
  removeToastHistoryEntry(id: string) {
    this.toastHistory = this.toastHistory.filter((t) => t.id !== id)
    this.notify()
  }

  /** Dismisses a transient popup early (e.g. the user clicked its ✕) without touching history. */
  dismissToastPopup(id: string) {
    this.transientToasts = this.transientToasts.filter((t) => t.id !== id)
    this.notify()
  }

  /** Attempts to navigate immediately; always drops a toast with an "Open ↗" button too — see
   * OPEN_URL_OSC_IDENT's doc comment for why the button, not the attempt, is the reliable path. */
  private openUrl(session: Session, url: string) {
    window.open(url, '_blank', 'noopener,noreferrer')
    this.pushToast(session, url, { kind: 'open-url', value: url })
  }

  /** Attempts the clipboard write immediately; always drops a toast with a "Copy" button too — same
   * reasoning as openUrl above. */
  private copyToClipboard(session: Session, text: string) {
    void navigator.clipboard?.writeText(text).catch(() => {})
    this.pushToast(session, text, { kind: 'copy', value: text })
  }

  private pushToast(session: Session, message: string, action?: ToastAction) {
    const entry: ToastEntry = {
      id: `toast-${this.toastCounter++}`,
      timestamp: Date.now(),
      message,
      sessionKey: session.key,
      sessionLabel: session.label,
      action,
    }
    this.toastHistory = [...this.toastHistory, entry].slice(-MAX_TOAST_HISTORY)
    this.transientToasts = [...this.transientToasts, entry]
    this.notify()
    setTimeout(() => this.dismissToastPopup(entry.id), TOAST_POPUP_DURATION_MS)
    const chime = new Audio(MESSAGE_SOUND_PATH)
    chime.volume = this.bellVolume
    void chime.play().catch(() => {})
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
   * Opens a brand-new terminal for `element`/`jobId`/`containerId` — every call gets its own distinct
   * session/tab, even if one is already open for the same job/container, so launching never silently
   * refocuses or replaces a prior session. `command` optionally execs a specific process for this
   * session instead of attaching to the container's own — like `kubectl exec` vs `kubectl attach`.
   */
  open(element: string, jobId: string, containerId: string | null, label: string, command?: string[]) {
    const baseKey = `${element}:${jobId}:${containerId ?? 'primary'}`
    const key = `${baseKey}:${this.execCounter++}`

    const container = document.createElement('div')
    container.style.width = '100%'
    container.style.height = '100%'

    const term = new Terminal({ convertEol: true, cursorBlink: true, theme: getTerminalTheme() })
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    // Linkifies URLs appearing in ordinary program output (not just toasts) — clicking one opens it
    // in a new tab via the addon's default handler, which is a real click, so it's never subject to
    // the popup-blocker/gesture caveat that OPEN_URL_OSC_IDENT's own auto-open attempt is.
    term.loadAddon(new WebLinksAddon())
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
    term.parser.registerOscHandler(TOAST_OSC_IDENT, (data) => {
      const message = data.trim()
      if (message) this.pushToast(session, message)
      return true
    })
    term.parser.registerOscHandler(OPEN_URL_OSC_IDENT, (data) => {
      const payload = data.trim()
      if (payload.startsWith(OPEN_URL_OSC_PREFIX)) {
        const url = parseAllowedUrl(payload.slice(OPEN_URL_OSC_PREFIX.length))
        if (url) this.openUrl(session, url)
      }
      return true
    })
    term.parser.registerOscHandler(CLIPBOARD_OSC_IDENT, (data) => {
      const semicolon = data.indexOf(';')
      if (semicolon < 0) return true
      const payload = data.slice(semicolon + 1)
      if (payload === '?' || payload === '') return true // read-back query / clear — nothing to relay
      const text = decodeBase64Utf8(payload)
      if (text) this.copyToClipboard(session, text)
      return true
    })
    this.sessions.set(key, session)
    this.activeKey = key
    this.notify()

    term.writeln(`Connecting to ${label}…`)

    this.connect(session, jobId, containerId, command).catch((e: Error) => {
      term.writeln(`\r\nFailed to open terminal: ${e.message}`)
      session.status = 'error'
      this.notify()
    })
  }

  private async connect(session: Session, jobId: string, containerId: string | null, command?: string[]) {
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const wsRoot = await resolveWsRoot()
    // jobId (e.g. "$namespace:$kind:$name" for Kubernetes) contains literal colons — encode both path
    // components rather than interpolating raw, since some proxies/WAFs in front of the Elements
    // server mishandle unencoded colons in a path segment. Transparent server-side: Jetty's JSR-356
    // @ServerEndpoint path-template matching decodes path params automatically, and neither value can
    // ever contain a literal '/', so this can't introduce a spurious segment boundary.
    const path = containerId
      ? `${wsRoot}/service/${encodeURIComponent(jobId)}/${encodeURIComponent(containerId)}`
      : `${wsRoot}/service/${encodeURIComponent(jobId)}`
    const url = `${scheme}://${window.location.host}${path}`

    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    session.ws = ws

    ws.onopen = () => {
      ws.send(JSON.stringify({ sessionSecret: window.__elementsApiClient?.getSessionToken?.(), command }))
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
    runCatchingClose(session.ws)
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
      })),
    h(ToastHistoryMenu))
}

/** Expandable menu (next to the volume/mute controls) showing the shared toast history ring buffer. */
function ToastHistoryMenu() {
  const { toastHistory } = useTerminalSnapshot()
  const [open, setOpen] = React.useState(false)
  const rootRef = React.useRef<HTMLDivElement | null>(null)

  React.useEffect(() => {
    if (!open) return
    function onPointerDown(e: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [open])

  return h('div', { className: 'relative', ref: rootRef },
    h('button', {
      className: 'relative px-2.5 py-1 rounded border text-xs hover:bg-muted transition-colors',
      onClick: () => setOpen((v) => !v),
      title: 'Toast notification history',
    }, '🍞', toastHistory.length > 0 && h('span', {
      className: 'absolute -top-1.5 -right-1.5 min-w-[1rem] px-1 rounded-full bg-primary text-primary-foreground text-[10px] leading-4 text-center',
    }, toastHistory.length)),
    open && h('div', {
      className: 'absolute right-0 mt-1 max-h-96 overflow-y-auto rounded-lg border bg-popover shadow-lg z-50 text-xs',
      style: { width: '28rem' },
    },
      h('div', { className: 'flex items-center justify-between px-3 py-2 border-b sticky top-0 bg-popover' },
        h('span', { className: 'font-semibold' }, 'Toast history'),
        h('button', {
          className: 'text-muted-foreground hover:text-destructive disabled:opacity-40 disabled:hover:text-muted-foreground',
          disabled: toastHistory.length === 0,
          onClick: () => terminalSessionManager.clearToastHistory(),
        }, 'Clear')),
      toastHistory.length === 0
        ? h('div', { className: 'px-3 py-4 text-center text-muted-foreground' }, 'No toasts yet')
        : h('div', { className: 'divide-y' },
            [...toastHistory].reverse().map((t) => h('div', { key: t.id, className: 'px-3 py-2 space-y-0.5' },
              h('div', { className: 'flex items-center justify-between gap-2 text-muted-foreground' },
                h('span', { className: 'font-mono truncate' }, t.sessionLabel),
                h('div', { className: 'flex items-center gap-2 shrink-0' },
                  h('span', {}, new Date(t.timestamp).toLocaleTimeString()),
                  h('button', {
                    className: 'text-muted-foreground hover:text-destructive',
                    title: 'Dismiss this toast',
                    onClick: () => terminalSessionManager.removeToastHistoryEntry(t.id),
                  }, '✕'))),
              h('div', { className: 'flex items-center justify-between gap-2' },
                h('div', { className: 'break-words text-foreground' }, linkifyText(t.message)),
                t.action && h(ToastActionButton, { action: t.action })))))))
}

/**
 * Transient popups for freshly-received toasts, auto-dismissed after `TOAST_POPUP_DURATION_MS`.
 * Mount once near the top of the page hosting `TerminalTabs`/`BellControls` — not per-tab, since a
 * toast should surface even while its originating session isn't the active one.
 */
export function ToastPopups() {
  const { transientToasts } = useTerminalSnapshot()
  if (transientToasts.length === 0) return null
  // Width is an inline style, not a `w-[60rem]` Tailwind class — arbitrary-value utility classes
  // only exist in the host's compiled CSS if that exact class string already happens to appear
  // somewhere in the host's own scanned source (this bundle is fetched at runtime, so it's never
  // scanned itself); an inline style has no such dependency.
  return h('div', { className: 'fixed top-4 right-4 z-[100] space-y-2', style: { width: '60rem' } },
    transientToasts.map((t) => h('div', {
      key: t.id,
      className: 'rounded-lg border bg-popover shadow-lg px-3 py-2 text-xs',
    },
      h('div', { className: 'flex items-center justify-between gap-2' },
        h('span', { className: 'font-mono font-semibold truncate text-muted-foreground' }, t.sessionLabel),
        h('button', {
          className: 'text-muted-foreground hover:text-foreground',
          onClick: () => terminalSessionManager.dismissToastPopup(t.id),
        }, '✕')),
      h('div', { className: 'mt-0.5 flex items-center justify-between gap-2' },
        h('div', { className: 'break-words' }, linkifyText(t.message)),
        t.action && h(ToastActionButton, { action: t.action })))))
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
        'No terminals are currently open. Launch one from a running job’s container list.'))
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
