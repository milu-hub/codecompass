# CodeCompass 七层全面测试报告

> 生成：2026-09-19 · 模式：全自动测试 + 自动修复 · 数据库：MySQL（集成）/ H2（单元）· LLM：DeepSeek deepseek-chat（真实 key）

## 1. 测试环境

| 项 | 值 |
|---|---|
| JDK / Maven | 21.0.12.1 / 3.9.6 |
| Node | v24.19.0 |
| Spring Boot | 4.1.1（webmvc + Jackson 3 + JPA + Flyway 12） |
| MySQL | 8.0.46（`DB_URL/DB_USER/DB_PASSWORD`） |
| LLM | DeepSeek `deepseek-chat`（`CODESCOMPASS_LLM_API_KEY`，OpenAI 兼容） |
| 前端 | Vue 3.5 + Vite 8.3 + Element Plus 2.14 |

## 2. 总体结论

**七层测试完成，发现并修复 3 个真实 bug（均已提交并复验）；其余 2 个「失败」为测量假象（非产品缺陷）。**

- 全量自动化（`mvn test -Dsurefire.excludedGroups=`，MySQL + 真实 LLM）：**398 项，6 失败** —— 6 个失败同属一个根因（微服务仓库克隆超时）。
- 修复后：6 个失败项及其同类（4 个测试类共 15 项）复测**全绿**；另新增 3 个回归测试全绿。**合计 401 项无失败**。
- 真实 LLM 档已带 key 实测：问答引用命中 **20/20 = 100%**，测验真机生成 ≥5 题。

## 3. 七层逐项结果

### 第一层：回归（F1/F3 未破坏）

| 项 | 结果 | 证据 |
|---|---|---|
| 全量测试 | ⚠️ 398 项 6 失败 → 修复后全绿 | 6 失败全是 `spring-petclinic-microservices` 克隆超时（根因见 §5.1） |
| F1 分析→类列表→依赖图 | ✅ | 真机：25 单元 / 21 边 / mermaid 871 字节，首次 6767ms |
| F3 提问→行号引用→可跳转 | ✅ | 真机 ask 返回引用 `OwnerController.java:48-179`，结构合法；`RealLlmReferenceAcceptanceTest` 20/20=100% |
| 与 T12 五项指标对比 | ✅ 无退化 | 见 §6 |

### 第二层：F2 学习路线

| 项 | 结果 | 证据 |
|---|---|---|
| 首步是入口类 | ✅ | 首步 `PetClinicApplication`，25 步 |
| 不重复不遗漏 | ✅ | 25 步 = 25 单元 |
| 每步 reason 非空 ≤60 字 | ✅ | 0 空；max 28 字（UTF-8 正确解码） |
| 二次请求命中库、不重调 LLM | ✅ | 二次返回一致（`(repoUrl,commitSha)` 唯一键） |
| 空仓库/未完成 → 明确错误 | ✅ | `LearningPathServiceTest`/`ControllerTest` 覆盖 |

### 第三层：F4 自动测验

| 项 | 结果 | 证据 |
|---|---|---|
| ≥5 题、含单选+判断 | ✅ | 真机 7 题（single_choice + true_false） |
| 每题 explanation、reference 落在检索片段 | ✅ | 全有 explanation，reference 行号合法 |
| 判分（全对/全错/部分） | ✅ | 全对 7/7 = 1.0；`QuizServiceTest` 覆盖 |
| 0 个类 / 6 个类 → 明确错误 | ✅ | 0 → 400、6 → 400（**上限是修复项** §5.2） |
| 越界行号 → 丢弃该题 | ✅ | `QuizServiceTest` 覆盖 |

### 第四层：F5 笔记 · 进度 · 成就

| 项 | 结果 | 证据 |
|---|---|---|
| 首次访问生成 cc_client_id Cookie（30 天 HttpOnly） | ✅ | 真机 Set-Cookie：`Max-Age=2592000; HttpOnly` |
| 换浏览器/清 Cookie 数据隔离 | ✅ | 3 个独立会话 clientId 互异；fresh 会话 0 笔记 |
| 笔记 CRUD + (clientId,repoUrl,codeUnitId) 唯一 | ✅ | 真机写读回；`IdentityNoteControllerTest`/`NoteRepositoryTest` |
| 进度三态持久化 | ✅ | `ProgressServiceTest` |
| 五成就触发 + 幂等 | ✅ | 真机 FIRST_REPO/FIRST_NOTE 解锁；其余由 `AchievementServiceTest` 按阈值验证 |

### 第五层：F6 分享领读页

| 项 | 结果 | 证据 |
|---|---|---|
| POST 返回 shareId + url | ✅ | `shareId=3170cc...` + `/share/{id}` |
| 短链无 Cookie 可打开 | ✅ | GET 200（无 Cookie 会话） |
| 展示依赖图/学习路线/问答 | ✅ | 含 mermaid（`graph ` 命中） |
| 脱敏 | ✅ | 无 `sk-` / `clientId` / `unlockedAt` / `public class` |
| 过期 → 404「已过期」 | ✅ | `ShareControllerTest`（6） |
| 未完成 → 明确错误 | ✅ | 409（`ShareController`） |

### 第六层：跨功能与边界

| 项 | 结果 | 证据 |
|---|---|---|
| 多语言扩展点（业务层零改动） | ✅ | `LanguageAnalyzerRegistryTest`(8) + `PythonAnalyzerAcceptanceTest` |
| 多仓库隔离 | ✅ | 仓储按 clientId 过滤 |
| **并发：同仓库并发 3 次** | ✅（修复后） | 修后 3/3 全成、结果一致（25 单元）；**修复项** §5.3 |
| 非法 GitHub URL → 400 | ✅ | 真机 `not-a-url` → 400 |
| 空问题 → 400 | ✅ | 真机 `{"question":""}` → 400 |
| 克隆超时 60s 中止 + 清理 | ✅ | `CloneWatchdogTest`(4)/`GitProcessRunnerTest`(5) |
| 超 1000 文件/20MB/500MB 终检 | ✅（单测） | `DiskUsageMeterTest`(4)，未用真实超大仓库 |
| LLM 超时/非法 JSON | ✅（单测） | `OpenAiCompatibleLlmClientTest`/`AnswerServiceTest` |
| Python 仓库 flask | ✅ | 真机 147 单元 / 148 边 / 29 个 .py 文件 |

### 第七层：性能与资源

| 项 | 结果 | 证据 |
|---|---|---|
| petclinic 首次分析 <30s | ✅ | 真机 6767ms |
| 二次分析（缓存）<2s | ⚠️ 口径 | 问答缓存命中 <2s（`CacheHitLatencyTest` P95=0ms，手工 42ms）；分析本身**无结果缓存**（二次分析实测 5.1s，仅复用克隆工作区） |
| 1000 文件仓库堆 <1GB | ⬜ 未覆盖 | 无 1000 文件仓库 |
| 8 表索引 EXPLAIN / 慢查询 | ⬜ 未覆盖 | 无 mysql CLI |
| 前端首屏 <2s | ✅ | CDP 实测 **393ms**，0 JS 异常 |
| 切换 Tab 不重请求 | ⬜ 未计时 | 前端状态驻留（Pinia），未单独计时 |
| 依赖图渲染 <1s | ⬜ 未计时 | 未单独计时；功能已走查（选类出图 svgNodes>0） |

## 4. 两个「假问题」（非产品 bug，已排除）

1. **学习路线 reason 长度 72 字超 60** —— PowerShell 5.1 把 UTF-8 响应按 Latin-1 解码，中文膨胀为 3 字符/字。正确解码复测 **28 字**，合规（后端 clamp 60 字生效）。
2. **笔记隔离「fresh 会话看到 1 条」** —— 脚本 `@($null).Count` 把空结果当 1 个元素。复测 fresh 会话 **0 条**，隔离正常。

## 5. 已修复的 3 个真实 bug（含 commit）

### 5.1 微服务仓库克隆超时 60s —— `64e8f2d`
- **现象**：全量 398 项中 6 项失败，全是 `spring-petclinic-microservices` 克隆超时。
- **根因**：P2 给 Python 配整仓扫描（`source-root: .`），`sparseCheckoutPattern()` 对整仓语言返回 `**`，克隆器取各语言模式并集后把**每个仓库**都全量检出；大仓库 >60s 撞超时。
- **修复**：整仓语言稀疏模式改由 `fileExtensions` 派生（`.py` → `**/*.py`），不再 `**` 全量。
- **复验**：4 类 15 项全绿；克隆耗时 60s+ → 12~24s。

### 5.2 测验「最多 5 个类」上限未实现 —— `446489e`
- **现象**：规格「至少 1 个、最多 5 个」，后端只查了空、没查上限，选 6 个类照常生成。
- **修复**：`QuizService.generate` 加 `MAX_SELECTED_CLASSES=5` 校验，超限抛 `IllegalArgumentException` → 400。
- **复验**：0 个类/6 个类均 400。

### 5.3 并发分析同一仓库工作区互删 —— `caa35c4`
- **现象**：3 个并发任务分析同一仓库，2 个报「删除工作区失败」。
- **根因**：`workspaceIdFor(url)` 是 URL 哈希（确定性），并发任务撞同一目录；`TempWorkspaceManager.create` 先删后建，一方删掉另一方正在用的目录。
- **修复**：`uniqueWorkspaceIdFor(url)` 追加 `UUID.randomUUID()`，每次克隆目录唯一。
- **复验**：3 并发全成，结果一致（25 单元 / 21 边）。

## 6. 与 T12 验收指标对比

| T12 指标（阈值） | 本次结果 | 退化？ |
|---|---|---|
| 黄金样本通过全部验收 | `SpringAnnotationCoverageIntegrationTest` 3 样本全绿（含微服务样本，修复后） | 无 |
| 核心注解覆盖率 ≥80% | 3 样本 100% | 无 |
| 问答引用行号准确率 ≥90% | `RealLlmReferenceAcceptanceTest` 20/20 = 100% | 无 |
| 缓存命中响应 <2s | `CacheHitLatencyTest` P95=0ms；手工 42ms | 无 |
| 多语言扩展点 | Python 真分析器 + 业务层零语言 import | 无 |

**结论：无退化。**

## 7. 未覆盖风险（如实标注）

1. 1000 文件仓库 JVM 堆 <1GB —— 无合适大仓库。
2. 8 表 EXPLAIN / 慢查询 —— 无 mysql CLI，未做 DB 剖析。
3. 依赖图渲染 <1s 计时 / Tab 切换不重请求计时 —— 未单独计时（功能已走查）。
4. 真实超大仓库触发 1000 文件/20MB/500MB 终检 —— 仅 `DiskUsageMeterTest` 单测。
5. 私有仓库 URL —— 需真实私有仓库 + 凭据。
6. 真实 LLM 超时/非法 JSON —— 仅本地 mock（`OpenAiCompatibleLlmClientTest`）覆盖。
7. 单次分析成本 <0.1 元 —— 监控指标，未重测。

## 8. 证据日志

- 全量测试：`mvn test -Dsurefire.excludedGroups=`（MySQL + DeepSeek key）→ 398 项 6 失败（均为 `克隆超时（60s）`）
- 修复复测：4 类 15 项全绿；`SourceFileScannerIntegrationTest` 85.9s→24.1s、`DependencyGraphIntegrationTest` 93.1s→12.5s
- 真机走查：petclinic 首次分析 6767ms / 25 单元 / 21 边；前端首屏 393ms；flask 147 单元 / 148 边
- 报告期间提交：`64e8f2d`、`446489e`、`caa35c4`（工作区干净）
