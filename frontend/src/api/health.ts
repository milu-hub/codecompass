import { getJson } from './http'
import type { HealthResponse } from '../types/health'

export function fetchHealth(): Promise<HealthResponse> {
  return getJson<HealthResponse>('/health')
}
