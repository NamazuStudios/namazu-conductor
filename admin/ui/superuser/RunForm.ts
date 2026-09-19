import React from 'react'
import { KVEditor, ListEditor } from './ui'
import type { KVPair } from './ui'
import type { PlacementInput } from './types'

const h = React.createElement
const inputClass = 'w-full rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary'
const labelClass = 'block text-xs font-medium text-muted-foreground mb-1'

export const TERMINAL_COMMAND_SUGGESTION = ['/bin/sh']

export interface AdvancedOptions {
  args: string[]
  command: string[]
  env: KVPair[]
  placement: PlacementInput
  tty: boolean
  /** Whether to add the operator's own session secret to `env` at launch time — see
   * `namazu.conductor/session-secret-env`/`enable-session-secret` in kubernetes/README.md. Only
   * meaningful (and only ever shown as a checkbox) when the profile declares an env var to put it in. */
  injectSessionSecret: boolean
}

export function defaultAdvancedOptions(impliedTerminal: boolean, injectSessionSecretByDefault = false): AdvancedOptions {
  return {
    args: [],
    command: impliedTerminal ? TERMINAL_COMMAND_SUGGESTION : [],
    env: [],
    placement: { type: '', region: '', ip: '', lat: '', lon: '' },
    tty: impliedTerminal,
    injectSessionSecret: injectSessionSecretByDefault,
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

/**
 * Plain controlled-fields editor for the advanced run options — no submit/cancel, no internal state,
 * no own `executeJob` call. The single "Run" button (in `AvailableJobsPage`'s `ProfileRow`) reads
 * whatever's currently here when clicked; this component just edits `props.value` via `props.onChange`.
 */
export function RunForm(props: {
  value: AdvancedOptions
  onChange: (value: AdvancedOptions) => void
  /** Env var name from `namazu.conductor/session-secret-env` — absent hides the checkbox entirely. */
  sessionSecretEnvVar?: string
}) {
  const { value, onChange, sessionSecretEnvVar } = props

  const updateListItem = (key: 'args' | 'command', i: number, val: string) =>
    onChange({ ...value, [key]: value[key].map((v, j) => (j === i ? val : v)) })
  const addListItem = (key: 'args' | 'command') =>
    onChange({ ...value, [key]: [...value[key], ''] })
  const removeListItem = (key: 'args' | 'command', i: number) =>
    onChange({ ...value, [key]: value[key].filter((_, j) => j !== i) })

  const setPlacementField = (key: keyof PlacementInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    onChange({ ...value, placement: { ...value.placement, [key]: e.target.value } })

  function handleTtyToggle(e: React.ChangeEvent<HTMLInputElement>) {
    const checked = e.target.checked
    onChange({
      ...value,
      tty: checked,
      command: checked && value.command.length === 0 ? TERMINAL_COMMAND_SUGGESTION : value.command,
    })
  }

  return h('div', { className: 'space-y-3' },
    h('div', { className: 'grid grid-cols-2 gap-3' },
      h('div', null,
        h('label', { className: labelClass }, 'Command override'),
        h(ListEditor, {
          items: value.command,
          onChange: (i, v) => updateListItem('command', i, v),
          onAdd: () => addListItem('command'),
          onRemove: (i) => removeListItem('command', i),
        })),
      h('div', null,
        h('label', { className: labelClass }, 'Args'),
        h(ListEditor, {
          items: value.args,
          onChange: (i, v) => updateListItem('args', i, v),
          onAdd: () => addListItem('args'),
          onRemove: (i) => removeListItem('args', i),
        }))),

    h('div', null,
      h('label', { className: labelClass }, 'Environment variables'),
      h(KVEditor, {
        items: value.env,
        onChangeKey: (i, v) => onChange({ ...value, env: value.env.map((p, j) => (j === i ? { ...p, key: v } : p)) }),
        onChangeValue: (i, v) => onChange({ ...value, env: value.env.map((p, j) => (j === i ? { ...p, value: v } : p)) }),
        onAdd: () => onChange({ ...value, env: [...value.env, { key: '', value: '' }] }),
        onRemove: (i) => onChange({ ...value, env: value.env.filter((_, j) => j !== i) }),
      })),

    h('div', null,
      h('label', { className: labelClass }, 'Placement'),
      h('select', { className: inputClass, value: value.placement.type, onChange: setPlacementField('type') },
        h('option', { value: '' }, '— None —'),
        h('option', { value: 'REGION' }, 'Region'),
        h('option', { value: 'IP_ADDRESS' }, 'IP Address'),
        h('option', { value: 'LAT_LON' }, 'Lat / Lon')),
      value.placement.type === 'REGION' &&
        h('input', { className: `${inputClass} mt-1`, value: value.placement.region, onChange: setPlacementField('region') }),
      value.placement.type === 'IP_ADDRESS' &&
        h('input', { className: `${inputClass} mt-1`, value: value.placement.ip, onChange: setPlacementField('ip') }),
      value.placement.type === 'LAT_LON' &&
        h('div', { className: 'flex gap-2 mt-1' },
          h('input', { className: inputClass, value: value.placement.lat, onChange: setPlacementField('lat') }),
          h('input', { className: inputClass, value: value.placement.lon, onChange: setPlacementField('lon') }))),

    h('label', { className: 'flex items-center gap-2 text-sm cursor-pointer' },
      h('input', {
        type: 'checkbox',
        checked: value.tty,
        onChange: handleTtyToggle,
      }),
      h('span', null, 'Run as terminal job'),
      h('span', { className: 'text-xs text-muted-foreground' },
        '(allocates a pty; attach a terminal from Running Jobs once it starts — Kubernetes jobs only)')),

    sessionSecretEnvVar && h('label', { className: 'flex items-center gap-2 text-sm cursor-pointer' },
      h('input', {
        type: 'checkbox',
        checked: value.injectSessionSecret,
        onChange: (e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...value, injectSessionSecret: e.target.checked }),
      }),
      h('span', null, 'Inject my session secret'),
      h('span', { className: 'text-xs text-muted-foreground' },
        `(sets $${sessionSecretEnvVar} to your current session secret — visible to anything running in the container)`)))
}
