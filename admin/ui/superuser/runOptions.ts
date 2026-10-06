/** Pure (React-free) logic behind the advanced run options — kept out of RunForm.ts so vitest can
 * unit-test the request-payload derivation without resolving `react` (the dashboard bundle reads
 * `window.React`; the package isn't installed, only its types). */

import type { KVPair } from './ui'
import type { PlacementInput } from './types'

export const TERMINAL_COMMAND_SUGGESTION = ['/bin/sh']

/** One environment-variable row in the RunForm. [container] is the profile container the entry
 * targets (issue #73); `''` — the default — means unqualified, i.e. the primary container via the
 * flat `environment` field. */
export interface EnvPair extends KVPair {
  container: string
}

export interface AdvancedOptions {
  args: string[]
  command: string[]
  env: EnvPair[]
  metadata: KVPair[]
  placement: PlacementInput
  tty: boolean
  /** Whether to add the operator's own session secret to the env at launch time — see
   * `namazu.conductor/session-secret-env`/`enable-session-secret` in kubernetes/README.md. Only
   * meaningful (and only ever shown as a checkbox) when the profile declares an env var to put it in. */
  injectSessionSecret: boolean
  /** Which containers the session secret lands in (container ids; `''` = the primary container via
   * the flat `environment` field). Defaults to primary-only. */
  sessionSecretContainers: string[]
}

export function defaultAdvancedOptions(impliedTerminal: boolean, injectSessionSecretByDefault = false): AdvancedOptions {
  return {
    args: [],
    command: impliedTerminal ? TERMINAL_COMMAND_SUGGESTION : [],
    env: [],
    metadata: [],
    placement: { type: '', region: '', ip: '', lat: '', lon: '' },
    tty: impliedTerminal,
    injectSessionSecret: injectSessionSecretByDefault,
    sessionSecretContainers: [''],
  }
}

/** `undefined` (omitted), not `[]`, when the caller never touched the field — see AvailableJobsPage. */
export function derivePlacementList(placement: PlacementInput): unknown[] {
  if (placement.type === 'REGION' && placement.region.trim()) {
    return [{ type: 'REGION', region: placement.region.trim() }]
  }
  if (placement.type === 'IP_ADDRESS' && placement.ip.trim()) {
    return [{ type: 'IP_ADDRESS', ip: placement.ip.trim() }]
  }
  if (placement.type === 'LAT_LON' && placement.lat && placement.lon) {
    return [{ type: 'LAT_LON', latitude: parseFloat(placement.lat), longitude: parseFloat(placement.lon) }]
  }
  return []
}

/** The flat (primary-container) env to send, or `undefined` when no unqualified row was filled in. */
export function deriveEnvironment(env: EnvPair[]): Record<string, string> | undefined {
  const environment: Record<string, string> = {}
  env.forEach((pair) => {
    const key = pair.key.trim()
    if (!key || pair.container) return
    environment[key] = pair.value
  })
  return Object.keys(environment).length > 0 ? environment : undefined
}

/** The per-container env to send (issue #73), or `undefined` when no container-qualified row was
 * filled in. Rows naming the primary container go here too — the server merges the flat field over
 * the container's profile-declared env and this map over both, so primary-qualified rows are equally
 * correct here and keep their explicitness. */
export function deriveContainerEnvironment(env: EnvPair[]): Record<string, Record<string, string>> | undefined {
  const containerEnv: Record<string, Record<string, string>> = {}
  env.forEach((pair) => {
    const key = pair.key.trim()
    if (!key || !pair.container) return
    ;(containerEnv[pair.container] ??= {})[key] = pair.value
  })
  return Object.keys(containerEnv).length > 0 ? containerEnv : undefined
}

/** The launch payload for the session secret: one flat/qualified entry per container selected in
 * [sessionSecretContainers], each set to [secret] under [sessionSecretEnvVar]. Empty result when
 * the checkbox is off, no secret, or no env var name to target. */
export function deriveSessionSecretEnv(
  injectSessionSecret: boolean,
  sessionSecretContainers: string[],
  sessionSecretEnvVar: string,
  secret: string | null
): { environment: Record<string, string>; containerEnvironment: Record<string, Record<string, string>> } {
  if (!injectSessionSecret || !secret || !sessionSecretEnvVar) return { environment: {}, containerEnvironment: {} }
  const environment: Record<string, string> = {}
  const containerEnvironment: Record<string, Record<string, string>> = {}
  for (const container of sessionSecretContainers) {
    if (container) (containerEnvironment[container] ??= {})[sessionSecretEnvVar] = secret
    else environment[sessionSecretEnvVar] = secret
  }
  return { environment, containerEnvironment }
}
