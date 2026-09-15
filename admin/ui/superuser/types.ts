export interface ContainerRef {
  id: string
  name: string
  primary: boolean
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
}

export interface JobProfile {
  id: string
  containers?: ContainerRef[]
  [key: string]: unknown
}

export interface ProviderProfilesResult {
  element: string
  providerType?: string
  profiles?: JobProfile[]
  error?: string | null
}

export interface ProviderExecutionsResult {
  element: string
  executions?: JobExecution[]
  error?: string | null
}

export interface PlacementInput {
  type: '' | 'REGION' | 'IP_ADDRESS' | 'LAT_LON'
  region: string
  ip: string
  lat: string
  lon: string
}
