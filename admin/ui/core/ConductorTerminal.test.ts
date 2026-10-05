/** Regression tests for issue #70: `onOsc()` must wire non-built-in idents to xterm's parser.
 * Before the fix it only recorded the handler in `oscHandlers`, which built-in dispatch consults —
 * so sequences for any other ident were parsed by xterm and silently dropped.
 *
 * xterm and the two addons are mocked: the fake `Terminal.parser.registerOscHandler` records every
 * registration into `registered`, and tests re-dispatch through the captured handlers to exercise
 * the full chain (osc event → host onOsc handlers → built-in default). */

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ConductorTerminalCore } from './ConductorTerminal'

const { registered } = vi.hoisted(() => ({
  registered: [] as { ident: number; handler: (data: string) => boolean }[],
}))

vi.mock('@xterm/xterm', () => ({
  Terminal: vi.fn().mockImplementation(() => ({
    parser: {
      registerOscHandler: (ident: number, handler: (data: string) => boolean) => {
        registered.push({ ident, handler })
      },
    },
    loadAddon: vi.fn(),
    open: vi.fn(),
    onBell: vi.fn(),
    onData: vi.fn(),
    onResize: vi.fn(),
    reset: vi.fn(),
    writeln: vi.fn(),
    focus: vi.fn(),
    dispose: vi.fn(),
    options: {},
  })),
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit() {} } }))
vi.mock('@xterm/addon-web-links', () => ({ WebLinksAddon: class {} }))

function makeCore() {
  return new ConductorTerminalCore({
    jobId: '$ns:job:test',
    sessionSecret: 'secret',
    theme: { background: '#000000', foreground: '#ffffff' },
  })
}

describe('built-in wiring', () => {
  beforeEach(() => { registered.length = 0 })

  it('registers the three built-in idents at construction', () => {
    makeCore()
    expect(registered.map((r) => r.ident).sort((a, b) => a - b)).toEqual([52, 1337, 9001])
  })

  it('dispatches a built-in through the full chain (osc event → host handler → default)', () => {
    const core = makeCore()
    const oscEvents: unknown[] = []
    core.on('osc', (detail) => oscEvents.push(detail))

    const hostClaimed = vi.fn(() => true)
    core.onOsc(9001, hostClaimed)
    const toastEvents: unknown[] = []
    core.on('toast', (detail) => toastEvents.push(detail))

    const registration = registered.find((r) => r.ident === 9001)!
    registration.handler('hello')

    expect(oscEvents).toEqual([{ ident: 9001, data: 'hello' }])
    expect(hostClaimed).toHaveBeenCalledWith('hello')
    // host claimed it → built-in default never ran
    expect(toastEvents).toEqual([])
  })
})

describe('issue #70 — onOsc lazily wires non-built-in idents', () => {
  beforeEach(() => { registered.length = 0 })

  it('registers with xterm on first onOsc for an unknown ident', () => {
    const core = makeCore()
    expect(registered.find((r) => r.ident === 777)).toBeUndefined()
    core.onOsc(777, () => true)
    expect(registered.filter((r) => r.ident === 777)).toHaveLength(1)
  })

  it('delivers the sequence to the handler once wired', () => {
    const core = makeCore()
    const received: string[] = []
    core.onOsc(777, (data) => { received.push(data); return true })

    const registration = registered.find((r) => r.ident === 777)!
    expect(registration.handler('RequestInput;abc')).toBe(true)
    expect(received).toEqual(['RequestInput;abc'])
  })

  it('emits the raw osc event for lazily-wired idents too', () => {
    const core = makeCore()
    const oscEvents: unknown[] = []
    core.on('osc', (detail) => oscEvents.push(detail))
    core.onOsc(777, () => true)
    registered.find((r) => r.ident === 777)!.handler('payload')
    expect(oscEvents).toEqual([{ ident: 777, data: 'payload' }])
  })

  it('falls through to false when the handler does not claim the sequence', () => {
    const core = makeCore()
    core.onOsc(777, () => undefined)
    expect(registered.find((r) => r.ident === 777)!.handler('x')).toBe(false)
  })

  it('does not re-register on a second onOsc for the same ident', () => {
    const core = makeCore()
    core.onOsc(777, () => true)
    core.onOsc(777, () => true)
    expect(registered.filter((r) => r.ident === 777)).toHaveLength(1)
  })

  it('unsubscribing removes the handler without re-registering or breaking dispatch', () => {
    const core = makeCore()
    const off = core.onOsc(777, () => true)
    off()
    core.onOsc(777, () => undefined)
    expect(registered.filter((r) => r.ident === 777)).toHaveLength(1)

    const received: string[] = []
    core.onOsc(777, (data) => { received.push(data); return true })
    registered.find((r) => r.ident === 777)!.handler('again')
    expect(received).toEqual(['again'])
  })

  it('never double-registers a built-in ident via onOsc', () => {
    const core = makeCore()
    core.onOsc(9001, () => true)
    core.onOsc(52, () => true)
    core.onOsc(1337, () => true)
    for (const ident of [9001, 52, 1337]) {
      expect(registered.filter((r) => r.ident === ident)).toHaveLength(1)
    }
  })
})
