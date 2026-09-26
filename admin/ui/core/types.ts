/** Framework-agnostic public types for the ConductorTerminal widget and shared core. */

export type TerminalStatus = 'connecting' | 'open' | 'closed' | 'error'

/** Maps (via the documented convention of an element thanks) to the exact shape the admin's
 * TerminalSessionHandler first-frame auth message expects. */
export interface TerminalInit {
  sessionSecret?: string
  command?: string[]
}

export interface ToastDetail {
  message: string
  sessionLabel: string
}

export interface OpenUrlDetail {
  url: string
}

export interface CopyDetail {
  text: string
}

export interface OscDetail {
  /** The OSC ident (the `Ps` number), e.g. 1337 */ 
  ident: number
  /** The raw payload after the ident's `;`. */
  data: string
}

export type TerminalEventMap = {
  status: TerminalStatus
  bell: void
  toast: ToastDetail
  'open-url': OpenUrlDetail
  copy: CopyDetail
  osc: OscDetail
  error: string
}

export type TerminalEventName = keyof TerminalEventMap
export type TerminalEventHandler<K extends TerminalEventName> = (detail: TerminalEventMap[K]) => void

/** Return `true` to claim the sequence (stop it reaching built-in defaults); `false`/`undefined`
 * lets the built-in handler for that ident run. Mirrors xterm.js's own OSC contract. */
export type OscHandler = (data: string) => boolean | void

export interface TerminalTheme {
  background: string
  foreground: string
  cursor?: string
  cursorAccent?: string
  selectionBackground?: string
  selectionInactiveBackground?: string
}

export interface ConductorTerminalOptions {
  /** Valid Elements session secret (superuser today) or a zero-arg sync/async producer of one. */
  sessionSecret: string | (() => string | Promise<string>)
  /** The running job id to attach to (its primary container unless `containerId` is set). */
  jobId: string
  /** Optional specific container within the job; defaults to the primary container. */
  containerId?: string
  /** Optional exec override (like `kubectl exec <job> -- <command>`); defaults to attach. */
  command?: string[]
  /** Overrides the discovered `/conductor/ws` prefix (e.g. a fully-qualified `wss://…` base URL
   * when embedding cross-origin, since same-origin attribute discovery can't run there). */
  wsRoot?: string
  /** Resolver for the WS root when `wsRoot` isn't given. Defaults to same-origin attribute
   * discovery (`/api/rest/elements/system`), matching the dashboard. */
  resolveWsRoot?: () => Promise<string>
  /** Resolver for the terminal host labels, used to label toasts. Defaults to the job id. */
  label?: string
  /** `'auto'` (default) follows the host page/`prefers-color-scheme`; a concrete palette or a
   * zero-arg producer is honoured as-is. */
  theme?: 'auto' | TerminalTheme | (() => TerminalTheme)
  /** Enable the widget adapter's built-in default handlers (`widget/main.ts`). Defaults `true`;
   * set to `false` when the host wants to render its own UI off the (always-emitted) events. The
   * core engine itself is side-effect-free regardless. */
  bells?: boolean
  toasts?: boolean
  openUrl?: boolean
  clipboard?: boolean
}

/** What the widget returns from `mount()`; also the internal shape the dashboard adapter wraps. */
export interface ConductorTerminal {
  readonly status: TerminalStatus
  readonly sessionLabel: string
  readonly host: HTMLElement
  on<K extends TerminalEventName>(name: K, handler: TerminalEventHandler<K>): () => void
  /** Register a handler for an extended/custom OSC ident. Returning `true` prevents the built-in
   * default for that ident (if any). Built-in idents: 9001 (toast), 1337 `OpenURL=` (open url),
   * 52 (clipboard copy). */
  onOsc(ident: number, handler: OscHandler): () => void
  /** The last clipboard text really copied via OSC 52, if any. */
  get lastCopiedText(): string | null
  write(data: string): void
  resize(cols: number, rows: number): void
  fit(): void
  focus(): void
  setTheme(theme: TerminalTheme): void
  /** Closes the WebSocket, disposes the xterm, detaches from the host node. Idempotent. */
  dispose(): void
}