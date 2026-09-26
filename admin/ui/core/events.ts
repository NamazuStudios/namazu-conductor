/** Minimal typed event emitter used by [ConductorTerminal] and the shared core. */

import type { TerminalEventMap, TerminalEventName, TerminalEventHandler } from './types'

type HandlerMap = {
  [K in TerminalEventName]: Set<TerminalEventHandler<K>>
}

export class Emitter {
  private handlers: HandlerMap = {
    status: new Set(),
    bell: new Set(),
    toast: new Set(),
    'open-url': new Set(),
    copy: new Set(),
    osc: new Set(),
    error: new Set(),
  }

  on<K extends TerminalEventName>(name: K, handler: TerminalEventHandler<K>): () => void {
    this.handlers[name].add(handler)
    return () => this.handlers[name].delete(handler)
  }

  emit<K extends TerminalEventName>(name: K, detail: TerminalEventMap[K]) {
    for (const handler of [...this.handlers[name]]) {
      try { (handler as TerminalEventHandler<K>)(detail) } catch { /* listener fault */ }
    }
  }
}