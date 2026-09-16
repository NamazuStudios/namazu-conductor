import React from 'react'
import { RunningJobsSection } from './RunningJobs'
import { readAndClearParams } from './nav'

const h = React.createElement

export function RunningJobsPage() {
  const [highlightId] = React.useState(() => readAndClearParams()?.get('highlight') ?? null)
  return h('div', { className: 'p-6' }, h(RunningJobsSection, { highlightId }))
}
