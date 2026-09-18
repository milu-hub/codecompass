import { getJson, postJson } from './http'

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
 */
export function askQuestion(
  taskId: string,
  question: string,
  unitId: string | null,
  anchorStartLine?: number | null,
  anchorEndLine?: number | null,
): Promise<AskResponse> {
  return postJson<AskResponse>(`/api/repos/${taskId}/ask`, {
    question,
    unitId,
    anchorStartLine: anchorStartLine ?? null,
    anchorEndLine: anchorEndLine ?? null,
  })
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
