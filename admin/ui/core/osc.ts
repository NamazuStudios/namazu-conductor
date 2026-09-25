/** OSC (Operating System Command) handling in-band over the pty stream.
 *
 * A container writes `\e]ID;payload\a` (or `\x9c` ST) to its own stdout to trigger a handler.
 * The whole sequence travels unmodified through the binary pty/WebSocket channel, so this needs
 * no server-side support — xterm.js's parser reassembles OSC sequences split across frames.
 *
 * Dispatch (per sequence): `osc` event listeners → host `onOsc(ident)` handlers (in registration
 * order) → built-in defaults. A handler returning `true` claims the sequence and stops the chain.
 *
 * Built-in ident assigns (kept in sync with the dashboard):
 *  - 9001 — toast notification (issue #37)
 *  - 1337 — iTerm2-style `OpenURL=<url>` (issue #34)
 *  - 52   — clipboard copy (OSC 52).
 */

import type { OscHandler } from './types'

export const TOAST_OSC_IDENT = 9001
export const OPEN_URL_OSC_IDENT = 1337
export const OPEN_URL_OSC_PREFIX = 'OpenURL='
export const CLIPBOARD_OSC_IDENT = 52

/** Only http(s) URLs are ever surfaced — defense in depth against an open redirect from
 * container-controlled output. */
export function parseAllowedUrl(raw: string): string | null {
  try {
    const parsed = new URL(raw)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : null
  } catch {
    return null
  }
}

/** Decodes an OSC 52 payload (base64 of UTF-8 bytes) back to text; `null` if not valid base64. */
export function decodeBase64Utf8(base64: string): string | null {
  try {
    const binary = atob(base64)
    const bytes = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
    return new TextDecoder('utf-8').decode(bytes)
  } catch {
    return null
  }
}

interface BuiltInHandlers {
  toast: (message: string) => void
  openUrl: (url: string) => void
  copy: (text: string) => void
}

export function builtInOscDefaults(handlers: BuiltInHandlers): Record<number, OscHandler> {
  return {
    [TOAST_OSC_IDENT]: (data) => {
      const message = data.trim()
      if (message) handlers.toast(message)
      return true
    },
    [OPEN_URL_OSC_IDENT]: (data) => {
      const payload = data.trim()
      if (payload.startsWith(OPEN_URL_OSC_PREFIX)) {
        const url = parseAllowedUrl(payload.slice(OPEN_URL_OSC_PREFIX.length))
        if (url) handlers.openUrl(url)
      }
      return true
    },
    [CLIPBOARD_OSC_IDENT]: (data) => {
      const semicolon = data.indexOf(';')
      if (semicolon < 0) return true
      const payload = data.slice(semicolon + 1)
      if (payload === '?' || payload === '') return true // read-back query / clear — nothing to relay
      const text = decodeBase64Utf8(payload)
      if (text) handlers.copy(text)
      return true
    },
  }
}