import React from 'react'
import xtermCss from '@xterm/xterm/css/xterm.css?inline'
import { TerminalTabs, terminalSessionManager, playBell } from './terminal'
import { readAndClearParams } from './nav'

const h = React.createElement

function injectXtermStyles() {
  if (document.getElementById('conductor-xterm-styles')) return
  const style = document.createElement('style')
  style.id = 'conductor-xterm-styles'
  style.textContent = xtermCss
  document.head.appendChild(style)
}

function BellControls() {
  const [volume, setVolume] = React.useState(() => terminalSessionManager.getBellVolume())

  function handleVolumeChange(e: React.ChangeEvent<HTMLInputElement>) {
    const next = Number(e.target.value) / 100
    setVolume(next)
    terminalSessionManager.setBellVolume(next)
  }

  return h('div', { className: 'flex items-center gap-3' },
    h('button', {
      className: 'px-2.5 py-1 rounded border text-xs hover:bg-muted transition-colors',
      onClick: () => playBell(),
      title: 'Play the terminal bell sound',
    }, '🔔'),
    h('label', { className: 'flex items-center gap-1.5 text-xs text-muted-foreground' },
      volume === 0 ? '🔇' : '🔊',
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

export function TerminalsPage() {
  React.useEffect(() => {
    injectXtermStyles()

    const params = readAndClearParams()
    const jobId = params?.get('jobId')
    const element = params?.get('element')
    if (jobId && element) {
      const containerId = params?.get('containerId') || null
      const label = params?.get('label') || jobId.slice(0, 8)
      terminalSessionManager.open(element, jobId, containerId, label)
    }
  }, [])

  return h('div', { className: 'p-6 space-y-4' },
    h('div', { className: 'flex justify-end' }, h(BellControls)),
    h(TerminalTabs))
}
