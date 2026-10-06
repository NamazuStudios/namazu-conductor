import React from 'react'
import { KVEditor, ListEditor, isBehavioralMetadataKey, RESERVED_METADATA_PREFIX } from './ui'
import type { KVPair } from './ui'
import type { ContainerRef, PlacementInput } from './types'
import { TERMINAL_COMMAND_SUGGESTION, defaultAdvancedOptions } from './runOptions'
import type { AdvancedOptions, EnvPair } from './runOptions'

// Re-exported so existing importers of RunForm keep working; the implementations (and the tests)
// live in the React-free runOptions.ts.
export { TERMINAL_COMMAND_SUGGESTION, defaultAdvancedOptions }
export type { AdvancedOptions, EnvPair }

const h = React.createElement
const inputClass = 'w-full rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary'
const labelClass = 'block text-xs font-medium text-muted-foreground mb-1'

/**
 * The metadata overrides to actually send, or `undefined` when the operator added none.
 *
 * `undefined` (omitted) rather than `{}` matters: an empty object is a no-op merge, but omitting the
 * field keeps the request body honest that nothing was overridden, matching how `command` and
 * `placement` already behave.
 *
 * A behavioural `namazu.conductor` key (workload-kind, jobSet, replicas, …) is dropped here rather
 * than sent and rejected: the server would answer 400 for it, and letting an operator compose a
 * key that is guaranteed to be refused is a worse experience than not sending it. Cosmetic
 * reserved keys (`hidden`, `agent`, `link.*`, …) are *not* dropped — per-run overrides of those
 * are exactly the point of the vocabulary.
 */
export function deriveMetadataOverrides(metadata: KVPair[]): Record<string, string> | undefined {
  const overrides: Record<string, string> = {}
  metadata.forEach((pair) => {
    const key = pair.key.trim()
    if (!key || isBehavioralMetadataKey(key)) return
    overrides[key] = pair.value
  })
  return Object.keys(overrides).length > 0 ? overrides : undefined
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
  /** The profile's containers (issue #73); when it declares more than one, env rows get a
   * container selector and the session-secret injection a per-container checkbox set. */
  containers?: ContainerRef[]
}) {
  const { value, onChange, sessionSecretEnvVar, containers } = props
  const multiContainer = (containers?.length ?? 0) > 1

  const updateListItem = (key: 'args' | 'command', i: number, val: string) =>
    onChange({ ...value, [key]: value[key].map((v, j) => (j === i ? val : v)) })
  const addListItem = (key: 'args' | 'command') =>
    onChange({ ...value, [key]: [...value[key], ''] })
  const removeListItem = (key: 'args' | 'command', i: number) =>
    onChange({ ...value, [key]: value[key].filter((_, j) => j !== i) })

  const setPlacementField = (key: keyof PlacementInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    onChange({ ...value, placement: { ...value.placement, [key]: e.target.value } })

  const setEnvRow = (i: number, patch: Partial<EnvPair>) =>
    onChange({ ...value, env: value.env.map((p, j) => (j === i ? { ...p, ...patch } : p)) })

  const toggleSecretContainer = (container: string, checked: boolean) => {
    const set = new Set(value.sessionSecretContainers)
    if (checked) set.add(container); else set.delete(container)
    onChange({ ...value, sessionSecretContainers: [...set] })
  }

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
      h('div', { className: 'space-y-1' },
        value.env.map((pair, i) =>
          h('div', { key: i, className: 'flex gap-1 items-center' },
            multiContainer && h('select', {
              className: `${inputClass} w-36 shrink-0`,
              value: pair.container,
              title: 'Container this variable is injected into',
              onChange: (e: React.ChangeEvent<HTMLSelectElement>) => setEnvRow(i, { container: e.target.value }),
            },
              h('option', { value: '' }, 'primary (default)'),
              containers!.map((c) => h('option', { key: c.id, value: c.id }, c.name))),
            h('input', {
              className: inputClass, placeholder: 'NAME', value: pair.key,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setEnvRow(i, { key: e.target.value }),
            }),
            h('input', {
              className: inputClass, placeholder: 'value', value: pair.value,
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => setEnvRow(i, { value: e.target.value }),
            }),
            h('button', {
              type: 'button', className: 'px-2 text-muted-foreground hover:text-destructive',
              title: 'Remove',
              onClick: () => onChange({ ...value, env: value.env.filter((_, j) => j !== i) }),
            }, '✕'))),
        h('button', {
          type: 'button',
          className: 'mt-1 px-2 py-0.5 text-xs rounded border border-border text-muted-foreground hover:bg-muted',
          onClick: () => onChange({ ...value, env: [...value.env, { key: '', value: '', container: '' }] }),
        }, '+ Add variable'),
      ),
      multiContainer && h('p', { className: 'text-xs text-muted-foreground mt-1' },
        `'primary (default)' injects into the pod's first container; other values inject into the
         named sidecar and are rejected with a 400 if the name doesn't exist`)),

    h('div', null,
      h('label', { className: labelClass }, 'Metadata overrides'),
      h(KVEditor, {
        items: value.metadata,
        onChangeKey: (i, v) => onChange({ ...value, metadata: value.metadata.map((p, j) => (j === i ? { ...p, key: v } : p)) }),
        onChangeValue: (i, v) => onChange({ ...value, metadata: value.metadata.map((p, j) => (j === i ? { ...p, value: v } : p)) }),
        onAdd: () => onChange({ ...value, metadata: [...value.metadata, { key: '', value: '' }] }),
        onRemove: (i) => onChange({ ...value, metadata: value.metadata.filter((_, j) => j !== i) }),
      }),
      h('p', { className: 'text-xs text-muted-foreground mt-1' },
        `merged over the profile's declared metadata for this launch. Cosmetic ` +
        `"${RESERVED_METADATA_PREFIX}" keys (hidden, agent, link.*, …) may be overridden; ` +
        `keys that drive Conductor's behaviour (workload-kind, jobSet, replicas, …) are ` +
        `rejected with a 400`)),

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

    sessionSecretEnvVar && h('div', { className: 'text-sm' },
      h('label', { className: 'flex items-center gap-2 cursor-pointer' },
        h('input', {
          type: 'checkbox',
          checked: value.injectSessionSecret,
          onChange: (e: React.ChangeEvent<HTMLInputElement>) => onChange({ ...value, injectSessionSecret: e.target.checked }),
        }),
        h('span', null, 'Inject my session secret'),
        h('span', { className: 'text-xs text-muted-foreground' },
          `(sets $${sessionSecretEnvVar} to your current session secret — visible to anything running in the targeted container(s))`)),
      value.injectSessionSecret && multiContainer && h('div', { className: 'ml-6 mt-1 flex flex-wrap gap-3' },
        containers!.map((c) =>
          h('label', { key: c.id, className: 'flex items-center gap-1 text-xs cursor-pointer' },
            h('input', {
              type: 'checkbox',
              checked: value.sessionSecretContainers.includes(c.primary ? '' : c.id),
              onChange: (e: React.ChangeEvent<HTMLInputElement>) => toggleSecretContainer(c.primary ? '' : c.id, e.target.checked),
            }),
            c.primary ? 'primary (default)' : c.name)))))
}
