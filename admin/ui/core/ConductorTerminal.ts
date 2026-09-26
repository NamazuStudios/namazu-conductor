/** The framework-agnostic ConductorTerminal engine: owns one xterm.js instance + its WebSocket,
 * dispatches OSC sequences (built-in idents + host overrides), and emits typed events. The
 * dashboard's tab/drawer UI and the standalone `ConductorTerminal.mount()` widget are both thin
 * adapters over this class, so the terminal behaviour can never drift between the two.
 *
 * This class stays side-effect-free on purpose — it only *emits*:
 *  - `bell`     → BEL character received
 *  - `toast`    → OSC 9001 `Ps;message` (issue #37)
 *  - `open-url` → OSC 1337 `OpenURL=<url>` (issue #34)
 *  - `copy`     → OSC 52 `Ps;c;base64`
 *  - `osc`      → any OSC ident (raw), before host `onOsc` handlers and built-in defaults
 * Any visible built-in behaviour (bell chime, toast popup, window.open, clipboard write) is the
 * *adapter's* job: widget/main.ts implements simple defaults gated by its `toasts`/`bells`/
 * `openUrl`/`clipboard` options, the dashboard's terminal.ts implements its richer equivalents.
 * The events always fire regardless, so either side can subscribe even with a built-in disabled.
 */

import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { Emitter } from './events'
import { probeHostThemeDarkFallback, watchHostTheme } from './theme'
import { builtInOscDefaults, TOAST_OSC_IDENT, OPEN_URL_OSC_IDENT, CLIPBOARD_OSC_IDENT } from './osc'
import type { ConductorTerminal, ConductorTerminalOptions, OscHandler, TerminalTheme } from './types'

const DEFAULT_RESOLVE_WS_ROOT = async (): Promise<string> => {
  // Same discovery path the dashboard uses (`GET /api/rest/elements/system`) so a same-origin
  // mount() works with zero config; cross-origin embedders pass `wsRoot` explicitly.
  const res = await fetch('/api/rest/elements/system', { credentials: 'include' }).catch(() => null)
  if (res && res.ok) {
    const elements = await res.json().catch(() => null) as Array<{ definition?: { name?: string }; attributes?: Record<string, string> }> | null
    const root = elements?.find((e) => e.definition?.name === 'dev.getelements.conductor.admin')
      ?.attributes?.['dev.getelements.elements.element.ws.root']
    if (root && root.length > 0) return root
  }
  return '/conductor/ws'
}

export class ConductorTerminalCore implements ConductorTerminal {
  readonly sessionLabel: string
  readonly host: HTMLElement
  /** Low-level access for the dashboard adapter (tab drawer, resize-on-host-resize). */
  readonly term: Terminal
  readonly fitAddon: FitAddon
  status: ConductorTerminal['status'] = 'connecting'

  private ws: WebSocket | null = null
  private disposed = false
  private stopThemeWatch: () => void = () => {}
  private oscHandlers = new Map<number, OscHandler[]>()
  private emitter = new Emitter()
  private copied: string | null = null
  private options: ConductorTerminalOptions

  constructor(options: ConductorTerminalOptions) {
    this.options = options
    this.sessionLabel = options.label ?? options.jobId
    this.host = document.createElement('div')
    this.host.style.width = '100%'
    this.host.style.height = '100%'
    this.term = new Terminal({ convertEol: true, cursorBlink: true, theme: this.resolveTheme() })
    this.fitAddon = new FitAddon()
    this.term.loadAddon(this.fitAddon)
    // Linkifies URLs in ordinary program output; a real click, never popup-blocked.
    this.term.loadAddon(new WebLinksAddon())
    this.term.open(this.host)

    this.registerBuiltIns()
    // BEL → typed event so hosts can decide their own chime/UI (the core stays UI-free).
    this.term.onBell(() => this.emitter.emit('bell', undefined))

    if (this.options.theme === undefined || this.options.theme === 'auto') {
      this.stopThemeWatch = watchHostTheme((theme) => this.setTheme(theme))
      this.setTheme(probeHostThemeDarkFallback())
    }
  }

  on<K extends keyof import('./types').TerminalEventMap>(name: K, handler: import('./types').TerminalEventHandler<K>): () => void {
    return this.emitter.on(name, handler)
  }

  onOsc(ident: number, handler: OscHandler): () => void {
    const handlers = this.oscHandlers.get(ident) ?? []
    handlers.push(handler)
    this.oscHandlers.set(ident, handlers)
    return () => {
      const list = this.oscHandlers.get(ident)
      if (list) this.oscHandlers.set(ident, list.filter((h) => h !== handler))
    }
  }

  get lastCopiedText(): string | null {
    return this.copied
  }

  write(data: string): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(new TextEncoder().encode(data))
  }

  resize(cols: number, rows: number): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify({ type: 'resize', cols, rows }))
  }

  fit(): void {
    this.fitAddon.fit()
  }

  focus(): void {
    this.term.focus()
  }

  setTheme(theme: TerminalTheme): void {
    this.term.options.theme = theme
  }

  dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.stopThemeWatch()
    try { this.ws?.close() } catch { /* already closed */ }
    this.ws = null
    this.term.dispose()
    this.host.replaceChildren()
  }

  /** Opens the WebSocket and starts the session. `host` must be in the DOM first (`fit` needs layout). */
  async connect(): Promise<void> {
    if (this.disposed) return
    this.term.writeln(`Connecting to ${this.sessionLabel}…`)
    const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
    const wsRoot = this.options.wsRoot ?? await (this.options.resolveWsRoot ?? DEFAULT_RESOLVE_WS_ROOT)()
    // jobId (e.g. "$namespace:$kind:$name") contains literal colons — encode both path components;
    // Jetty's JSR-356 template matching decodes them, and neither value contains a '/'.
    const path = this.options.containerId
      ? `${wsRoot}/service/${encodeURIComponent(this.options.jobId)}/${encodeURIComponent(this.options.containerId)}`
      : `${wsRoot}/service/${encodeURIComponent(this.options.jobId)}`
    const url = `${scheme}://${window.location.host}${path}`

    try {
      const sessionSecret = typeof this.options.sessionSecret === 'function'
        ? await this.options.sessionSecret()
        : this.options.sessionSecret
      const ws = new WebSocket(url)
      this.ws = ws
      ws.binaryType = 'arraybuffer'

      ws.onopen = () => {
        ws.send(JSON.stringify({ sessionSecret, command: this.options.command ?? undefined }))
        this.status = 'open'
        this.term.reset()
        this.emitter.emit('status', 'open')
      }
      ws.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) this.term.write(new Uint8Array(event.data))
      }
      ws.onclose = (event) => {
        this.status = 'closed'
        this.term.writeln(`\r\n[connection closed${event.reason ? `: ${event.reason}` : ''}]`)
        this.emitter.emit('status', 'closed')
      }
      ws.onerror = () => {
        this.status = 'error'
        this.emitter.emit('status', 'error')
      }
      this.term.onData((data) => { if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(data)) })
      this.term.onResize(({ cols, rows }) => { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'resize', cols, rows })) })
    } catch (e) {
      this.status = 'error'
      this.emitter.emit('error', e instanceof Error ? e.message : String(e))
      this.emitter.emit('status', 'error')
    }
  }

  private resolveTheme(): TerminalTheme {
    const t = this.options.theme
    if (t === undefined || t === 'auto') return probeHostThemeDarkFallback()
    return typeof t === 'function' ? t() : t
  }

  private registerBuiltIns() {
    // later set from options; not a theme unless provided
    const defaults = builtInOscDefaults({
      toast: (message) => this.emitter.emit('toast', { message, sessionLabel: this.sessionLabel }),
      openUrl: (url) => this.emitter.emit('open-url', { url }),
      copy: (text) => { this.copied = text; this.emitter.emit('copy', { text }) },
    })

    const register = (ident: number, builtIn: OscHandler) => {
      this.term.parser.registerOscHandler(ident, (data) => {
        this.emitter.emit('osc', { ident, data })
        const custom = this.oscHandlers.get(ident)
        if (custom && custom.some((h) => h(data) === true)) return true
        return builtIn(data) ?? false
      })
    }

    register(TOAST_OSC_IDENT, defaults[TOAST_OSC_IDENT])
    register(OPEN_URL_OSC_IDENT, defaults[OPEN_URL_OSC_IDENT])
    register(CLIPBOARD_OSC_IDENT, defaults[CLIPBOARD_OSC_IDENT])
  }
}