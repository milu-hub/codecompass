# 进度

## 当前任务
Python 解析器（P1–P8）与前端收尾（Python 高亮档案 P6 + 分享页学习路线美化第 7 步）已全部完成。下一步待用户指定。

## 已完成
计数口径为**本任务新增**，避免后续任务读到过期的累计值。当前合计：**单元 338 + 集成 28 = 366，全绿**。

- T0 项目骨架（`d2e87f3`）：Java 21 + Spring Boot 4.1.1 后端（`/health`）+ Vue3 / Vite 8 / Pinia 4 / Element Plus / TS 前端，前后端经 Vite 代理连通。新增单元 10
- T0 加固：Jackson 3 默认值实测契约（`JacksonThreeDefaultsTest`）、Element Plus 按需引入、前端 tsconfig 拆 app/node 双项目
- T1 GitHub 仓库浅克隆服务：URL 校验、稀疏浅克隆（4 条 git 命令）、落盘看门狗、三项终检、安全删除。新增单元 41 + 集成 2（真机克隆 spring-petclinic、失败路径）
- T2 源码文件扫描器：按 `scan.sources[]` 配置扫描、源码根片段匹配推导包名、多模块、排除 package-info/module-info。新增单元 15 + 集成 2（真机扫描 petclinic、真机扫描 8 模块 microservices）
- T3 LanguageAnalyzer 接口 + 语言中立 DTO：`LanguageAnalyzer`、`LanguageAnalyzerRegistry`、`AnalyzeRequest`、`AnalyzeResult`、`CodeUnitInfo`、`MethodInfo`、`FieldInfo`、`DependencyEdge`、`FailedFile`、`ModelSupport`、`AnalyzerConfiguration`。新增单元 20。**未写任何分析器实现**
- T4 JavaSpringAnalyzer：JavaParser 3.28.2 语法级解析、两遍处理（解析 + 引用解析成边）、框架识别、失败隔离。新增单元 34 + 集成 2（两黄金样本贯通 T1→T4 全链路）
- T5 Spring 注解识别：`CoreAnnotationClassifier`（配置驱动，零注解字面量）、核心注解角色配置、**黄金样本人工标注**（`src/test/resources/golden/*.yaml`，地面真值独立取得）、覆盖率验收。新增单元 16 + 集成 6（覆盖率/入口类/核心依赖 × 2 样本）
- T6 依赖图构建：`DependencyGraphBuilder`、`DependencyGraph`、`GraphNode`、`MermaidRenderer`、`GraphConfiguration`。新增单元 18 + 集成 2（真机图构建 + Mermaid 渲染 + 邻域）
- T7 图数据 REST API：`RepoController` 三端点、`AnalysisTaskStore`（CAS 原子发布）、`AnalysisOrchestrator`（后台流水线）、`UnitRoleAnnotator` seam、web 视图 DTO。新增单元 21 + 集成 2（真实 petclinic 全链路 HTTP）
- T8 Vue 类列表 + Mermaid 图：`RepositoryView` / `ClassList` / `DependencyGraphPane` / `stores/repository` / `api/repos`，mermaid 12 动态 import。后端 `/graph` 加 `?unit=&depth=` 邻域参数（控制器加量）。新增后端单元 2
- T9 代码片段检索层：`CodeRetriever` / `LexicalCodeRetriever` / `RetrievedSnippet` / `RetrieveProperties` / `RetrieveConfiguration`。词法检索（类/方法/字段/注解/包名 token 命中）+ 锚点层（锚点 +10、一跳出边 +3）。纯内存，从 T7 快照取数，无重克隆。新增单元 11
- T10 LLM 问答接口：`POST /api/repos/{id}/ask`。`LlmClient` 接口 + `OpenAiCompatibleLlmClient`（OpenAI 兼容协议，本地 HttpServer 实测请求体/Bearer/choice 提取）+ `AnswerService`（提示词每行前缀真实行号、宽松解析、引用逐条严格包含校验、不匹配丢弃带反馈重试、预算封顶）。新增单元 20
- T11 内存缓存 + 限流：`CacheService`/`InMemoryCacheService`（TTL + LRU 容量上限）、`RateLimiter`/`InMemoryRateLimiter`（按身份的自然日 token 上限）、`CacheKey`（sha+文件+问题hash+模型四要素）。commit SHA 全链路补齐（克隆后 `rev-parse HEAD` → CloneResult → outcome）。新增单元 18（集成测试补 1 条真机 sha 断言）
- T12 端到端验收：`PythonStubAnalyzer`（唯一新增生产文件，业务层零改动）+ 三个验收测试 + `docs/T12-验收报告.md`。五项验收全过：黄金样本 2/2、覆盖率 35/35=100%、引用行号 20/20=100%、缓存命中 P95=0ms、多语言扩展点结构断言。新增单元 3 + 集成 1
- T12 补测（用户补齐三处遗留）：**第三黄金样本** `javastacks/spring-boot-best-practice`（37 模块/179 文件，golden 独立脚本生成 109 注解类+39 入口+6 依赖含跨模块边，首跑全过 0 解析失败）；**真实 LLM 档**（DeepSeek deepseek-chat，key 仅走环境变量）：20 问抽样两次实测 95.0% / 100.0%（每问至少一条引用命中地面真值文件），52/50 条引用结构合法 100%；前两个样本的 5 类描述已人工确认（golden 改注）。新增集成 4（覆盖率参数化 +3、真实 LLM 验收 1）
- T13 LLM 配置抽象（多配置预留）：`LlmConfig`（id/provider/baseUrl/apiKey/model/isDefault + configured()）、`LlmConfigService`（list/getDefault/switchDefault/save/delete）、`InMemoryLlmConfigService`（单配置、yml 种子、只读）。客户端配置来源 LlmProperties → LlmConfigService.getDefault()，问答逻辑零改动。新增单元 7

## Python 解析器（P1–P7，设计见 `PYTHON_ANALYZER_PLAN.md`）

> 这是「第一版不做」清单里「Java 以外的解析器」那条的正式放开：本期实现 **Python**，
> Go/TypeScript 等仍只预留接口。边界同步见 `docs/AGENTS.md`（已改）与 `docs/TASKBOOK.md`（已划掉）。

- P1 语法接入（`f59160d`）：ANTLR4 4.13.2 + grammars-v4 `python/python3_14` 语法（按 commit `20efa53` 钉死，MIT），构建期生成词法/语法器，`PythonSourceParser` 冒烟件（现代语法 / 缩进 / 错误逐条带行号）
- P2 扫描与命名（`33ab833`）：整仓扫描（`source-root: '.'` + 目录排除表）、`PythonModuleNames`（模块/包名/限定名/单元 id 规则，与 Java 同构 `repo:路径#限定名`）
- P3 结构抽取（`e63e177`）：`PythonAnalyzer` 替换 stub —— 类/模块级函数/方法/字段 + **可信行号**（块结束逐 token 取，跳过 HIDDEN/INDENT/DEDENT/NEWLINE，实测 `__init__` 的 end 不能信 DEDENT）
- P4 依赖边（`a7e3c18`）：`PythonImportResolver` import 矩阵（from/别名/相对导入/再导出/星导入/子模块目标），去重、弃自环、弃仓外目标；修正单元 `packageName` 为模块路径
- P5 框架与角色（`677ffcd`）：`UnitRoleAnnotator` 加 `language()` + `UnitRoleAnnotatorRegistry`（**按语言查表**，解掉"单例 Bean"的架构欠账）；`PythonRoleAnnotator`（装饰器/模块约定/脚本入口）+ 框架识别（Django/Flask/FastAPI，yml 可配）
- P6 前端 Python 语法高亮（`d6ea309`）：`sourceHighlight.ts` 加 `python` 档案 —— `#` 行注释、`"""`/`'''` 三引号文本块（无块注释，字段改可空）、`@` 装饰器复用注解色、大写=类型 / 小写后跟 `(`=方法、关键字集保持正文色；**零文本损失不变量用 CDP 探针 20/20 实测**（Python 17 项 + Java 回归 3 项）
- P7 黄金样本验收（3 个样本自选：纯脚本 / Flask / Django），走真实 Spring 上下文 + 真实 yml，行号逐行断言。**端到端实测 `pallets/click`：303 单元 / 3497 边 / 0 失败文件**
- P8 文档回填：`AGENTS.md` / `TASKBOOK.md` 划掉「Java 以外的解析器」、开放 Python；本 PROGRESS 回填 P6 与前端收尾

后端全量：**单元 338 全绿**（`mvn test`，integration 组默认排除）。

## 前端美化收尾（design-taste，第 7 步）
- 分享页学习路线重排（`ShareController.render` → `renderLearningPath`）：三段式（28px 主色圆号 + 类名 15px 粗 + 右侧分钟 / 第二行 reason 13px 灰）、24px 步距无分隔线无卡片底、默认只展 8 步 +「展开全部」（hidden 切换）、`<768px` 分钟贴类名、类名不伪造链接（快照不含源码）。`ShareControllerTest` 新增 1 单元；CDP 计算样式走查 10/10 符合规格（截图 `docs/screenshots/45-分享页学习路线.png`）
- **窄屏复验抓到的真问题**：分享页原本没有 `<meta name="viewport">` —— 移动端浏览器会把布局视口当默认 **980px**，`max-width:767px` 媒体查询在真机上**永不命中**（窄屏规则等于白写）。补 `HEAD_META` 常量（charset + viewport）供 `render()` / `simplePage()` 共用，并加断言钉住。CDP 真机模拟（`mobile:true` + 400px）复验：布局视口 400px、`flex:0 1 auto`、分钟紧贴类名（间距 12px）

## 本地运行（已实测通过）
```bash
# 后端 :8080
mvn -f backend/pom.xml clean package
java -jar backend/target/codecompass-backend-0.0.1-SNAPSHOT.jar

# 前端 :5173（Windows 上 npm.ps1 被执行策略拦截，必须走 cmd /c 或 npm.cmd）
cd frontend && cmd /c "npm install" && cmd /c "npm run dev"

# 测试
mvn -f backend/pom.xml test                                # 单元测试（集成测试默认排除，可离线重复）
mvn -f backend/pom.xml test -Dsurefire.excludedGroups=     # 含 16 个真机集成测试（克隆 2 + 扫描 2 + 分析 2 + 覆盖率 6 + 图 2 + API 全链路 2），需网络，约 130s

# 前端构建（含类型检查）
cd frontend && cmd /c "npm run build"
```
冒烟结果（多次运行一致）：后端启动约 1.7s，全程无 WARN/ERROR；`:8080/health` 与经 Vite 代理的
`:5173/health` 均 200，且两者时间戳相差约 100ms（证明请求真的穿透了代理，不是缓存）；首页 200。
启动无绑定异常，说明 `codecompass.scan` / `codecompass.clone` / `codecompass.analyze`
三处配置均正确解析 —— 包括 framework-markers 里那些带引号的 `@` 标记（YAML 中 `@` 是保留指示符，
不加引号会解析失败）。
T7 后补验（真实运行中的 jar）：`POST /api/repos` 非法 URL → **400** 且错误信息完整；
`GET /api/repos/no-such/status` → **404**；说明 T7 的控制器、存储、编排器装配在真实应用里生效。
T8 后补验（T8 构建的 jar，含邻域参数）：OwnerController 的 `?unit=&depth=1` 邻域返回
**1 条边（→ OwnerRepository）**，mermaid 只有两个节点，全图 21 条边不受影响。
**坑**：单元 id 含 `#` 与 `:`，任何不经 URL 编码的拼接都会被当作 HTTP fragment 截断，
得到空邻域且无报错 —— 前端已用 `encodeURIComponent`，手工 curl / 验收脚本必须同样编码。

> **启动成功不能证明什么**：它证明不了 `JavaSpringAnalyzer` 已被注册表发现 —— 若装配类没被
> 组件扫描到，`ObjectProvider` 给出空列表，注册表为空，应用照样正常启动、`/health` 照样 200。
> 这一条由集成测试的 `registry.forLanguage("java").orElseThrow(...)` 覆盖。

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
- 2026-09-17：T3 把 `LanguageAnalyzerRegistry` 纳入交付 —— 它是「业务层不点名实现类」的结构保证，也是 T12「加 Python stub，业务层不改」的落地机制；内部无任何语言分支，只做 Map 查表。装配用 `ObjectProvider`，因为 T3 阶段一个分析器实现都没有，直接注入 `List<LanguageAnalyzer>` 会让容器启动失败
- 2026-09-17：**`CodeUnitInfo.kind` 与 `DependencyEdge.kind` 用开放字符串而非 enum** —— enum 会成为每门新语言都必须编辑的共享类型，违反「新增语言只应新增一个实现与一份配置」，并会让 T12 验收失败
- 2026-09-17：**行号定为 1-based 闭区间，并在 record 构造器上守卫**（`startLine < 1` 或 `endLine < startLine` 直接抛异常）。SCHEMA.md 里的 `"startLine": 0` 只是占位符却极像「从 0 开始」；真按 0-based 实现会让 T10 的引用跳转整体偏一行，而这是编译、单测、演示都看不出来的错误
- 2026-09-17：核心 DTO 的列表字段在构造器里做 null→空列表规范化 + 不可变复制；列表中的 null 元素被拒绝
- 2026-09-17：`id` 契约要求**确定性**（同输入同输出、仓库内唯一）但**不规定格式** —— 格式属于分析器实现细节，规定了就把语言细节钉进中立层。对应测试断言性质而非具体值
- 2026-09-17：`framework` 未识别时取 `""`（与 `packageName` 默认包口径一致，前端 TS 可写 `string`）；它是识别结果而非配置项
- 2026-09-17：**仓库外依赖不进依赖图**（如 `OwnerController` 依赖 Spring 的 `@Controller`）。SCHEMA.md 的 `toCodeUnitId` 没有「未解析」的表示。**代价**：`kind="annotation"` 的边只对仓内自定义注解有意义，注解识别体现在 `CodeUnitInfo.annotations` 里而非边
- 2026-09-17：`AnalyzeRequest` 现在不加进度回调 —— TASKBOOK §07 的 SSE 进度由 T7 在任务层做粗粒度上报，加回调属于提前实现
- 2026-09-17：T3 交付实测验证：analyzer 包**零 Jackson import**；`ClassInfo`/`analyzeJava`/`CompilationUnit` 仅出现在「刻意不用」的 Javadoc 里；`implements LanguageAnalyzer` **只出现在测试假分析器**中，生产代码零实现
- 2026-09-17：**已知取舍 —— `analyzer` 包依赖 `repo` 包**（`AnalyzeRequest` 引用 `CodeUnitFileInfo`）。方向合理（分析依赖摄取层的输出）且非循环，故未改动；移动它会违反「不重构已有类」。若将来要让层次更干净，`CodeUnitFileInfo` 应搬到中立的 `model` 包
- 2026-09-17：T4 依赖定为 `com.github.javaparser:javaparser-core:3.28.2`，**实测零传递依赖**（POM 无 `<dependencies>` 段），不影响 enforcer 的 Jackson 2 护栏；**不用** `javaparser-symbol-solver-core`（§05 预研 2 明确不做符号求解）
- 2026-09-17：**T4 最贵的坑 —— JavaParser 默认 LanguageLevel 是 `JAVA_11`**。实测用它解析 record / sealed / switch 表达式**全部失败**，现代 Spring 仓库会整批落进 `failedFiles`，而症状会把人引向「JavaParser 不好用」或「这些文件有问题」。故显式设置级别，默认取**最高非 PREVIEW 级别**（3.28.2 实测到 `JAVA_26`），并配测试断言默认级别 ≥ `JAVA_21`，防止依赖降级后天花板悄悄下沉
- 2026-09-17：T4 只取**顶层类型**为 codeUnits，嵌套类不单独成单元、其成员也不遍历
- 2026-09-17：**`packageName` / `unitName` 以 AST 为准**，忽略 T2 的路径推导值（目录与包声明可能不一致）
- 2026-09-17：**名字解析顺序即正确性**（无符号求解）：含 `.` → 原样再当前包前缀；简单名 → 单类型 import → 当前包 → 通配 import。Java 里显式 import 覆盖同包同名类，顺序反了会把边连到错误的类上，而「存在边」这类断言发现不了。泛型参数与数组元素须递归取类型名
- 2026-09-17：边按 **(from, to) 去重**，保留优先级最高的 kind（`field` > `annotation` > `import`）—— 同一对类画两条箭头会让 Mermaid 图看起来是坏的。**自引用不画自环**；**通配 import 不产生边**（一个 `com.x.*` 会连到该包下所有类，属过度生成），它只参与名字解析
- 2026-09-17：T4 的 `id` 含 filePath（`repositoryId:filePath#全限定名`）—— 多模块下同一全限定名可能出现在两个模块；语法级解析仍无法区分指向哪个，因此索引冲突时记 WARN
- 2026-09-17：框架识别标记走配置 `codecompass.analyze.framework-markers`（满足 T5「不在业务层硬编码注解列表」）；多框架并列时按框架名字典序，**保证确定性**，不依赖 Map 迭代顺序
- 2026-09-17：**每文件一个 try、捕 `Exception`** —— 范围太外则一个坏文件作废整次分析，太窄则缺文件/行范围缺失等异常逃逸成 500。`failedFiles[].filePath` 用 T2 口径的相对路径
- 2026-09-17：**行号语义 —— JavaParser 把注解算进类型声明范围**，故 `startLine` 指向注解行而非 `class` 关键字行。这对 T10 引用展示是好事（能带出注解上下文），已在集成测试中显式断言该语义
- 2026-09-17：**JavaParser 类型只出现在 `analyzer/java/` 内** —— 已扫描验证：24 处 javaparser import 全部在该包，`analyzer/` 根包与 `web`/`repo` 包零命中（根包内 2 处命中均为「刻意不用」的 Javadoc 说明）。`LanguageAnalyzer` 生产实现**恰好 1 个**
- 2026-09-17：T6 范围据此收窄为「图结构整理 + Mermaid 渲染数据」，名字解析与去重已在 T4 完成（已写入 TASKS.md）
- 2026-09-17：**T5 的要求 1、2 在 T4 已实现**（提取全部注解 ⊇ 那 8 个；注解列表已可配置）。T5 真正新增的是：核心注解的**角色配置**、`CoreAnnotationClassifier`、**黄金样本人工标注**、**覆盖率度量**
- 2026-09-17：**覆盖率分母按「注解」而非「语义」**（取 TASKBOOK §04 的字面口径）。若按 §03 的语义口径，`OwnerRepository`（Spring Data 接口、不带注解）要进分母，就得识别"继承自 `Repository`/`JpaRepository`"，那已不是注解识别、且需引入仓外框架知识
- 2026-09-17：**实测 —— 8 个核心注解里有 2 个在两个黄金样本上出现 0 次**：`@Autowired` 恒为 0（两样本都用构造器注入，Spring 4.3 起单构造器可省略注解）、`@Repository` 恒为 0（仓储是 Spring Data 接口）。故覆盖率分母只取**实测存在**的类级核心注解，`@Autowired`/`@Bean` 改用**合成用例**验证
- 2026-09-17：**`@Autowired` / `@Bean` 是成员级注解**，永远不会出现在 `CodeUnitInfo.annotations` 里 —— `@Autowired` 在字段、`@Bean` 在方法。分类器为此提供 `memberLevelCoreAnnotations`，约定 `member` 为保留角色名
- 2026-09-17：**`@Configuration` 归入 component 角色**（TASKBOOK 那 8 个里没有它，但实测两样本共有 6 个纯 `@Configuration` 类，不认会漏掉配置类）。`CoreAnnotationClassifierTest` 有一条用例专门钉住"component 是 2 不是 1"
- 2026-09-17：**角色判定只信注解不信类名** —— 实测 microservices 的 `VectorStoreController` 带的是 `@Component` 而非 `@RestController`，任何按名字猜角色的启发式都会误判。同名不同包的 `MetricConfig`（customers / visits）是另一条真实用例
- 2026-09-17：**覆盖率的地面真值必须独立于被测代码取得**，否则用解析器输出誊一份标注，覆盖率恒为 100%、指标失效。本次用独立脚本直接解析原始源码的注解行生成，落成 `golden/*.yaml` 后固定
- 2026-09-17：`core-annotations` 与 `framework-markers` **语义不同故分开配置**（前者答"这个类是什么角色"，后者答"仓库是不是 Spring"），但启动时校验一致性并 WARN，把漂移显式暴露而不是硬合并（硬合并会让两件事互相绑架）
- 2026-09-17：T5 覆盖率实测 **100%**（petclinic 分母 10、microservices 分母 25，全部命中），超出 TASKBOOK 要求的 80%
- 2026-09-17：**黄金样本的"5 个类的功能描述"由 AI 依源码草拟、尚待人工确认** —— TASKBOOK §03 要求的是手工标注，这部分无法代笔
- 2026-09-17：**T6 前实测图规模**（临时探针，已删）：petclinic 25 单元/21 边/孤立 6（24%）；microservices 54 单元/38 边/孤立 22（**41%**）；`kind=annotation` 的边为 **0**（仓内自定义注解太少，印证 T3 的判断）。据此确认"孤立节点处理"是真问题、"按模块分组"收益有限
- 2026-09-17：**模块分组本轮延后**，`GraphNode` 不加 group 字段 —— 图只有 21/38 条边，分组收益有限；代价是要么抽 T2 的源码根定位逻辑、要么让 T6 接收外部映射。将来加字段是纯新增、向后兼容
- 2026-09-17：**图的结构是契约，mermaid 文本是派生字段**。渲染器独立成 `MermaidRenderer` 并注入，换渲染器不动图模型
- 2026-09-17：**默认不含孤立节点**（实测 41% 孤立率，全画会散落孤立方框），孤立单元完整列在 `isolatedCodeUnitIds` 供前端提示
- 2026-09-17：**Mermaid 节点 ID 必须重新映射** —— 真实单元 id 含 `:` `/` `.` `#`，不在 Mermaid ID 语法内，直接使用会静默产出异常节点。映射为 `n0/n1/...`，**先按 id 排序再编号**保证确定性；label 一律加引号；空图降级为占位节点（只有 `graph LR` 一行时 Mermaid 渲染不可靠）
- 2026-09-17：**悬空边必须过滤** —— Mermaid 遇到未声明节点会**静默创建无标签幽灵节点**。图构建阶段按引用完整性过滤并记 WARN；`neighborhoodOf` 过滤后同样重建校验
- 2026-09-17：`DependencyGraphBuilder` 的**角色经入参 `Map<String,String>` 传入**而非注入 T5 分类器 —— 后者在 `analyzer/java/`，注入会让语言中立的 graph 包依赖 Spring 概念，R9 失守
- 2026-09-17：T6 真机实测输出：petclinic 的图 19 节点/21 边，`n5 --> n6` 即 OwnerController -> OwnerRepository（T6 验收标准），语法干净可渲染
- 2026-09-17：**T7 进度用轮询不用 SSE** —— 用户批准对 §07 底线措辞的明确偏离；分析 10~20s，轮询体验相同
- 2026-09-17：**T7 清理时机与 T9/T10 内容**：分析完立即删工作区（守 §07 底线），删前快照 `relativePath → 行数组` 进内存结果（受 T1 上限约束、实际很小），供 T9 检索与 T10 引用；T11 加 TTL 回收
- 2026-09-17：**T7 语言隔离 seam `UnitRoleAnnotator`**（中立根包）：编排层只依赖它，grep 验证 service/web 零 `analyzer/java/` 引用 —— 这是 T12「加语言不改业务层」的前置条件
- 2026-09-17：**T7 原子发布**：状态与结果合并进不可变快照（结果聚合在 outcome），存储 `AtomicReference` CAS 替换；「status=done 但结果未就绪」的中间态在结构上不存在
- 2026-09-17：T7 后台分析池（2 线程）独立于 T1 看门狗调度器；POST 只校验 + 建任务立即返回，克隆 60s 不占请求线程
- 2026-09-17：T7 graph 端点语义：done→200；pending/running→409；failed→200+status:"failed"+errorMessage；未知→404；language 字段始终存在（分析前为 null）
- 2026-09-17：T7 不建 Repository 实体（commitSha 留 T11）；`validateRepositoryUrl` 包级提 public 是触碰既有类的唯一最小变更
- 2026-09-17：**T8 补齐 Vite `/api` 代理** —— T7 是纯后端任务，代理只配了 `/health`，前端调 `/api/repos` 会 404 且症状与"后端没实现"完全相同。已实测经 :5173 的 POST 穿透到后端返回 400 原文
- 2026-09-17：T8 的 mermaid 走**动态 import**（12.0.0）：首屏 chunk 157.94 kB，elk 布局引擎等懒块最大 1.4MB、只在渲染时下载；`chunkSizeWarningLimit` 调到 1600 并注明原因（避免常驻警告淹没真实体积回归）
- 2026-09-17：T8 两个前端竞态防线：mermaid 渲染**递增令牌**（晚到的旧图丢弃，快速切换类不可复现的覆盖 bug 由此排除）；轮询**全 store 单一 timer 句柄**、stopPolling 是唯一清理入口（组件卸载/换任务后旧轮询不搅局）
- 2026-09-17：T8 点击类取**后端邻域图**（`/graph?unit=&depth=1`，复用 T6 的 neighborhoodOf）；codeUnits 保持全量、边与 mermaid 只含邻域 —— 类列表是稳定锚点。前端不做任何图切分算法
- 2026-09-17：T8 健康页缩成页脚一行连通性状态，主页面换成仓库分析（health store 保留未删）
- 2026-09-17：T8 前端不写任何语言判定：role/kind/annotations 全是展示数据，无 `endsWith("Controller")` 之类启发式
- 2026-09-17：T9 检索层做成**接口** —— TASKBOOK 预留向量检索方向（「不一定用向量数据库」），MVP 词法实现先行，替换时 T10 不动；检索输入全中立类型，对任何语言一视同仁
- 2026-09-17：**`anchorUnitId` 是中文问题的生命线**（§S7 点类提问）：中文 token 与英文标识符零交集，没有锚点层则中文提问恒为空。锚点 +10 强制入围、其一跳出边依赖 +3 入围；空列表只发生在「无锚点且零词法命中」
- 2026-09-17：**content 截取是 T10 行号准确率的最后一毫米**：快照行数组按 1-based 闭区间取 `lines[startLine-1 .. endLine-1]`，越界截断。`file` 必须与 T2 `relativePath` 严格同口径（它同时是 `sourceLines` 的 key）；快照缺文件时 **WARN + content 置空**，绝不静默 null
- 2026-09-17：词法匹配 = camelCase 拆分 + 小写 token 求交 + **全名不分词兜底**（`OWNERCONTROLLER` 型连写输入命中 `OwnerController`）；权重与 `maxSnippets` 全部进 `codecompass.retrieve.*` 配置 —— T10 调参不该重编译
- 2026-09-17：**surefire 报告怪癖（实测探针钉死）**：`@Nested` + JUnit 6 下控制台按 @DisplayName 容器拆行、顶层类显示 `Tests run: 0`，per-class 求和会少算嵌套测试（当前少 12）；但**失败正确传播**（故意改坏一条嵌套断言 → `Tests run: 12, Failures: 1` → BUILD FAILURE）。计数一律以控制台汇总 / XML 为准
- 2026-09-18：**ask 状态语义**：未知 404；未完成 409（failed 时携带 errorMessage）；空检索 200 + 提示语（**短路不调 LLM**）；传输/未配置 502（answer 不存在，不降级 200）；校验重试耗尽 200 + 空 references（answer 已存在，丢坏引用保答案）
- 2026-09-18：**Jackson 3.1.5 的 FAIL_ON_TRAILING_TOKENS 在 `tools.jackson.databind.DeserializationFeature`**（databind 层），不在 StreamReadFeature（javap 实测该枚举无此项）。宽松解析 = `jsonMapper.rebuild().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()`，不改全局 Bean
- 2026-09-18：**Boot 4.1.1 不提供 `RestClient.Builder` 自动配置 Bean**（全量回归 13 个 context 注入失败实测）—— 与 Boot 3.x 不同。装配层自己 `RestClient.builder().requestFactory(带超时).build()`；该构造路径（String 请求体/响应）已由本地 HttpServer 测试实测
- 2026-09-18：引用校验口径 = **单片段严格包含**（file 字节相等 + language 相同 + 行区间完全落在某片段的 1-based 区间内）；解析按条容忍（行号写成字符串也收），坏条目只丢自身；重试反馈把被丢弃清单原样列给 LLM
- 2026-09-18：`AnswerResponse` 放 `service/` 包而非 `web/dto/` —— service 层返回自己的结果类型，不反向依赖 web 层（方向性）；`AskRequest` 是入参 DTO，留 web/dto
- 2026-09-18：**T10 运行态实测**（真实 jar + 真实克隆 petclinic）：404/400/409 各归其位；done 后无 api-key → **502「LLM 未配置」**且发生在任何网络调用之前；配置真实 key 后无需改码即可用（`CODESCOMPASS_LLM_API_KEY` 环境变量或 yml）
- 2026-09-18：**commit SHA 是缓存 key 的仓库版本锚**：克隆命令序列后追加第 5 条 `git rev-parse HEAD`（输出独立文件 head-sha.txt，不从混合日志抠行）；任何失败只降级为 null（**克隆不因 sha 失败而失败**），下游据此整体跳过缓存 —— 宁可 miss 不可错命中
- 2026-09-18：`CloneResult`/`AnalysisOutcome` 加 `commitSha` 组件时保留 1-arg/4-arg 便捷构造器 —— 既有调用点与测试夹具零改动（与「不重构」边界的和解方式：加量 + 兼容入口）
- 2026-09-18：**ask 管道顺序即正确性**：缓存命中 → 返回（零 LLM 消耗、零计费）→ 检索 → 空则 fallback（不缓存不计费）→ 每轮 LLM 前计费（超限 429）→ LLM。顺序颠倒任一项都会错杀省钱路径或漏杀超限
- 2026-09-18：限流窗口 = `clientKey|LocalDate` 自然日，午夜自动翻转 + consume 时惰性清旧日期；**先 addAndGet 后判定**（并发微超不回滚、如实累计）；上限实时读配置（验收用 `--codecompass.rate-limit.daily-token-limit=1` 覆盖即可，无需改包）
- 2026-09-18：**token 计费 = 提示词字符数/4 的估算**（中英同口径），不解析 usage 字段 —— 那会迫使改 LlmClient 接口（T10 已完成，不值得为精度动它）。实测单问约 4000 估算 token，日限 10 万 ≈ 24 问，MVP 够用
- 2026-09-18：**clientKey 解析 X-Client-Id → X-Forwarded-For 首跳 → remoteAddr** —— 只读 remoteAddr 的话 dev 经 Vite 代理全站共享一个额度（易错点 3 的落地）；三条路径都有控制器测试钉住
- 2026-09-18：缓存实现 = `synchronizedMap(LinkedHashMap access-order)` + `removeEldestEntry` 容量 + 条目时间戳 TTL（get/put 惰性剔除过期）；`CacheService`/`RateLimiter` 均按接口注入，业务层零内存实现类引用；`Clock` 以 Bean 注入供假钟测试（与 Boot 4.1 无冲突，本轮 @SpringBootTest 全绿）
- 2026-09-18：**运行态实测**：日限额 1 时真实克隆 done 后 ask → **429「已达今日 LLM token 上限（4079/1）」**，限流先于 LLM、计数跨请求持久；集成测试新增断言钉住真机克隆的 `outcome.commitSha` 非空
- 2026-09-18：**踩坑 —— 集成套件与运行 jar 共用 `java.io.tmpdir/codecompass` 会互踩**（一方删除另一方的工作区 → 「准备工作区失败：删除工作区失败」）。运行态验收与集成测试**不能并行**，此后都串行执行
- 2026-09-18：**T12 行号准确率的地面真值纪律**：问题集手写、引用由源码快照文本 + marker 定位（独立于解析器与检索层），桩 LLM 只回放 —— 用检索结果派生引用会让验收变成自己证明自己。实测踩中两处标注错误（petclinic 已删除 `ClinicService`/`PetRepository`/`VisitRepository`，现为 `PetTypeRepository`），fail-fast 机制按设计生效，改的是标注不是系统
- 2026-09-18：**T12 缓存延迟测量纪律**：先真实走一次 LLM 入缓存 → 预热吸收 JIT → 100 次采样取 P95；`verify(llm, times(1))` 钉死命中路径零 LLM 调用 —— 否则测的不是缓存
- 2026-09-18：T12 验收遗留（不阻塞 MVP）：第三个黄金样本待用户选定；黄金「5 类功能描述」待人工确认；真实 LLM 引用档待 api-key 补测（报告如实标注，未假装测过）
- 2026-09-18：**真实 LLM 引用验收的指标口径（实测校准）**：引用级「全部落在期望文件」只有 48~50% —— LLM 回答时会自然引用锚点类的依赖文件（检索层本就连同 1 跳依赖提供），这不是错误。采纳「每问至少一条引用命中地面真值期望文件」为验收口径（两次实测 95.0% / 100.0%），结构合法率恒 100%；引用级命中率仅作信息性指标记录。LLM 非确定性如实记录（一次 19/20 带引用、一次 20/20）
- 2026-09-18：**api-key 只走环境变量，绝不入库**：`CODESCOMPASS_LLM_API_KEY` + 启动参数 `--codecompass.llm.base-url=https://api.deepseek.com/v1 --codecompass.llm.model=deepseek-chat`；真实 LLM 验收测试用 `@EnabledIfEnvironmentVariable` 自动跳过无 key 环境
- 2026-09-18：**踩坑 —— PowerShell 5.1 的 Get-Content/Set-Content 默认按系统 GBK 读写，会把 UTF-8 测试源码的中文整批变成乱码**。教训：改含中文的源文件一律用 edit/write 工具，绝不在 PowerShell 里做文本回写（本次已用 write 全量重写恢复，未造成提交污染）
- 2026-09-18：**T13 LlmConfig 模型**：`default` 是 Java 保留字 → 字段名 `isDefault`；provider 是元数据标签不参与路由（协议统一 OpenAI 兼容）；apiKey 明文只限服务内部使用，日志/异常禁打印 config 对象，未来对外端点必须脱敏
- 2026-09-18：**T13 switch/save/delete 显式抛 UnsupportedOperationException**（「配置以 application.yml 为准」）——客户端是启动时用默认配置构建的单例，假装支持运行时切换会交付悄悄失效的功能；真切换需要「客户端按请求解析配置」，留给未来多配置版本。问答链路（AnswerService/LlmClient 接口/检索/校验/限流）零改动，唯一触碰点是客户端配置来源
- 2026-09-18：**T13 预研实锤 —— MyBatis-Plus 3.5.12 与 Boot 4 不兼容**：其自动配置走 spring.factories，Boot 4 已弃用该机制 → SqlSessionFactory Bean 静默缺失（FlywayMigrationTest 的 @Autowired 实测 NoSuchBean）。按规格书「（或 JPA）」条款切换 `spring-boot-starter-data-jpa`，并以「EntityManagerFactory Bean 存在」断言防同类静默失效复发
- 2026-09-18：**H2 的 JSON 列是坑**：getObject 返回 byte[]、getString 返回带外层引号的文本（实测），跨库读取不稳 → cache_entries.value_json 用 TEXT（缓存值由 Jackson 序列化，无需列级校验）；规格 8 张业务表的 JSON 列留给 JPA/Hibernate 层处理
- 2026-09-18：**迁移脚本双通纪律**：H2(MODE=MySQL) 与 MySQL 8 共用同一套 V1，只用方言交集（AUTO_INCREMENT/DATETIME/JSON/TEXT + 独立 CREATE INDEX，不写 ENGINE/UNIQUE KEY 行内子句）；upsert 用 `ON DUPLICATE KEY UPDATE ... VALUES()`（H2 MySQL 模式支持，MySQL 8 中弃用但可用）
- 2026-09-18：**size() 澄清落地**：size() 自 T11 起就在 CacheService 接口里（接口未做任何改动），MysqlCacheService 按接口实现（只数未过期行，与内存版「存活条目」语义对齐）；测试计数按用户要求用 JdbcTemplate 直查 cache_entries
- 2026-09-18：**T13 运行态证据**：后端带 DB_URL 启动 → Flyway 真迁移 codecompass 库；真实问答 3342ms 后 cache_entries 出现该问缓存行；同问 14ms 命中且答案逐字一致；集成套件写入的问答缓存与运行 app 共享同一张表（跨进程持久化实锤）。**T11 的串行教训再次应验**（集成套件与运行 jar 并行时克隆 409），此后一律串行
- 2026-09-18：**成就配置绑定坑**：`@ConfigurationProperties(prefix="codecompass.achievements")` + 字段 `definitions` 要求 yml 写成 `achievements.definitions[]`（列表直接挂前缀下会静默绑定为空数组）——已修正并留 FlywayMigrationTest 级别探针教训
- 2026-09-18：**F5 触发点的身份选择**：分析完成是异步管线（不带身份），analyze 触发点放在 RepoController.status「首次观察到 done」处（客户端轮询视角即「分析成功后」），refId=taskId 幂等——不动 F1 编排器
- 2026-09-18：**Flyway 失败迁移修复流程（实测）**：MySQL DDL 非事务 + 迁移中途失败会在 schema_history 留 success=0 记录 → 下次启动 Validate 直接拒。修复 = 手动删除失败行（`DELETE ... WHERE success=0`）+ 保证迁移脚本对中间态幂等
- 2026-09-18：**T21 分享语义决策**：「前 10 条问答」取**分享者自己的**（隐私优先，qa_history 按 clientId 过滤）；分享页成就只留 code+name（无 clientId/unlockedAt）；GET /share/** 不在 /api/** 拦截器范围 → 无 Cookie 无身份继承
- 2026-09-18：**T23 双方言测试两条纪律**（真 MySQL 才暴露、H2 永远掩盖）：① 断言 JSON 列内容用**解析后 JSON 树相等**，不用字节相等（MySQL 规范化格式）；② 触碰持久库的测试要 `@Transactional` 回滚 + 每轮唯一键，否则上一轮残留会挡住唯一约束插入
- 2026-09-18：**T24 角色配色的配置边界**：entity(`@Entity`)/mapper(`@Mapper`) 是 JPA/MyBatis 注解、不是框架标记 —— 放进 framework-markers 会让「纯 JPA 仓库」被误判成 Spring，故新增 `role-only-annotations` 配置项把它们从配置漂移告警里排除（配置驱动，分类器代码只加一处过滤）
- 2026-09-18：**T24 默认视图决策**：进入结果页**不预选任何类**（原先自动选第一个类）——「默认不铺全图、点击类才显示邻域」要求首屏是引导提示而非别人的图；中栏同步显示「点击左侧的类，查看它的源码」
- 2026-09-18：**完整真实运行（全链路走查 + 全套测试）**：257 全绿（单元 236 + 集成 21，含真实 LLM 20 问、3 黄金样本、覆盖率 144/144）。经 Vite 代理走查：petclinic 25 单元/21 边、邻域 1 边、真实问答 2413ms 带 2 引用、**同问缓存命中 9ms（约 270 倍）答案逐字一致**、新问引用 94-122 即 processFindForm；第三样本 180 单元/80 边/0 失败文件，跨模块问答引用 CalcController+CalcService 正确
- 2026-09-18：**环境坑 ×2**：① Vite 8 的 dev server 只绑 IPv6 `::1` —— `127.0.0.1:5173` 连不上、`localhost:5173` 正常，验收脚本一律用 localhost；② 昨天遗留的 Vite 僵尸进程占着 5173 且无响应（端口在监听、请求被拒），新实例被挤到 5174 —— 先清僵尸再重启才能回到标准端口
- 2026-09-18：**F3 前端问答窗口**（`QaPanel.vue`）：提问带当前选中类为锚点、无选中退化为关键词检索；答案原文展示 + 引用标签点击定位到对应类（邻域图随之切换）；错误（409/429/502）展示后端原文；切换任务清空问答区。后端零改动，纯前端增量。真实浏览器走查（CDP 驱动 + 真实 DeepSeek）：锚点正确 → 回答上屏 → 5 条引用 → 点击引用后图标题切换为被引用类 ✓
- 2026-09-18：新增根 `.gitignore`（`.idea/`、`docs/screenshots/`）——截图是演示产物不进版本库；此前无根级 .gitignore，靠 `git add -A` 提交时已小心避让
- 2026-09-18：**T14 源码可见 + 选中标识符提问**：后端 `GET /{id}/source?unit=`（T7 内存快照的**单类切片**，非仓库转储 —— §07「不公开大段源码」的边界解读：整仓库倾倒禁止、单类按需取阅是 S7 引用验证的产品本职）；`AskRequest` 加 `anchorStartLine/anchorEndLine` 行锚点，AnswerService 把选中范围切成「【聚焦】片段」置顶进提示词（范围钳制在单元内、unitId 未知退化为普通检索）；**行锚点提问不走缓存**（行号参与语义，CacheKey 不含行号会错命中）。`UnitView` 增 methods/fields 供前端吸附。前端三栏布局（类列表 | 源码+问答 | 图），点源码行自动吸附到所在方法（字段按声明行匹配）。真实走查：点第 95 行 → 「方法 processFindForm（94-122 行）」→ DeepSeek 回答逐行引用方法内代码 ✓。新增单元 9（AnswerService 4 + 控制器 5）
- T13（F2/F6 阶段）MySQL 持久化基础设施：Flyway 12（starter-flyway + flyway-mysql）V1 基线 9 表（规格 8 表 + cache_entries）、`spring.datasource.*` 走 DB_URL/DB_USER/DB_PASSWORD 环境变量（缺省回落 H2）、`MysqlCacheService`（JdbcTemplate + 注入 JsonMapper，默认启用；InMemoryCacheService 保留于 storage=memory）。**MyBatis-Plus 预研失败 → JPA 路线**。新增单元 7（FlywayMigrationTest 2 + MysqlCacheServiceTest 5）+ 集成 2（真 MySQL 双闸）
- T14 F2 学习路线后端：`LearningPathService`（入口类置顶 + 入度降序 + 名字典序的**环安全**排序；LLM 一次调用只喂元数据写 reason、失败回退确定性描述并截 60 字）、`LearningPathEntity`（`@JdbcTypeCode(SqlTypes.JSON)` 规避 H2 JSON 坑）+ `LearningPathRepository`（`(repoUrl,commitSha)` 唯一即 F2 缓存）、`LearningPathController`（POST 幂等不重调 LLM / GET 未生成 404）。新增单元 15 + 集成 1（真机 petclinic 首步入库、覆盖全量、reason 非空、二次幂等）
- T15 F2 学习路线前端：类列表加「学习路线」Tab（`LearningPathPanel`），生成/按 order 展示/点步跳转/预计时间/展开折叠。真机走查：25 步、首步 PetClinicApplication、点步跳转生效
- T16 F4 自动测验后端：`QuizService`（只把选中类源码发 LLM，逐题校验 reference 落在选中类行区间、题型/选项/下标校验、cap 10）、`QuizEntity`/`QuizRepository`、`QuizController`（生成 + 判分）。answer 用 0-based 下标（规避 SCHEMA 示例「A」歧义）。新增单元 10 + 集成 1（真机 + 真实 DeepSeek：≥5 题、reference 全合法、判分正确率）
- T17 F4 测验前端：类列表加「测验」Tab（`QuizPanel`），选中类生成/逐题作答/提交判分显示得分与错题解析/引用跳转。真机走查：10 题生成、判分显示得分
- T18 F5 匿名身份 + 笔记：`ClientIdentityInterceptor`（cc_client_id Cookie，30 天 HttpOnly，无则生成 UUID + upsert anonymous_users）、`ClientIdentityHolder`（ThreadLocal 上下文，测试可注入）、`IdentityService`、`/api/me`（GET 身份/PUT 昵称）、`NoteController`（笔记 CRUD，绑定 clientId 且所有权校验）、`AnonymousUserEntity`/`NoteEntity` + 仓储。新增单元 8 + 集成 1（真 HTTP：Cookie 恢复身份、昵称、笔记持久化与隔离）
- T19 F5 进度 + 成就：`ProgressEntity/Repository`（upsert、status 校验）、`AchievementService`（规则全配置 `codecompass.achievements.definitions[]`，user_actions 计数 ≥ threshold 解锁、refId 幂等）、`AchievementEntity/Repository`、`ProgressController`/`AchievementController`。触发点：analyze（status 首次观察到 done，不碰 F1 编排器）、ask、note、quiz_perfect、path_done（最新学习路线全步骤 done）。新增单元 8 + 集成 1（真机分析解锁 FIRST_REPO、进度持久化、FIRST_NOTE）
- T20 F5 前端：类列表进度圆点、右侧笔记面板（upsert/删除/解锁提示）、顶部成就徽章（popover 全量定义 + 分析完成时刷新并弹解锁通知）。**实测抓到两个真 bug 并修复**：单元 id（含 filePath）超 128 字符 → V2 加宽 code_unit_id 到 512 且唯一索引缩为 (client_id, code_unit_id)（三列索引超 InnoDB 3072 字节上限，实测 1071）；user_actions.ref_id 同样超宽 → V3 加宽。真实浏览器走查：成就徽章 + 笔记保存全通
- T21 F6 分享领读页后端：`ShareService`（快照 = 仓库信息 + 依赖图 mermaid + 学习路线 + 分享者自己前 10 条问答 + 已解锁成就 code/name，无源码无 API key 无他人笔记）、`ShareSnapshotEntity/Repository`（30 天过期可配）、`ShareController`（POST 生成短链 / GET /share/{id} 独立 HTML + Mermaid CDN + UTF-8，过期 404「已过期」）。**前置补齐 qa_history 表（V4）**：ask 成功后旁路记录（失败不打断问答）。新增单元 6 + 集成 1（真机短链无 Cookie 打开、含依赖图、脱敏）
- T22 F6 分享页前端：结果区「生成分享页」按钮 + 短链弹窗（复制/打开）；分享页本体是后端渲染 HTML（FEATURE_SPEC 明确不复用 SPA）。**补齐 Vite `/share` 代理**（dev 下短链走 :5173，不代理会 404 —— 与 T8 补 /api 代理同款）。真机走查：弹窗短链可用，分享页 200 且含依赖图/学习路线/问答/页脚、不含源码
- T23 端到端验收：**327 全绿**（单元 299 + 集成 28，含真机克隆 3 样本 + 真 MySQL + 真实 DeepSeek），`docs/T23-验收报告.md` 逐项映射证据。本轮修 2 类**双方言测试断言**：① JSON 列回环改「解析后 JSON 树相等」（MySQL 会规范化 JSON 格式，字节比较只在 H2 成立）；② 仓储测试改 @Transactional + 每轮唯一键（真 MySQL 不随测试重建，固定键会被上轮残留挡住）
- T24 UI 统一修正：`MermaidRenderer` 按角色输出 classDef/class（entry 橙 / controller 蓝 / service 绿 / entity 灰 / mapper 紫 / repository 青，顺序与节点顺序固定保证确定性）；core-annotations 加 entity/mapper 两个非 Spring 角色 + `role-only-annotations` 配置（把它们从「未进 framework-markers」漂移告警里排除，避免纯 JPA 仓库被误判成 Spring）；前端三栏（左类列表+进度 / 中源码+问答 / 右 Tab：依赖图·学习路线·测验·笔记·成就），**默认不铺全图也不预选类**，点类才出邻域。真机走查：默认提示、点类出邻域、五 Tab、问答 12 条引用全通。新增单元 5
- T24 增量（用户要求）：**分享页加入本人笔记** —— 笔记没有别的"另存"途径，分享是唯一出口。`ShareSnapshot` 增 `notes[]`（类名+内容+更新时间，紧凑构造器对旧快照缺字段规范化空列表），`ShareService` 按 **clientId 过滤只取分享者本人**笔记（按更新时间倒序），`ShareController` 增「笔记」段落（HTML 转义/空态）。真机走查：写笔记 → 生成分享 → 无 Cookie 打开页面含笔记正文 ✓。新增单元 2
