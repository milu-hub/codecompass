import { getJson, postJson, putJson, delJson } from './http'
import { llmRequestHeaders } from '../utils/llmConfig'

/**
 * T7 REST API 的 TS 类型，手写镜像后端契约（不引 openapi 生成 —— T0 定下的原则）。
 */

export interface UnitView {
  id: string
  filePath: string
  packageName: string
  name: string
  kind: string
  /** 来自 T5 分类器；无角色为空串 */
  role: string
  annotations: string[]
  startLine: number
  endLine: number
  /** T14：选中标识符提问的原料 —— 点源码行时吸附到所在方法/字段 */
  methods: MethodView[]
  fields: FieldView[]
}

export interface MethodView {
  name: string
  signature: string
  startLine: number
  endLine: number
}

export interface FieldView {
  name: string
  type: string
}

export interface DependencyEdge {
  id: string
  fromCodeUnitId: string
  toCodeUnitId: string
  kind: string
  language: string
}

export interface GraphResponse {
  taskId: string
  repositoryUrl: string
  status: string
  language: string
  framework: string
  errorMessage: string | null
  /** 无论全图还是邻域请求，codeUnits 都是全量类列表 */
  codeUnits: UnitView[]
  dependencies: DependencyEdge[]
  failedFiles: { filePath: string; reason: string }[]
  isolatedCodeUnitIds: string[]
  mermaid: string
}

export interface AnalysisTaskView {
  taskId: string
  url: string
  status: 'pending' | 'running' | 'done' | 'failed'
  language: string | null
  progress: number
  message: string
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export function submitRepository(url: string): Promise<AnalysisTaskView> {
  return postJson<AnalysisTaskView>('/api/repos', { url })
}

export function fetchStatus(taskId: string): Promise<AnalysisTaskView> {
  return getJson<AnalysisTaskView>(`/api/repos/${taskId}/status`)
}

/** unitId 为空时返回全图；否则返回该单元的一跳邻域（后端 T6 的 neighborhoodOf）。 */
export function fetchGraph(taskId: string, unitId?: string): Promise<GraphResponse> {
  const query = unitId ? `?unit=${encodeURIComponent(unitId)}&depth=1` : ''
  return getJson<GraphResponse>(`/api/repos/${taskId}/graph${query}`)
}

// ---------- T14 源码与行锚点 ----------

export interface SourceView {
  file: string
  language: string
  startLine: number
  endLine: number
  lines: string[]
}

export function fetchSource(taskId: string, unitId: string): Promise<SourceView> {
  return getJson<SourceView>(`/api/repos/${taskId}/source?unit=${encodeURIComponent(unitId)}`)
}

// ---------- T10 问答 ----------

export interface AskReference {
  file: string
  language: string
  startLine: number
  endLine: number
}

export interface AskResponse {
  answer: string
  references: AskReference[]
  model: string
}

/**
 * unitId 是 §S7「点击某个类提问」的锚点；null 时后端按关键词检索。
 * anchorStartLine/anchorEndLine 是 T14 选中标识符的行锚点（可空）。
 *
 * <p>用户自填的 LLM 配置按请求用**请求头**带上（`X-LLM-Api-Key` 等）：未配置时
 * {@link llmRequestHeaders} 返回空对象，一个头都不发，后端回落服务端默认额度。
 * 走请求头而不是 URL 参数，避免 key 进代理访问日志。
 */
export function askQuestion(
  taskId: string,
  question: string,
  unitId: string | null,
  anchorStartLine?: number | null,
  anchorEndLine?: number | null,
): Promise<AskResponse> {
  return postJson<AskResponse>(
    `/api/repos/${taskId}/ask`,
    {
      question,
      unitId,
      anchorStartLine: anchorStartLine ?? null,
      anchorEndLine: anchorEndLine ?? null,
    },
    llmRequestHeaders(),
  )
}

// ---------- F2 学习路线（T14/T15） ----------

export interface LearningPathStep {
  order: number
  codeUnitId: string
  reason: string
  estimatedMinutes: number
}

export interface LearningPath {
  repoUrl: string
  commitSha: string
  steps: LearningPathStep[]
}

/** POST 生成（后端幂等：已生成直接返回持久化结果）。 */
export function generateLearningPath(taskId: string): Promise<LearningPath> {
  return postJson<LearningPath>(`/api/repos/${taskId}/learning-path`, {})
}

/** GET 获取；未生成时后端返回 404。 */
export function fetchLearningPath(taskId: string): Promise<LearningPath> {
  return getJson<LearningPath>(`/api/repos/${taskId}/learning-path`)
}

// ---------- F4 自动测验（T16/T17） ----------

export interface QuizReference {
  file: string
  language: string
  startLine: number
  endLine: number
}

export interface QuizQuestion {
  id: string
  type: 'single_choice' | 'true_false'
  question: string
  options: string[]
  /** 正确选项的 0-based 下标 */
  answer: number
  explanation: string
  reference: QuizReference
}

export interface Quiz {
  id: string
  repoUrl: string
  commitSha: string
  questions: QuizQuestion[]
}

export interface QuizGrade {
  correct: number
  total: number
  accuracy: number
}

export function generateQuiz(taskId: string, codeUnitIds: string[]): Promise<Quiz> {
  return postJson<Quiz>(`/api/repos/${taskId}/quiz`, { codeUnitIds })
}

export function submitQuiz(
  quizId: string,
  answers: { questionId: string; answerIndex: number }[],
): Promise<QuizGrade> {
  return postJson<QuizGrade>(`/api/quizzes/${quizId}/submit`, { answers })
}

// ---------- F5 进度 / 笔记 / 成就（T19/T20） ----------

export interface ProgressView {
  clientId: string
  repoUrl: string
  codeUnitId: string
  status: string
  updatedAt: string
}

export function fetchProgress(repoUrl: string): Promise<ProgressView[]> {
  return getJson<ProgressView[]>(`/api/progress?repoUrl=${encodeURIComponent(repoUrl)}`)
}

export function updateProgress(repoUrl: string, codeUnitId: string, status: string): Promise<ProgressView> {
  return putJson<ProgressView>('/api/progress', { repoUrl, codeUnitId, status })
}

export interface NoteView {
  id: number
  clientId: string
  repoUrl: string
  codeUnitId: string
  content: string
  createdAt: string
  updatedAt: string
}

export function fetchNotes(repoUrl: string): Promise<NoteView[]> {
  return getJson<NoteView[]>(`/api/notes?repoUrl=${encodeURIComponent(repoUrl)}`)
}

export function saveNote(repoUrl: string, codeUnitId: string, content: string): Promise<NoteView> {
  return postJson<NoteView>('/api/notes', { repoUrl, codeUnitId, content })
}

export function updateNote(noteId: number, content: string): Promise<NoteView> {
  return putJson<NoteView>(`/api/notes/${noteId}`, { content })
}

export function deleteNote(noteId: number): Promise<void> {
  return delJson(`/api/notes/${noteId}`)
}

export interface AchievementView {
  code: string
  name: string
  description: string
  unlockedAt: string | null
}

export function fetchAchievements(): Promise<AchievementView[]> {
  return getJson<AchievementView[]>('/api/achievements')
}

// ---------- F6 分享（T21/T22） ----------

export interface ShareLink {
  shareId: string
  /** 相对短链，如 /share/abc123def456 */
  url: string
}

/** 生成分享快照，返回短链。 */
export function createShareLink(taskId: string): Promise<ShareLink> {
  return postJson<ShareLink>(`/api/repos/${taskId}/share`, {})
}
