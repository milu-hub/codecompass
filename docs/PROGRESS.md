# 进度

## 当前任务
T3 LanguageAnalyzer 接口定义

## 已完成
计数口径为**本任务新增**，避免后续任务读到过期的累计值。当前合计：**单元 66 + 集成 4 = 70，全绿**。

- T0 项目骨架（`d2e87f3`）：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite 8 / Pinia 4 / Element Plus / TS 前端，前后端经 Vite 代理连通。新增单元 10
- T0 加固：Jackson 3 默认值实测契约（`JacksonThreeDefaultsTest`）、Element Plus 按需引入、前端 tsconfig 拆 app/node 双项目
- T1 GitHub 仓库浅克隆服务：URL 校验、稀疏浅克隆（4 条 git 命令）、落盘看门狗、三项终检、安全删除。新增单元 41 + 集成 2（真机克隆 spring-petclinic、失败路径）
- T2 源码文件扫描器：按 `scan.sources[]` 配置扫描、源码根片段匹配推导包名、多模块、排除 package-info/module-info。新增单元 15 + 集成 2（真机扫描 petclinic、真机扫描 8 模块 microservices）

## 本地运行（已实测通过）
```bash
# 后端 :8080
mvn -f backend/pom.xml clean package
java -jar backend/target/codecompass-backend-0.0.1-SNAPSHOT.jar

# 前端 :5173（Windows 上 npm.ps1 被执行策略拦截，必须走 cmd /c 或 npm.cmd）
cd frontend && cmd /c "npm install" && cmd /c "npm run dev"

# 测试
mvn -f backend/pom.xml test                                # 单元测试（集成测试默认排除，可离线重复）
mvn -f backend/pom.xml test -Dsurefire.excludedGroups=     # 含真机克隆集成测试，需网络

# 前端构建（含类型检查）
cd frontend && cmd /c "npm run build"
```
冒烟结果（多次运行一致）：后端启动约 1.7s，全程无 WARN/ERROR；`:8080/health` 与经 Vite 代理的
`:5173/health` 均 200，且两者时间戳相差约 100ms（证明请求真的穿透了代理，不是缓存）；
首页 200。启动无绑定异常，说明 `codecompass.scan` 与 `codecompass.clone` 下的
`60s`、字节阈值、源码根均正确解析。

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
- 2026-09-17：**T2 不用 glob 匹配**。实测 Java `PathMatcher` 的 `glob:**/src/main/java/**` 对 `src/main/java/org/foo/Bar.java` 返回 **false**（Java 的 `**/` 不匹配零层目录），而 git 用同一模式确实检出了该文件 —— 两个方言冲突。若直接复用，单模块仓库（黄金样本 petclinic）会**静默返回 0 个文件**。改用**源码根片段序列匹配**（任意深度命中、取最后一次出现）
- 2026-09-17：T2 **不读文件内容推导包名** —— 纯路径推导。否则某门语言的语法知识会渗进语言无关的扫描器，T3 的 LanguageAnalyzer 分层当场破功
- 2026-09-17：多语言扩展点落在 `codecompass.scan.sources[]`（language / source-root / file-extensions / excluded-file-names）。新增语言只追加一项，克隆与扫描代码都不改
- 2026-09-17：**T1 的稀疏检出模式由 `scan.source-root` 派生**（`**/<source-root>/**`），`CloneProperties.pathPatterns` 改名 `extraPathPatterns` 只保留非源码模式（pom.xml）。理由是避免"检出范围"与"扫描范围"两处各配一份而漂移 —— 漂移的后果是 T2 扫出空集合却不报错。这是本轮唯一触碰 T1 的改动，已获豁免
- 2026-09-17：`CodeUnitFileInfo.packageName` 在默认包时取 `""` 而非 null；结果按 `relativePath` 排序保证确定性；多模块同名类**不按 unitName 去重**
- 2026-09-17：**多模块黄金样本定为 `spring-petclinic/spring-petclinic-microservices`**（8 模块，无根级源码，53 个 .java）。与 petclinic 互补：后者单模块、源码在根级，专门照出 glob 方言的零层前缀坑；前者全是带模块名前缀的路径。两者均已进集成测试
- 2026-09-17：实测该样本克隆+检出 8.4s，落盘 62 文件 / 0.13 MB / 最大单文件 10.5 KB，远在 1000 文件、20MB 单文件、500MB 总大小三项上限之内
- 2026-09-17：**TASKBOOK §03 的第三个黄金样本（「一个你熟悉的项目」）仍待用户选定** —— 它用于人工判断解析结果是否合理，无法代选。T12 端到端验收会用到
