/**
 * 极薄的 fetch 封装：相对路径 + 非 2xx 抛错 + JSON 解析。
 *
 * 刻意不引 axios（T0 约束：依赖尽量少）。
 * 始终使用相对路径：开发期由 Vite dev server 代理到后端，浏览器视角同源，
 * 因此后端不需要任何 CORS 配置。
 */
export async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(`GET ${path} 失败：HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    // 后端 400/404 等错误响应形如 { error: "..." }，尽量把原文带进异常信息
    let detail = `HTTP ${response.status}`
    try {
      const payload = (await response.json()) as { error?: string }
      if (payload.error) {
        detail = payload.error
      }
    } catch {
      // 响应体不是 JSON 时忽略
    }
    throw new Error(`POST ${path} 失败：${detail}`)
  }
  return (await response.json()) as T
}

export async function putJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`PUT ${path} 失败：HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

export async function delJson(path: string): Promise<void> {
  const response = await fetch(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok && response.status !== 204) {
    throw new Error(`DELETE ${path} 失败：HTTP ${response.status}`)
  }
}
