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
