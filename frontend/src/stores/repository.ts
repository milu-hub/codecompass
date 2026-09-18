import { defineStore } from 'pinia'
import { fetchGraph, fetchStatus, submitRepository, fetchLearningPath, generateLearningPath } from '../api/repos'
import type { AnalysisTaskView, GraphResponse, LearningPath } from '../api/repos'

export type TaskPhase = 'idle' | 'submitting' | 'pending' | 'running' | 'done' | 'failed'

/** T14：源码里选中的标识符范围（提问的行锚点）。 */
export interface SourceSelection {
  startLine: number
  endLine: number
  /** 给人看的描述，如「方法 processFindForm（94-122 行）」 */
  label: string
}

/**
 * T8 的状态机中心。
 *
 * 轮询用递归 setTimeout 而不是 setInterval，且全 store **只有一个 timer 句柄**：
 * stopPolling 是唯一清理入口，submit / done / failed 三处都先停后走 ——
 * 否则切换任务后旧轮询会把新任务的状态搅乱（这类泄漏只在长时间使用时暴露）。
 */
export const useRepositoryStore = defineStore('repository', {
  state: () => ({
    url: '',
    taskId: null as string | null,
    phase: 'idle' as TaskPhase,
    progress: 0,
    message: '',
    errorMessage: null as string | null,
    language: null as string | null,
    framework: '',
    graph: null as GraphResponse | null,
    neighborhood: null as GraphResponse | null,
    selectedUnitId: null as string | null,
    filterText: '',
    selection: null as SourceSelection | null,
    learningPath: null as LearningPath | null,
    pollingHandle: null as number | null,
    // 点击类的请求竞态令牌：晚到的旧响应必须丢弃
    unitRequestToken: 0,
  }),

  actions: {
    stopPolling() {
      if (this.pollingHandle !== null) {
        clearTimeout(this.pollingHandle)
        this.pollingHandle = null
      }
    },

    async submit() {
      this.stopPolling()
      this.phase = 'submitting'
      this.errorMessage = null
      this.graph = null
      this.neighborhood = null
      this.selectedUnitId = null
      this.learningPath = null
      this.progress = 0
      this.message = ''
      try {
        const task = await submitRepository(this.url.trim())
        this.taskId = task.taskId
        this.phase = task.status === 'failed' ? 'failed' : 'running'
        if (this.phase === 'running') {
          this.schedulePoll()
        }
      } catch (error) {
        this.phase = 'failed'
        this.errorMessage = error instanceof Error ? error.message : String(error)
      }
    },

    schedulePoll() {
      if (this.pollingHandle !== null) {
        return
      }
      this.pollingHandle = window.setTimeout(() => {
        this.pollingHandle = null
        void this.pollOnce()
      }, 1500)
    },

    async pollOnce() {
      if (!this.taskId) {
        return
      }
      try {
        const status = await fetchStatus(this.taskId)
        this.progress = status.progress
        this.message = status.message
        this.language = status.language
        if (status.status === 'done') {
          this.phase = 'done'
          await this.loadGraph()
        } else if (status.status === 'failed') {
          this.phase = 'failed'
          this.errorMessage = status.errorMessage ?? '分析失败'
        } else {
          this.phase = status.status
          this.schedulePoll()
        }
      } catch (error) {
        this.phase = 'failed'
        this.errorMessage = error instanceof Error ? error.message : String(error)
      }
    },

    async loadGraph() {
      if (!this.taskId) {
        return
      }
      this.graph = await fetchGraph(this.taskId)
      this.framework = this.graph.framework
      const first = this.graph.codeUnits[0]
      if (first && !this.selectedUnitId) {
        await this.selectUnit(first.id)
      }
      void this.loadLearningPath()
    },

    async selectUnit(unitId: string) {
      if (!this.taskId) {
        return
      }
      this.selectedUnitId = unitId
      this.selection = null   // 换类后旧选中范围不再有意义
      const token = ++this.unitRequestToken
      const response = await fetchGraph(this.taskId, unitId)
      if (token === this.unitRequestToken) {
        this.neighborhood = response
      }
    },

    setSelection(selection: SourceSelection) {
      this.selection = selection
    },

    clearSelection() {
      this.selection = null
    },

    async loadLearningPath() {
      if (!this.taskId) {
        return
      }
      try {
        this.learningPath = await fetchLearningPath(this.taskId)
      } catch {
        this.learningPath = null   // 尚未生成
      }
    },

    async generateLearningPath() {
      if (!this.taskId) {
        return
      }
      this.learningPath = await generateLearningPath(this.taskId)
    },
  },
})
