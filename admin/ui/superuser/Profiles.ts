import React from 'react'
import { RunForm } from './RunForm'
import type { JobProfile, ProviderProfilesResult } from './types'

const h = React.createElement

function ProfileCard(props: { profile: JobProfile; element: string; isRunning: boolean; onRun: (id: string) => void; onRunClose: () => void }) {
  const profile = props.profile
  const [isExpanded, setExpanded] = React.useState(false)
  const keys = Object.keys(profile).filter((k) => k !== 'id')

  return h('div', { className: 'rounded-lg border bg-card' },
    h('div', { className: 'flex items-center gap-3 px-4 py-3' },
      h('button', { className: 'flex-1 flex items-center gap-2 text-left', onClick: () => setExpanded((v) => !v) },
        h('span', { className: 'text-xs opacity-50' }, isExpanded ? '▼' : '▶'),
        h('span', { className: 'font-mono text-sm font-medium' }, profile.id)),
      h('button', {
        className: 'flex items-center gap-1.5 px-3 py-1 rounded bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors',
        onClick: () => (props.isRunning ? props.onRunClose() : props.onRun(profile.id)),
      }, props.isRunning ? '✕ Cancel' : '▶ Run')),
    isExpanded && keys.length > 0 &&
      h('div', { className: 'border-t px-4 py-3 grid grid-cols-[auto_1fr] gap-x-6 gap-y-1.5' },
        keys.flatMap((k) => {
          const val = profile[k]
          const display = val == null ? h('span', { className: 'text-muted-foreground' }, '—')
            : typeof val === 'object' ? JSON.stringify(val) : String(val)
          return [
            h('span', { key: `${k}_k`, className: 'text-xs font-medium text-muted-foreground whitespace-nowrap' }, k),
            h('span', { key: `${k}_v`, className: 'text-xs font-mono break-all' }, display),
          ]
        })),
    props.isRunning &&
      h('div', { className: 'border-t px-4 pb-4' },
        h(RunForm, { element: props.element, profileId: profile.id, onClose: props.onRunClose })))
}

export function ProviderSection(props: { provider: ProviderProfilesResult }) {
  const p = props.provider
  const [isExpanded, setExpanded] = React.useState(false)
  const [activeProfileId, setActiveProfileId] = React.useState<string | null>(null)

  return h('div', { className: 'space-y-2' },
    h('div', { className: 'flex items-center gap-3 flex-wrap' },
      h('span', { className: 'font-mono text-sm font-medium' }, p.element),
      p.providerType && h('span', { className: 'text-xs px-2 py-0.5 rounded-full bg-primary/10 text-primary' }, p.providerType),
      !p.error && h('span', {
        className: `text-xs px-2 py-0.5 rounded-full ${p.profiles && p.profiles.length > 0 ? 'bg-muted text-muted-foreground' : 'bg-yellow-100 text-yellow-700'}`,
      }, p.profiles ? `${p.profiles.length} Job Profile${p.profiles.length === 1 ? '' : 's'}` : '0 Job Profiles'),
      p.error && h('button', {
        className: 'text-xs px-2 py-0.5 rounded-full bg-destructive/10 text-destructive hover:bg-destructive/20 transition-colors',
        onClick: () => setExpanded((v) => !v),
      }, p.error.length > 60 ? `${p.error.slice(0, 60)}…` : p.error,
         h('span', { className: 'ml-1 opacity-60' }, isExpanded ? '▲' : '▼'))),
    isExpanded && p.error && h('pre', {
      className: 'rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive whitespace-pre-wrap break-all',
    }, p.error),
    p.profiles && p.profiles.length > 0 &&
      h('div', { className: 'space-y-2' },
        p.profiles.map((profile) => h(ProfileCard, {
          key: profile.id,
          profile,
          element: p.element,
          isRunning: activeProfileId === profile.id,
          onRun: setActiveProfileId,
          onRunClose: () => setActiveProfileId(null),
        }))))
}
