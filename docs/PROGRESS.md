# 进度

## 当前任务
T1 GitHub 仓库浅克隆服务

## 已完成
- T0 项目骨架（`d2e87f3`）：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite 8 / Pinia 4 / Element Plus / TS 前端，前后端经 Vite 代理连通
- T0 加固：Jackson 3 默认值实测契约（`JacksonThreeDefaultsTest`）、Element Plus 按需引入、前端 tsconfig 拆 app/node 双项目

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
- 2026-09-17：不配置 git remote，保持本地提交（TASKBOOK / TASKS.md 均未要求远程仓库，R6 的回滚需求由本地提交满足）
- 2026-09-17：**Jackson 3 默认值已由实测钉死**（`JacksonThreeDefaultsTest`，7 例）：Boot 4 自动配置的 `JsonMapper` 可直接注入；`FAIL_ON_TRAILING_TOKENS` = 开；`FAIL_ON_UNKNOWN_PROPERTIES` = 关；`FAIL_ON_NULL_FOR_PRIMITIVES` = 开（包装类型不受影响）；`Instant` 输出 ISO-8601 字符串；**字段顺序为声明序**——社区流传的「`SORT_PROPERTIES_ALPHABETICALLY` 在 Jackson 3 默认开启」在 Boot 4.1.1 下不成立
- 2026-09-17：由实测结论推出 T10 做法——LLM 输出常带 markdown 围栏与结尾杂字符，而 `FAIL_ON_TRAILING_TOKENS` 默认开启，故 T10 必须用 `jsonMapper.rebuild()` 派生宽松实例，不得改全局 Bean
- 2026-09-17：Element Plus 改按需引入（`unplugin-vue-components` + `ElementPlusResolver`）：JS 986 → 124 kB、CSS 360 → 42 kB、chunk 警告消除；组件类型取 `element-plus/global`，不生成 `components.d.ts`，避免「`vue-tsc` 先跑、声明后生成」的构建顺序陷阱
- 2026-09-17：前端 tsconfig 拆为 app / node 两个项目（`vue-tsc --build`）并加 `@types/node` 锁 24.13.5 —— `@vue/tsconfig/tsconfig.dom.json` 显式设 `types: []` 以隔离 Node 类型，若让 `vite.config.ts` 与 `src` 共用一份配置，这个 Node 上下文文件就是在「无 Node 类型」下被检查的
