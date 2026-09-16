import { AvailableJobsPage } from './AvailableJobsPage'
import { RunningJobsPage } from './RunningJobsPage'
import { ROUTES } from './nav'
import React from 'react'

declare global {
  interface Window {
    __elementsPlugins?: { register: (route: string, component: React.ComponentType) => void }
  }
}

window.__elementsPlugins?.register(ROUTES.available, AvailableJobsPage)
window.__elementsPlugins?.register(ROUTES.running, RunningJobsPage)
