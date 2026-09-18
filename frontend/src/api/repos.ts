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

/** unitId 是 §S7「点击某个类提问」的锚点；null 时后端按关键词检索。 */
export function askQuestion(taskId: string, question: string, unitId: string | null): Promise<AskResponse> {
  return postJson<AskResponse>(`/api/repos/${taskId}/ask`, { question, unitId })
}
