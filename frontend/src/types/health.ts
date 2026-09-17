/**
 * 后端 `GET /health` 的响应契约，镜像 backend 的 HealthResponse record。
 * 注意：time 是 ISO-8601 UTC 时间字符串，不是时间戳数字。
 */
export interface HealthResponse {
  status: string
  service: string
  version: string
  time: string
}
