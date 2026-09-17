# 进度

## 当前任务
T1 GitHub 仓库浅克隆服务

## 已完成
- T0 项目骨架：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite / TS 前端，前后端经 Vite 代理连通

## 阻塞项
（空）

## 决策记录
- 2026-09-17：确定 MVP 边界，产品目标多语言，首发 Java 解析器
- 2026-09-17：本机 Redis 被 Device Guard 阻止，MVP 改用内存缓存
- 2026-09-17：Spring Boot 定为 4.1.1（4.2.0-M1 是里程碑版，不用）；web starter 用 `spring-boot-starter-webmvc`，`starter-web` 在 4.x 已废弃
- 2026-09-17：前端 TypeScript 锁 5.9.3 —— TS 7 是原生版，不再导出 `typescript/lib/tsc`，vue-tsc 3.3.11 无法驱动
- 2026-09-17：加 maven-enforcer 护栏禁 Jackson 2 的 databind/core；`jackson-annotations` 必须保留（Jackson 3 的 databind 依赖它）
- 2026-09-17：T0 不引 web 切片测试依赖（Boot 4 已把 `@WebMvcTest` 迁到 `spring-boot-webmvc-test`），改用 `@SpringBootTest(RANDOM_PORT)` 真实端口验证
- 2026-09-17：后端划出 `backend/` + `frontend/` 平铺；包结构约定语言特有实现放 `analyzer/<language>/`