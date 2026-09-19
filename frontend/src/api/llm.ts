/**
 * LLM 配置相关接口。
 *
 * 「测试连接」用的是**表单里当前填的值**（还没保存也能测），走请求体而不是 URL 参数，
 * 也不经过任何持久化 —— 服务端只拿它发一次最小请求就丢弃。
 */
import { postJson } from './http'

export interface LlmTestRequest {
  baseUrl: string
  apiKey: string
  model: string
}

export interface LlmTestResult {
  ok: boolean
  message: string
}

export function testLlmConnection(request: LlmTestRequest): Promise<LlmTestResult> {
  return postJson<LlmTestResult>('/api/llm/test', request)
}
