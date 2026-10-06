/** Unit tests for the advanced-run-options payload derivation (issue #73: per-container env). */

import { describe, expect, it } from 'vitest'
import {
  deriveEnvironment,
  deriveContainerEnvironment,
  deriveSessionSecretEnv,
  defaultAdvancedOptions,
} from './runOptions'
import type { EnvPair } from './runOptions'

const env = (key: string, value: string, container = ''): EnvPair => ({ key, value, container })

describe('deriveEnvironment (flat, primary-container)', () => {
  it('collects unqualified rows and trims keys', () => {
    expect(deriveEnvironment([env(' FOO ', 'bar'), env('BAZ', 'qux')]))
      .toEqual({ FOO: 'bar', BAZ: 'qux' })
  })

  it('skips container-qualified rows and blank keys (undefined when nothing qualifies)', () => {
    expect(deriveEnvironment([env('FOO', 'bar', 'agent-proxy'), env('', 'x')]))
      .toBeUndefined()
  })

  it('is undefined when nothing qualifies', () => {
    expect(deriveEnvironment([])).toBeUndefined()
    expect(deriveEnvironment([env('FOO', 'bar', 'sidecar')])).toBeUndefined()
  })
})

describe('deriveContainerEnvironment (per-container, issue #73)', () => {
  it('groups qualified rows by container', () => {
    expect(deriveContainerEnvironment([
      env('NAMAZU_CLOUD_SESSION_SECRET', 's3cret', 'agent-proxy'),
      env('OTHER', 'v', 'agent-proxy'),
      env('PRIMARY_ONLY', 'v', 'agent'),
    ])).toEqual({
      'agent-proxy': { NAMAZU_CLOUD_SESSION_SECRET: 's3cret', OTHER: 'v' },
      'agent': { PRIMARY_ONLY: 'v' },
    })
  })

  it('is undefined when nothing is qualified', () => {
    expect(deriveContainerEnvironment([env('FOO', 'bar')])).toBeUndefined()
  })

  it('drops blank keys even when qualified', () => {
    expect(deriveContainerEnvironment([env(' ', 'v', 'sidecar')])).toBeUndefined()
  })
})

describe('deriveSessionSecretEnv', () => {
  const ENV_VAR = 'NAMAZU_CLOUD_SESSION_SECRET'

  it('targets the primary container by default (backward compatible)', () => {
    expect(deriveSessionSecretEnv(true, [''], ENV_VAR, 'tok'))
      .toEqual({ environment: { [ENV_VAR]: 'tok' }, containerEnvironment: {} })
  })

  it('lands once per selected sidecar', () => {
    expect(deriveSessionSecretEnv(true, ['agent', 'agent-proxy'], ENV_VAR, 'tok'))
      .toEqual({
        environment: {},
        containerEnvironment: { agent: { [ENV_VAR]: 'tok' }, 'agent-proxy': { [ENV_VAR]: 'tok' } },
      })
  })

  it('mixes primary and sidecar targets', () => {
    const { environment, containerEnvironment } = deriveSessionSecretEnv(true, ['', 'agent-proxy'], ENV_VAR, 'tok')
    expect(environment).toEqual({ [ENV_VAR]: 'tok' })
    expect(containerEnvironment).toEqual({ 'agent-proxy': { [ENV_VAR]: 'tok' } })
  })

  it('is empty when the checkbox is off or there is no secret', () => {
    expect(deriveSessionSecretEnv(false, [''], ENV_VAR, 'tok'))
      .toEqual({ environment: {}, containerEnvironment: {} })
    expect(deriveSessionSecretEnv(true, [''], ENV_VAR, null))
      .toEqual({ environment: {}, containerEnvironment: {} })
  })
})

describe('defaultAdvancedOptions', () => {
  it('defaults to primary-only secret targeting and no env rows', () => {
    const options = defaultAdvancedOptions(false, true)
    expect(options.env).toEqual([])
    expect(options.sessionSecretContainers).toEqual([''])
    expect(options.injectSessionSecret).toBe(true)
  })
})
