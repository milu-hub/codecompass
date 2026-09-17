# 进度

## 当前任务
T9 代码片段检索层

## 已完成
计数口径为**本任务新增**，避免后续任务读到过期的累计值。当前合计：**单元 177 + 集成 16 = 193，全绿**。

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
