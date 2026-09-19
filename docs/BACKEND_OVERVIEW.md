# CodeCompass 后端核心功能实现说明

> 范围：只讲后端功能逻辑（`backend/src/main/java/com/codecompass/`），不讲前端与页面显示。
> 读法：每个功能一行，说清「做什么 / 怎么实现 / 用了什么技术」；关键类名保留，不展开代码。
> 实现摘要的权威来源是 `docs/PROGRESS.md`。

## 功能一览

| # | 功能 | 做什么 | 怎么实现 | 用了什么技术 |
|---|---|---|---|---|
| 1 | **仓库浅克隆** | 把 GitHub 仓库按需拉到临时区，挡住超大/超时/恶意仓库 | 4 条 git 命令：浅克隆 + blob 过滤 + `--no-checkout` → `sparse-checkout init` → `set 模式` → `checkout`；看门狗每 2s 用 NIO 量落盘，超 1.2× 阈值即杀进程树；克隆后三项终检（单文件 20MB / 总量 500MB / 1000 文件）；删除前先清 Windows 只读位 | git CLI、`ProcessHandle.descendants()`、NIO；`GitRepositoryCloner`。**60s 是整个流程的总预算，不是每条命令** |
| 2 | **源码扫描** | 扫出待解析文件清单 + 包名/单元名/language | **不用 glob** —— 实测 Java 的 `**/src/main/java/**` 匹配不了零层前缀，与 git 方言冲突，会让单模块仓库静默返回 0 文件；改用**源码根片段序列匹配**（任意深度命中、取最后一次出现）；包名纯路径推导、不读文件；目录排除按语言隔离 | `Files.walkFileTree`；`SourceFileScanner` + `ScanProperties`（配置驱动 `scan.sources[]`） |
| 3 | **LanguageAnalyzer 接口** | 把「某语言怎么解析」收敛成一个接口，业务层不认识任何实现 | 接口只做 parse → `AnalyzeResult`；`LanguageAnalyzerRegistry` 按 language 字符串 Map 查表、无任何语言分支；`kind` 用开放字符串而非 enum | `ObjectProvider` 收集实现（零实现时也能启动）；单测守卫「业务层不得 import 语言实现」 |
| 4 | **JavaSpringAnalyzer** | 解析 .java → 类/方法/字段/注解 + 依赖边 | 两遍处理：先建符号表，再解析引用成边；名字解析顺序为**显式 import → 同包 → 通配 → 同文件**；同 FQN 先到先得；边去重、丢自环、按 id 排序、仓外目标丢弃 | JavaParser 3.28.2（**语法级，不做符号求解**）；必须显式设 LanguageLevel（默认 JAVA_11 会解析不了 record/sealed） |
| 5 | **Spring 注解识别** | 由注解判定类的角色（entry/controller/service…） | **零注解字面量**：注解→角色映射与框架标记全在 yml，`CoreAnnotationClassifier` 读配置判定；「配了 core 却没进 framework」的漂移在启动时告警 | `@ConfigurationProperties`；覆盖率用独立脚本生成的黄金样本 YAML 度量 |
| 6 | **依赖图构建** | 分析结果 → 可渲染的图 + 邻域视图 | 节点=单元（附角色）、边=依赖边；**孤立单元不画进图、单独计数报告**；渲染成 `n0["类名"]` + 按角色的 `classDef/class`；邻域=从某单元 BFS 取 N 跳 | 纯内存 `DependencyGraphBuilder` + `MermaidRenderer`（输出 Mermaid 文本） |
| 7 | **REST API** | 提交分析 / 轮询状态 / 取图 | POST 只校验 URL 就**异步入池**、立即 201 返 taskId（克隆最长 60s，同步会耗尽 Tomcat 线程）；状态机 pending→running→done/failed，快照用 CAS **原子发布**；未知 404 / 未完成 409 / 非法 URL 400 | `AnalysisOrchestrator` + 专用线程池 + `AnalysisTaskStore`（CAS） |
| 8 | **代码片段检索** | 给一个问题（可带锚点类）挑出最相关的代码片段喂给 LLM | 纯词法检索：camelCase 拆分 + 小写 token，与单元名/方法名/字段名/注解/包名求交；**锚点 +10、一跳出边 +3**；快照按 1-based 闭区间切片、越界截断；`maxSnippets` 与权重全走配置 | 内存索引，从任务快照取数（**不重新克隆**）；`CodeRetriever` / `LexicalCodeRetriever`。**行号只来自解析层** |
| 9 | **LLM 问答** | 基于检索片段回答，并给出可点击的真实行号引用 | 提示词**每行前缀真实行号**；要求只依据片段、只输出一个 JSON（answer + references）；**引用逐条严格校验**（file 完全相等 + language 相同 + 行区间落在某片段内），不匹配的丢弃并**把丢弃清单当反馈重试**（`maxAttempts` 默认 3 封顶）；传输类错误不进重试、直接上抛 → 502 | OpenAI 兼容协议（`/chat/completions` + Bearer），不引 provider SDK；宽松 `JsonMapper`（关 trailing tokens 容忍围栏/杂字符）；Boot 4.1 无 `RestClient.Builder` Bean，自行构造 |
| 10 | **缓存与限流** | 同问不重复烧 token；每人每天有 token 上限 | 缓存 key = **commitSha + 锚点文件 + 问题 hash + model** 四要素，`\u0000` 分隔后 SHA-256 落单列；TTL 到期惰性删除；**commitSha 未知就不查不写**（宁可 miss 不可错命中）；限流窗口 = `clientKey｜自然日`，超限 429。**顺序即正确性**：缓存 → 检索 → 限流 → LLM | MySQL `cache_entries`（TEXT 存值 + 过期列）；身份 `X-Client-Id → X-Forwarded-For 首跳 → remoteAddr`。行锚点提问**不走缓存**（行号参与语义） |
| 11 | **F2 学习路线** | 给一份「按什么顺序读」的路线 + 每步理由与预计时间 | **顺序由确定性算法定**：入口类置顶 → 入度降序 → 同层名字字典序（环安全）；**LLM 只写每步 reason**（只喂元数据、不喂源码、截 60 字），失败或未配置回退确定性描述；预计时间由方法数派生；按 `(repoUrl, commitSha)` 唯一落库，POST 幂等不重调 LLM | 环安全拓扑排序 + `learning_paths` 表（JSON 列）+ LLM 单次调用；`LearningPathService` |
| 12 | **F4 自动测验** | 就选中的类出题并判分 | 只把**选中类的源码**发 LLM，要求返回题目（单选/判断 + 选项 + answer 下标 + explanation + reference）；逐题校验——题型/选项/下标合法、**reference 必须落在选中类的真实行区间**，坏的丢题；题目数 `cap 10`；判分是确定性比对并算正确率 | LLM 出题 + 确定性校验与判分；`QuizService`。answer 用 0-based 下标（规避 SCHEMA 示例「A」的歧义） |
| 13 | **F5 笔记/进度/成就** | 匿名身份下记笔记、标进度、解锁成就 | 首次访问下发 `cc_client_id` Cookie（30 天 HttpOnly），无则生成 UUID 并 upsert 匿名用户；笔记绑定 `(clientId, repoUrl, codeUnitId)` 唯一、重复写覆盖、**他人结构性不可见**；进度三态 unread/reading/done 持久化；成就规则**全配置**，靠 `user_actions` 计数 ≥ 阈值解锁、refId 幂等 | `ClientIdentityInterceptor` + `ThreadLocal` 身份上下文；触发点旁路（analyze 在「首次观察到 done」、ask / note / quiz_perfect / path_done），记录失败不打断主流程 |
| 14 | **F6 分享页** | 把一次分析成果导出成无 Cookie 可打开的只读静态页 | 快照 = 仓库信息 + 依赖图 mermaid + 学习路线 + **分享者本人**前 10 条问答 + 已解锁成就 code/name + **本人**笔记；**脱敏**：不含源码、不含 API key、不含 clientId / unlockedAt、不含他人笔记；后端拼独立 HTML（不复用 SPA），内嵌 Mermaid CDN；30 天过期，过期/不存在 404 | `share_snapshots` 表（JSON 快照）+ 手写 HTML 渲染；`GET /share/**` 不在 `/api/**` 拦截器范围 → 无身份继承 |

## 关键技术决策

1. **多语言 = 接口 + 注册表 + 一份配置**：加一门语言只追加 `scan.sources[]` 与一个分析器实现，克隆、扫描、构图、检索全都不动。
2. **行号 1-based 闭区间，且在 record 构造器上守卫**；写成 0-based 会让引用整体偏一行，而编译、单测、演示都看不出来。**所有引用行号只来自解析层与检索层，模型不许编造。**
3. **只用语法、不做符号求解**；仓外依赖不入图 —— 宁缺一条边，也不造幽灵节点。
4. **克隆先按需、再终检、失败必清理**（含清 Windows 只读位，否则临时目录永久残留）；限制一律按**落盘量**计。
5. **能用配置表达的绝不写进代码**：扫描范围、注解角色、克隆阈值、检索权重、成就规则全部走 yml。
6. **问导管道的顺序本身就是正确性**：缓存 → 检索 → 限流 → LLM，任一颠倒都会错杀省钱路径或漏杀超限。
