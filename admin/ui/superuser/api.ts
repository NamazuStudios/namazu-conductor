declare global {
  interface Window {
    __elementsApiClient?: { getSessionToken?: () => string | undefined }
  }
}

// Must match ConductorAdminApplication.RS_ROOT — kept as a distinct path segment from the WebSocket
// root (see terminal.ts) since both loaders sharing one context path is suspected to break WebSocket
// endpoint discovery (https://github.com/NamazuStudios/elements/issues/95).
const REST_ROOT = '/conductor/admin-console/rest'

export function authHeaders(): Record<string, string> {
  const token = window.__elementsApiClient?.getSessionToken?.()
  return token ? { 'Elements-SessionSecret': token } : {}
}

async function requestJson<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    ...init,
    headers: { ...authHeaders(), ...(init?.headers ?? {}) },
  })
  const body = await res.json().catch(() => null)
  if (!res.ok) {
    const message = (body && typeof body === 'object' && 'error' in body && (body as { error?: string }).error)
      || `${res.status} ${res.statusText}`
    throw new Error(message)
  }
  return body as T
}

function postJson<T>(path: string, payload: unknown): Promise<T> {
  return requestJson<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
}

export function fetchProfiles() {
  return requestJson<{ status: string; providers: import('./types').ProviderProfilesResult[] }>(`${REST_ROOT}/profiles`)
}

export function fetchJobs() {
  return requestJson<{ status: string; providers: import('./types').ProviderExecutionsResult[] }>(`${REST_ROOT}/jobs`)
}

export interface ExecuteJobRequestBody {
  element: string
  profileId: string
  args?: string[]
  command?: string[]
  environment?: Record<string, string>
  placement?: unknown[]
  tty?: boolean
}

export function executeJob(body: ExecuteJobRequestBody) {
  return postJson<import('./types').JobExecution>(`${REST_ROOT}/jobs`, body)
}

export function stopJob(element: string, id: string) {
  return postJson<void>(`${REST_ROOT}/jobs/stop`, { element, id })
}

export function mintTerminalTicket(jobId: string, containerId: string | null, command?: string[]) {
  return postJson<{ ticket: string }>(`${REST_ROOT}/jobs/terminal-ticket`, { jobId, containerId, command })
}
