import React from 'react'
import { executeJob } from './api'
import { ExecutionResult, KVEditor, ListEditor } from './ui'
import type { KVPair } from './ui'
import type { JobExecution, PlacementInput } from './types'

const h = React.createElement
const inputClass = 'w-full rounded border bg-background px-2 py-1 text-sm font-mono focus:outline-none focus:ring-1 focus:ring-primary'
const labelClass = 'block text-xs font-medium text-muted-foreground mb-1'

const TERMINAL_COMMAND_SUGGESTION = ['/bin/sh']

export function RunForm(props: { element: string; profileId: string; onClose: () => void }) {
  const [args, setArgs] = React.useState<string[]>([])
  const [command, setCommand] = React.useState<string[]>([])
  const [env, setEnv] = React.useState<KVPair[]>([])
  const [placement, setPlacement] = React.useState<PlacementInput>({ type: '', region: '', ip: '', lat: '', lon: '' })
  const [tty, setTty] = React.useState(false)
  const [result, setResult] = React.useState<JobExecution | null>(null)
  const [submitting, setSubmitting] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)

  const updateList = (setter: React.Dispatch<React.SetStateAction<string[]>>, i: number, val: string) =>
    setter((list) => { const n = list.slice(); n[i] = val; return n })
  const addToList = (setter: React.Dispatch<React.SetStateAction<string[]>>, empty: string) =>
    setter((list) => [...list, empty])
  const removeFromList = (setter: React.Dispatch<React.SetStateAction<string[]>>, i: number) =>
    setter((list) => list.filter((_, j) => j !== i))

  const setPlacementField = (key: keyof PlacementInput) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setPlacement((p) => ({ ...p, [key]: e.target.value }))

  function handleTtyToggle(e: React.ChangeEvent<HTMLInputElement>) {
    const checked = e.target.checked
    setTty(checked)
    if (checked && command.length === 0) setCommand(TERMINAL_COMMAND_SUGGESTION)
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true); setError(null); setResult(null)

    const environment: Record<string, string> = {}
    env.forEach((pair) => { if (pair.key.trim()) environment[pair.key.trim()] = pair.value })

    let placementList: unknown[] = []
    if (placement.type === 'REGION' && placement.region.trim()) {
      placementList = [{ type: 'REGION', region: placement.region.trim() }]
    } else if (placement.type === 'IP_ADDRESS' && placement.ip.trim()) {
      placementList = [{ type: 'IP_ADDRESS', ip: placement.ip.trim() }]
    } else if (placement.type === 'LAT_LON' && placement.lat && placement.lon) {
      placementList = [{ type: 'LAT_LON', latitude: parseFloat(placement.lat), longitude: parseFloat(placement.lon) }]
    }

    executeJob({
      element: props.element,
      profileId: props.profileId,
      args: args.map((s) => s.trim()).filter(Boolean),
      command: command.map((s) => s.trim()).filter(Boolean),
      environment,
      placement: placementList,
      tty,
    })
      .then(setResult)
      .catch((e: Error) => setError(e.message || 'Request failed'))
      .finally(() => setSubmitting(false))
  }

  return h('div', { className: 'mt-3 rounded-lg border bg-background p-4 space-y-3' },
    h('form', { onSubmit: handleSubmit, className: 'space-y-3' },

      h('div', { className: 'grid grid-cols-2 gap-3' },
        h('div', null,
          h('label', { className: labelClass }, 'Command override'),
          h(ListEditor, {
            items: command,
            onChange: (i, v) => updateList(setCommand, i, v),
            onAdd: () => addToList(setCommand, ''),
            onRemove: (i) => removeFromList(setCommand, i),
          })),
        h('div', null,
          h('label', { className: labelClass }, 'Args'),
          h(ListEditor, {
            items: args,
            onChange: (i, v) => updateList(setArgs, i, v),
            onAdd: () => addToList(setArgs, ''),
            onRemove: (i) => removeFromList(setArgs, i),
          }))),

      h('div', null,
        h('label', { className: labelClass }, 'Environment variables'),
        h(KVEditor, {
          items: env,
          onChangeKey: (i, v) => setEnv((list) => { const n = list.slice(); n[i] = { ...n[i], key: v }; return n }),
          onChangeValue: (i, v) => setEnv((list) => { const n = list.slice(); n[i] = { ...n[i], value: v }; return n }),
          onAdd: () => setEnv((list) => [...list, { key: '', value: '' }]),
          onRemove: (i) => setEnv((list) => list.filter((_, j) => j !== i)),
        })),

      h('div', null,
        h('label', { className: labelClass }, 'Placement'),
        h('select', { className: inputClass, value: placement.type, onChange: setPlacementField('type') },
          h('option', { value: '' }, '— None —'),
          h('option', { value: 'REGION' }, 'Region'),
          h('option', { value: 'IP_ADDRESS' }, 'IP Address'),
          h('option', { value: 'LAT_LON' }, 'Lat / Lon')),
        placement.type === 'REGION' &&
          h('input', { className: `${inputClass} mt-1`, value: placement.region, onChange: setPlacementField('region') }),
        placement.type === 'IP_ADDRESS' &&
          h('input', { className: `${inputClass} mt-1`, value: placement.ip, onChange: setPlacementField('ip') }),
        placement.type === 'LAT_LON' &&
          h('div', { className: 'flex gap-2 mt-1' },
            h('input', { className: inputClass, value: placement.lat, onChange: setPlacementField('lat') }),
            h('input', { className: inputClass, value: placement.lon, onChange: setPlacementField('lon') }))),

      h('label', { className: 'flex items-center gap-2 text-sm cursor-pointer' },
        h('input', {
          type: 'checkbox',
          checked: tty,
          onChange: handleTtyToggle,
        }),
        h('span', null, 'Run as terminal job'),
        h('span', { className: 'text-xs text-muted-foreground' },
          '(allocates a pty; attach a terminal from Running Jobs once it starts — Kubernetes jobs only)')),

      error && h('p', { className: 'text-xs text-destructive' }, error),
      result && h(ExecutionResult, { execution: result }),

      h('div', { className: 'flex justify-end gap-2' },
        h('button', {
          type: 'button',
          className: 'px-3 py-1.5 rounded border text-sm hover:bg-muted transition-colors',
          onClick: props.onClose,
        }, 'Cancel'),
        h('button', {
          type: 'submit',
          disabled: submitting,
          className: 'px-4 py-1.5 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 disabled:opacity-50',
        }, submitting ? 'Launching…' : '▶ Launch Job'))))
}
