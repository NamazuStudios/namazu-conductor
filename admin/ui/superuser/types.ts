export interface ContainerRef {
  id: string
  name: string
  primary: boolean
  defaultCommand?: string[]
}

export interface JobEndpoint {
  host: string
  port: number
  protocol: string
}

export interface JobExecution {
  id: string
  status: string
  endpoints?: JobEndpoint[]
  details?: unknown
  containers?: ContainerRef[]
  /** Tags/annotations actually present on the workload. Read back from the provider, so it can be a
   * superset of what was declared. Absent for providers with no metadata channel. */
  metadata?: Record<string, string>
}

export interface JobProfile {
  id: string
  containers?: ContainerRef[]
  terminalJob?: boolean
  description?: string
  /** Free-form presentation metadata declared by the infrastructure — the whole Kubernetes
   * annotation map or ECS tag map, verbatim, including `namazu.conductor` keys. Conductor assigns
   * no meaning to any of it. */
  metadata?: Record<string, string>
  [key: string]: unknown
}

export interface ProviderProfilesResult {
  element: string
  providerType?: string
  profiles?: JobProfile[]
  error?: string | null
  jobSetName?: string | null
  jobSetDescription?: string | null
}

export interface ProviderExecutionsResult {
  element: string
  executions?: JobExecution[]
  error?: string | null
  jobSetName?: string | null
  jobSetDescription?: string | null
}

export interface PlacementInput {
  type: '' | 'REGION' | 'IP_ADDRESS' | 'LAT_LON'
  region: string
  ip: string
  lat: string
  lon: string
}
