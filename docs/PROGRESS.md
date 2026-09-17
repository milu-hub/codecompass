# 进度

## 当前任务
T1 GitHub 仓库浅克隆服务

## 已完成
- T0 项目骨架（`d2e87f3`）：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite 8 / Pinia 4 / Element Plus / TS 前端，前后端经 Vite 代理连通

## 阻塞项
（空）

## 遗留 / 待验证
- **Jackson 3 默认值变更尚未实测**：`FAIL_ON_TRAILING_TOKENS`、`FAIL_ON_NULL_FOR_PRIMITIVES` 在 Jackson 3 中由「关」变「开」，T0 的 `/health` 不触及这些路径。T10 解析 LLM 输出前必须先验证，否则 LLM 常见的 markdown 围栏 / 结尾多余字符会直接抛异常
- Element Plus 整包引入导致 JS chunk 986 kB（build 有 >500 kB 警告）。按需引入需加 `unplugin-vue-components`，与「依赖尽量少」冲突，留到 T8 做界面时再定
- 前端未装 `@types/node`（当前无一处使用 Node API）。若将来需要，注意其 `latest` 标签为 22.20.3，与本机 Node 24.19 不匹配，必须显式 pin

## 决策记录
- 2026-09-17：确定 MVP 边界，产品目标多语言，首发 Java 解析器
- 2026-09-17：本机 Redis 被 Device Guard 阻止，MVP 改用内存缓存
- 2026-09-17：Spring Boot 定为 4.1.1（4.2.0-M1 是里程碑版，不用）；web starter 用 `spring-boot-starter-webmvc`，`starter-web` 在 4.x 已废弃
- 2026-09-17：前端 TypeScript 锁 5.9.3 —— TS 7 是原生版，不再导出 `typescript/lib/tsc`，vue-tsc 3.3.11 无法驱动
- 2026-09-17：加 maven-enforcer 护栏禁 Jackson 2 的 databind/core；`jackson-annotations` 必须保留（Jackson 3 的 databind 依赖它）
- 2026-09-17：T0 不引 web 切片测试依赖（Boot 4 已把 `@WebMvcTest` 迁到 `spring-boot-webmvc-test`），改用 `@SpringBootTest(RANDOM_PORT)` 真实端口验证
- 2026-09-17：后端划出 `backend/` + `frontend/` 平铺；包结构约定语言特有实现放 `analyzer/<language>/`
- 2026-09-17：不配置 git remote，保持本地提交（TASKBOOK / TASKS.md 均未要求远程仓库，R6 的回滚需求由本地提交满足）
