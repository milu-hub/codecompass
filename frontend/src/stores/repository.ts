import { defineStore } from 'pinia'
import { fetchGraph, fetchStatus, submitRepository, fetchLearningPath, generateLearningPath, generateQuiz, submitQuiz, fetchProgress, updateProgress, fetchNotes, saveNote, updateNote, deleteNote, fetchAchievements, createShareLink } from '../api/repos'
import type { AnalysisTaskView, GraphResponse, LearningPath, Quiz, QuizGrade, NoteView, AchievementView, ShareLink } from '../api/repos'

export type TaskPhase = 'idle' | 'submitting' | 'pending' | 'running' | 'done' | 'failed'

/**
 * 成就「已读」标记的存储键。
 *
 * 身份本来就是浏览器级的匿名 Cookie（cc_client_id），所以「看过没看过」也放在
 * 浏览器本地即可，不需要为此改后端。
 */
const SEEN_ACHIEVEMENTS_KEY = 'cc_seen_achievements'

function loadSeenAchievements(): string[] {
  try {
    const raw = localStorage.getItem(SEEN_ACHIEVEMENTS_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return Array.isArray(parsed)
      ? parsed.filter((code): code is string => typeof code === 'string')
      : []
  } catch {
    // 隐私模式 / 脏数据：当作没看过，不影响主流程
    return []
  }
}

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
    quiz: null as Quiz | null,
    quizGrade: null as QuizGrade | null,
    unitProgress: {} as Record<string, string>,
    notes: [] as NoteView[],
    achievements: [] as AchievementView[],
    /** 已经「看过」的成就 code（点开成就入口即视为看过） */
    seenAchievementCodes: loadSeenAchievements(),
    shareLink: null as ShareLink | null,
    pollingHandle: null as number | null,
    // 点击类的请求竞态令牌：晚到的旧响应必须丢弃
    unitRequestToken: 0,
  }),

  getters: {
    /** 顶部红点 = 已解锁但还没看过的成就数（看过就归零，红点随之消失）。 */
    unseenAchievementCount(state): number {
      return state.achievements.filter(
        (achievement) =>
          achievement.unlockedAt && !state.seenAchievementCodes.includes(achievement.code),
      ).length
    },
  },

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
      this.quiz = null
      this.quizGrade = null
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
      // T24：默认不铺全图、也不预选第一个类 —— 点击类才显示它的邻域与源码。
      this.selectedUnitId = null
      this.neighborhood = null
      void this.loadLearningPath()
      void this.loadProgress(this.graph.repositoryUrl)
      void this.loadNotes(this.graph.repositoryUrl)
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

    async generateQuiz(codeUnitIds: string[]) {
      if (!this.taskId) {
        return
      }
      this.quiz = await generateQuiz(this.taskId, codeUnitIds)
      this.quizGrade = null
    },

    async submitQuiz(answers: { questionId: string; answerIndex: number }[]) {
      if (!this.quiz) {
        return
      }
      this.quizGrade = await submitQuiz(this.quiz.id, answers)
    },

    async loadProgress(repoUrl: string) {
      this.unitProgress = {}
      for (const item of await fetchProgress(repoUrl)) {
        this.unitProgress[item.codeUnitId] = item.status
      }
    },

    async setProgress(repoUrl: string, codeUnitId: string, status: string) {
      await updateProgress(repoUrl, codeUnitId, status)
      this.unitProgress[codeUnitId] = status
    },

    async loadNotes(repoUrl: string) {
      this.notes = await fetchNotes(repoUrl)
    },

    async saveNote(repoUrl: string, codeUnitId: string, content: string) {
      await saveNote(repoUrl, codeUnitId, content)
      await this.loadNotes(repoUrl)
    },

    async removeNote(noteId: number, repoUrl: string) {
      await deleteNote(noteId)
      await this.loadNotes(repoUrl)
    },

    /** 刷新成就，返回本次新解锁的成就（供组件弹提示）。 */
    async refreshAchievements(): Promise<AchievementView[]> {
      const next = await fetchAchievements()
      const previouslyUnlocked = new Set(
        this.achievements.filter((a) => a.unlockedAt).map((a) => a.code),
      )
      this.achievements = next
      return next.filter((a) => a.unlockedAt && !previouslyUnlocked.has(a.code))
    },

    /**
     * 点开成就入口 = 已读：把当前已解锁的成就全部记为看过，顶部红点随之消失。
     * 只增不减，并持久化到本地，刷新后不会把老成就又当成新解锁。
     */
    markAchievementsSeen() {
      const unlocked = this.achievements
        .filter((achievement) => achievement.unlockedAt)
        .map((achievement) => achievement.code)
      const merged = [...new Set([...this.seenAchievementCodes, ...unlocked])]
      if (merged.length === this.seenAchievementCodes.length) {
        return
      }
      this.seenAchievementCodes = merged
      try {
        localStorage.setItem(SEEN_ACHIEVEMENTS_KEY, JSON.stringify(merged))
      } catch {
        // 写不进去也不影响本次会话内红点消失
      }
    },

    /** F6：生成分享短链。 */
    async generateShareLink() {
      if (!this.taskId) {
        return
      }
      this.shareLink = await createShareLink(this.taskId)
    },
  },
})
