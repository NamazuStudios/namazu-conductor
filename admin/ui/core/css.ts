/** xterm.css injection, shared by the dashboard bundle and the standalone widget so both get an
 * identical, idempotent stylesheet (`#conductor-xterm-styles`). */

import xtermCss from '@xterm/xterm/css/xterm.css?inline'

export function injectXtermStyles() {
  if (document.getElementById('conductor-xterm-styles')) return
  const style = document.createElement('style')
  style.id = 'conductor-xterm-styles'
  style.textContent = xtermCss
  document.head.appendChild(style)
}