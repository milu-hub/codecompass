import { defineStore } from 'pinia'
import { fetchHealth } from '../api/health'

interface HealthState {
  status: string
  service: string
  version: string
  time: string
  loading: boolean
  error: string | null
}

export const useHealthStore = defineStore('health', {
  state: (): HealthState => ({
    status: 'UNKNOWN',
    service: '',
    version: '',
    time: '',
    loading: false,
    error: null,
  }),
  actions: {
    async load() {
      this.loading = true
      this.error = null
      try {
        const health = await fetchHealth()
        this.status = health.status
        this.service = health.service
        this.version = health.version
        this.time = health.time
      } catch (e) {
        this.status = 'DOWN'
        this.error = e instanceof Error ? e.message : String(e)
      } finally {
        this.loading = false
      }
    },
  },
})
