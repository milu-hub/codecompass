# 进度

## 当前任务
T2 源码文件扫描器

## 已完成
- T0 项目骨架（`d2e87f3`）：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite 8 / Pinia 4 / Element Plus / TS 前端，前后端经 Vite 代理连通
- T0 加固：Jackson 3 默认值实测契约（`JacksonThreeDefaultsTest`）、Element Plus 按需引入、前端 tsconfig 拆 app/node 双项目
- T1 GitHub 仓库浅克隆服务：URL 校验、稀疏浅克隆（4 条 git 命令）、落盘看门狗、三项终检、安全删除。单元测试 50 个 + 集成测试 2 个（真机克隆 spring-petclinic）

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
- 2026-09-17：不配置 git remote，保持本地提交
- 2026-09-17：**Jackson 3 默认值已由实测钉死**（`JacksonThreeDefaultsTest`）：自动配置的 `JsonMapper` 可注入；`FAIL_ON_TRAILING_TOKENS` 开；`FAIL_ON_UNKNOWN_PROPERTIES` 关；`FAIL_ON_NULL_FOR_PRIMITIVES` 开（包装类型不受影响）；`Instant` 输出 ISO-8601；**字段顺序为声明序**（社区流传的「字母序」在 Boot 4.1.1 下不成立）
- 2026-09-17：T10 须用 `jsonMapper.rebuild()` 派生宽松实例解析 LLM 输出，不得改全局 Bean
- 2026-09-17：Element Plus 改按需引入：JS 986 → 124 kB、CSS 360 → 42 kB；组件类型用 `element-plus/global`，不生成 `components.d.ts`
- 2026-09-17：前端 tsconfig 拆 app/node 双项目（`vue-tsc --build`）+ `@types/node` 锁 24.13.5；`tsconfig.node.json` 必须显式 `noEmit`，否则 `composite` 会把 `vite.config.js/.d.ts` 吐进源码目录
- 2026-09-17：仓库限制调整为 **文件数 ≤ 1000、单文件 ≤ 20MB、单仓库 ≤ 500MB**，一律按**落盘量**计（`.git` + 已检出工作区）—— 因为 `--filter=blob:none` + 稀疏检出下仓库真实大小与真实文件总数拿不到，而全量下载与调 GitHub API 都被禁止
- 2026-09-17：限制校验统一归 **T1**（看门狗 + 终检），T2 不重复校验
- 2026-09-17：克隆超时是**整个流程的总预算 60s**，不是每条命令 60s
- 2026-09-17：**看门狗用进程内 NIO 遍历，不用 `du` 子进程** —— 开发机是 Windows，`du` 非系统自带（本机只有 IDE 捆绑的 BusyBox 版），靠它会让看门狗静默失效
- 2026-09-17：克隆 URL 只接受 `https://github.com/...`；SSH 形式会走密钥与交互提示，与「禁用交互」冲突
- 2026-09-17：看门狗与超时**共用同一条 kill 路径**（先 `ProcessHandle.descendants()` 再父进程）；看门狗调度器为共享单例，避免并发分析时泄漏线程池
- 2026-09-17：**删除工作区前必须清只读位** —— git 在 Windows 上把 `.git/objects/pack/*.pack|.idx|.rev` 设为只读（真机实测 9 个），`Files.delete` 会拒绝，导致克隆成功但清理失败、临时目录永久残留
- 2026-09-17：「仓库不存在」路径耗时**高度不稳定**（三次实测 2.7s / 57.8s / 63.7s），可能越过 60s 预算而被报成超时。因此超时文案必须点明「仓库不存在 / 私有仓库 / 网络过慢」三种可能，并附 git stderr 尾部
- 2026-09-17：T2 禁止用 `git ls-files` 枚举待解析文件 —— 稀疏检出的索引含 101 个 `skip-worktree` 幽灵条目（实测列出 132 个而磁盘只有 31 个）
