/** Host-page theme detection and xterm palette derivation (framework-agnostic core). */

import type { TerminalTheme } from './types'

/**
 * Resolves the theme colours for the terminal, preferring the host page's actual
 * background/foreground (probed via a hidden `bg-background text-foreground` element, which the
 * Elements dashboard styles — including in `.dark`/`.light` mode), falling back to a
 * `prefers-color-scheme`-driven palette. Mirrors the dashboard's original `getTerminalTheme`.
 */
export function probeHostThemeDarkFallback(): TerminalTheme {
  try {
    const probe = document.createElement('div')
    probe.className = 'bg-background text-foreground'
    probe.style.position = 'absolute'
    probe.style.visibility = 'hidden'
    probe.style.pointerEvents = 'none'
    document.body.appendChild(probe)
    const style = getComputedStyle(probe)
    const bg = style.backgroundColor
    const fg = style.color
    document.body.removeChild(probe)
    // Tailwind only emits `background-color` for the `bg-*` utilities; an unstyled host yields
    // `rgba(0, 0, 0, 0)` (transparent), which we treat as "no host chroma".
    if (bg && bg !== 'rgba(0, 0, 0, 0)' && fg) return { background: bg, foreground: fg }
  } catch { /* no document */ }
  return prefersDark()
    ? { background: '#0a0a0a', foreground: '#ededed' }
    : { background: '#ffffff', foreground: '#09090b' }
}

export function prefersDark(): boolean {
  return typeof matchMedia !== 'undefined' && matchMedia('(prefers-color-scheme: dark)').matches
}

/** Re-applies the current host theme (theme object or neutral trigger) to every registered
 * terminal; call via [watchHostTheme]. */
export function applyHostTheme(terminals: { setTheme(theme: TerminalTheme): void }[]) {
  const theme = probeHostThemeDarkFallback()
  terminals.forEach((t) => t.setTheme(theme))
}

/** Watches host theme changes (`.dark`/`.light` class toggles and OS scheme changes) and re-themes
 * the given terminals. Returns a stop function. */
export function watchHostTheme(apply: (theme: TerminalTheme) => void): () => void {
  const observer = new MutationObserver(() => apply(probeHostThemeDarkFallback()))
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  const media = typeof matchMedia !== 'undefined' ? matchMedia('(prefers-color-scheme: dark)') : null
  const onChange = () => apply(probeHostThemeDarkFallback())
  media?.addEventListener('change', onChange)
  return () => { observer.disconnect(); media?.removeEventListener('change', onChange) }
}