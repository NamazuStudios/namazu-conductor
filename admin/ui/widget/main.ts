/**
 * Standalone embeddable ConductorTerminal bundle (UMD/IIFE). Load with a plain
 * `<script src="/app/ui/{admin-element}/widget/conductor-terminal.js">`, then:
 *
 *   const term = ConductorTerminal.mount(document.getElementById('console'), {
 *     sessionSecret: () => localStorage.getItem('elementsSession') ?? '',
 *     jobId: 'ns:job:run',
 *   })
 *
 * The bundle carries its own xterm + OSC engine (no React, no host dependency). Built-in default
 * handlers (toast popups, bell chime, window.open, clipboard) run unless disabled via options;
 * the same typed events (`status`/`bell`/`toast`/`open-url`/`copy`/`osc`/`error`) always fire so a
 * host can build its own UI instead — see admin/README.md's "Embedding the terminal widget".
 */

import { ConductorTerminalCore } from '../core/ConductorTerminal'
import { injectXtermStyles } from '../core/css'
import type { ConductorTerminal, ConductorTerminalOptions } from '../core/types'

// Resolved against THIS bundle's own script URL (see admin/ui/superuser/terminal.ts's identical
// trick), so the default sound asset co-located in the widget directory is found regardless of
// where the host page lives.
const BUNDLE_BASE_URL = (document.currentScript as HTMLScriptElement | null)?.src ?? window.location.href
const BELL_SOUND_PATH = new URL('complete.oga', BUNDLE_BASE_URL).href

export const CONDUCTOR_TERMINAL_VERSION = '1.0.0'

/** Minimal built-in toast popup, auto-dismissed, pinned top-right of the viewport inside the
 * host page. A host that wants richer toasts (history ring, action buttons) subscribes to the
 * `toast` event instead and sets `toasts: false`. */
function makeToastHost() {
  const stack = document.createElement('div')
  stack.style.cssText = 'position:fixed;top:8px;right:8px;z-index:9999;display:flex;flex-direction:column;gap:6px;max-width:min(28rem,90vw);'
  document.body.appendChild(stack)
  const timers = new Map<HTMLDivElement, number>()
  function dismiss(el: HTMLDivElement) {
    window.clearTimeout(timers.get(el))
    timers.delete(el)
    el.remove()
  }
  return {
    push(message: string) {
      const el = document.createElement('div')
      el.setAttribute('role', 'status')
      el.style.cssText = 'background:var(--popover,#1f1f23);color:var(--popover-foreground,#ececf1);' +
        'border:1px solid var(--border,#34343a);border-radius:6px;padding:8px 10px;font-size:12px;' +
        'font-family:ui-monospace,SFMono-Regular,Menlo,monospace;box-shadow:0 4px 16px rgba(0,0,0,.35);' +
        'white-space:pre-wrap;word-break:break-word;cursor:pointer;'
      el.textContent = message
      el.addEventListener('click', () => dismiss(el))
      stack.appendChild(el)
      while (stack.children.length > 3) stack.firstElementChild?.remove()
      timers.set(el, window.setTimeout(() => dismiss(el), 6000))
    },
    dispose() {
      timers.forEach((t) => window.clearTimeout(t))
      stack.remove()
    },
  }
}

function mount(container: HTMLElement, options: ConductorTerminalOptions): ConductorTerminal {
  injectXtermStyles()

  const host = document.createElement('div')
  host.style.width = '100%'
  host.style.height = '100%'
  container.append(host)

  const core = new ConductorTerminalCore(options)
  const toastHost = options.toasts === false ? null : makeToastHost()

  if (options.bells !== false) {
    core.on('bell', () => {
      const audio = new Audio(BELL_SOUND_PATH)
      audio.volume = 0.5
      void audio.play().catch(() => {})
    })
  }
  if (options.openUrl !== false) {
    core.on('open-url', ({ url }) => void window.open(url, '_blank', 'noopener,noreferrer'))
  }
  if (options.clipboard !== false) {
    core.on('copy', ({ text }) => void navigator.clipboard?.writeText(text).catch(() => {}))
  }
  if (toastHost) core.on('toast', ({ message }) => toastHost.push(message))

  // Host the terminal DOM inside our wrapper so `mount` can own cleanup regardless of what the
  // host page does to `container`. The core exposes `status` and all events on `term` itself.
  host.append(core.host)
  core.connect().finally(() => {
    core.fit()
    core.focus()
  })

  const disposed = { value: false }
  const coreDispose = core.dispose.bind(core)
  const t: ConductorTerminal = core
  t.dispose = () => {
    if (disposed.value) return
    disposed.value = true
    toastHost?.dispose()
    coreDispose()
    host.remove()
  }

  return t
}

;(window as unknown as Record<string, unknown>).ConductorTerminal = {
  version: CONDUCTOR_TERMINAL_VERSION,
  mount,
}