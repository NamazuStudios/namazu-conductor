declare global {
  interface Window {
    __elementsApiClient?: { getSessionToken?: () => string | undefined }
  }
}

const ELEMENT_NAME = 'dev.getelements.conductor.admin'
const RS_ROOT_ATTRIBUTE = 'dev.getelements.elements.element.rs.root'
const WS_ROOT_ATTRIBUTE = 'dev.getelements.elements.element.ws.root'

// Used only if attribute discovery (below) fails or returns something unusable — must match
// ConductorAdminApplication's compiled-in @ElementDefaultAttribute values.
const FALLBACK_REST_ROOT = '/conductor/admin-console/rest'
const FALLBACK_WS_ROOT = '/conductor/admin-console/ws'

export function authHeaders(): Record<string, string> {
  const token = window.__elementsApiClient?.getSessionToken?.()
  return token ? { 'Elements-SessionSecret': token } : {}
}

let ownAttributesPromise: Promise<Record<string, unknown>> | null = null

/**
 * Discovers this element's own resolved attributes — in particular RS_ROOT/WS_ROOT — via the
 * platform's `GET /api/rest/elements/system` endpoint, which reports each deployed element's
 * live `Attributes` map (post any deploy-time override, not just compiled-in defaults). Each
 * Conductor deployment is expected to run independently and may configure its own namespace, so
 * this dashboard can't assume a fixed path; it asks the platform instead of hardcoding one.
 * Falls back to the compiled-in default on any failure (older platform version without this
 * endpoint, network error, unexpected response shape) rather than breaking the dashboard.
 */
function resolveOwnAttributes(): Promise<Record<string, unknown>> {
  if (!ownAttributesPromise) {
    ownAttributesPromise = fetch('/api/rest/elements/system', { credentials: 'include', headers: authHeaders() })
      .then(res => res.ok ? res.json() : Promise.reject(new Error(`HTTP ${res.status}`)))
      .then((elements: Array<{ definition?: { name?: string }; attributes?: Record<string, unknown> }>) =>
        elements.find(e => e.definition?.name === ELEMENT_NAME)?.attributes ?? {})
      .catch(() => ({}))
  }
  return ownAttributesPromise
}

function stringAttribute(attrs: Record<string, unknown>, key: string, fallback: string): string {
  const value = attrs[key]
  return typeof value === 'string' && value.length > 0 ? value : fallback
}

export async function resolveRestRoot(): Promise<string> {
  return stringAttribute(await resolveOwnAttributes(), RS_ROOT_ATTRIBUTE, FALLBACK_REST_ROOT)
}

export async function resolveWsRoot(): Promise<string> {
  return stringAttribute(await resolveOwnAttributes(), WS_ROOT_ATTRIBUTE, FALLBACK_WS_ROOT)
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

export async function fetchProfiles() {
  const root = await resolveRestRoot()
  return requestJson<{ status: string; providers: import('./types').ProviderProfilesResult[] }>(`${root}/profiles`)
}

export async function fetchJobs() {
  const root = await resolveRestRoot()
  return requestJson<{ status: string; providers: import('./types').ProviderExecutionsResult[] }>(`${root}/jobs`)
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

export async function executeJob(body: ExecuteJobRequestBody) {
  const root = await resolveRestRoot()
  return postJson<import('./types').JobExecution>(`${root}/jobs`, body)
}

export async function stopJob(element: string, id: string) {
  const root = await resolveRestRoot()
  return postJson<void>(`${root}/jobs/stop`, { element, id })
}
