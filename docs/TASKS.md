# CodeCompass 任务卡

每个任务粒度 0.5～2 天。每完成一个任务就 commit，commit message 格式：`T{编号}: {任务名}`。

## T0 项目骨架

**输出**：Java 21 + Spring Boot 4.x 后端工程；Vue3 + Vite + Pinia + Element Plus 前端工程

**验收**：后端能启动；前端能启动；前后端通过健康检查接口连通

**禁止**：不引入 MySQL / MyBatis / JPA；不写登录 / JWT / Spring Security；不实现业务逻辑

## T1 GitHub 仓库浅克隆服务

**输入**：GitHub 公开仓库 URL
**输出**：`CloneResult { localPath, success, errorMessage }`

**要求**：ProcessBuilder 调 git；稀疏浅克隆，路径模式从配置读取；禁用 submodule；60 秒超时（**整个流程的总预算**）；目标目录已存在先删除（校验路径）；分析完删除临时目录（校验路径）

**落盘看门狗**：克隆与 sparse-checkout 全程启动落盘监控。**用进程内 NIO 遍历测量，不得调用 `du` 等外部命令**（开发机是 Windows，`du` 非系统自带、且本机只有 IDE 捆绑的 BusyBox 版，会让看门狗静默失效）。每 2 秒测一次临时目录总落盘量，**超过 600 MiB** 即中止整个任务并清理，返回错误「仓库过大或超过资源限制」。看门狗中止与超时中止**必须走同一条 kill 路径**（先 `ProcessHandle.descendants()` 再父进程），不得写两套。

**终检（克隆成功后）**：三项限制，超限即拒绝并**指明是哪一项超限**

**口径定义（重要）**：因为用 `--filter=blob:none` + 稀疏检出，仓库的"真实"大小与文件总数我们**永远拿不到**（除非全量下载或调 GitHub API，二者都被禁止）。所以三项限制一律按**落盘量**计：
- 文件数 = 稀疏检出后磁盘上的文件数（含 `pom.xml`）
- 单文件大小 = 单个落盘文件的字节数
- 仓库总大小 = `.git` 目录 + 已检出工作区 的字节和

**上限**：文件数 ≤ 1000、单文件 ≤ 20MB、仓库总大小 ≤ 500MB。**全部阈值从配置读取**，不得硬编码在类里。

**验收**：spring-petclinic 真实克隆测试（@Tag("integration")）；成功后 localPath 存在 pom.xml；超时有明确错误信息；看门狗能中止膨胀克隆并返回「仓库过大或超过资源限制」；终检拒绝超限仓库且错误信息指明超限项

**禁止**：不逐文件调 GitHub API；不执行仓库内脚本；不用 --recurse-submodules；**不用 `du` / `wmic` 等外部命令测量磁盘用量**；**不把看门狗与超时写成两套 kill 逻辑**

## T2 源码文件扫描器

**输入**：T1 的 localPath
**输出**：`List<CodeUnitFileInfo>`，含相对路径、包名、类名、language 字段

**要求**：路径模式从配置读取；只扫 `**/src/main/java` 下的 .java；忽略 package-info.java / module-info.java；支持多模块

**配置形态（`codecompass.scan.sources[]`，一份语言配置）**：
```yaml
codecompass:
  scan:
    sources:
      - language: java
        source-root: 'src/main/java'      # 路径片段序列，任意深度命中，同时作为包名根
        file-extensions: ['.java']
        excluded-file-names: ['package-info.java', 'module-info.java']
```
新增语言只追加一项，T1/T2 代码都不改。T1 的 sparse-checkout 模式由 `source-root` 派生为 `**/<source-root>/**`，避免与扫描范围漂移。

**关键决策（实测驱动）**：**扫描不使用 glob**。实测 Java `PathMatcher` 的 `glob:**/src/main/java/**` 对 `src/main/java/org/foo/Bar.java` 返回 **false**（Java 的 `**/` 不匹配零层目录），而 git 用同一模式确实检出了该文件 —— 两个方言冲突。若直接复用，单模块仓库（黄金样本 petclinic）会静默返回 0 个文件。故改用**源码根片段序列匹配**，顺带得到包名。

**其他口径**：`relativePath` 相对仓库根、统一 `/` 分隔；`packageName` 默认包取 `""`（不是 null）；结果按 `relativePath` 排序保证确定性；多模块下同名类不去重。

> 仓库限制（文件数 / 单文件 / 总大小）的校验在 **T1 终检**完成，T2 不重复校验。

**验收**：文件数与实际一致；每条记录 language = "java"；多模块项目每个模块都扫到

**禁止**：不写死 Java 特有路径；不在业务层判断文件语言；**不要用 `git ls-files` 枚举待解析文件**（稀疏检出的索引里含大量 `skip-worktree` 条目，实测列出 132 个而磁盘只有 31 个，会把不存在的文件送进解析器）；**不读文件内容推导包名**（会把 Java 语法塞进扫描器，T3 分层当场破功）；**不使用 glob 匹配**（见上）

## T3 LanguageAnalyzer 接口定义

**输出**：`LanguageAnalyzer` 接口 + 语言中立 DTO

**要求**：方法名语言中立；DTO 含 `CodeUnitInfo`、`MethodInfo`、`DependencyEdge`；每条记录带 `language`、`framework`

**验收**：接口不出现 Java 特有命名；DTO 能容纳 Java 解析结果；预留扩展点

**禁止**：
- 不出现 `ClassInfo`、`JavaClass` 等命名；不实现具体分析器
- 不要 import 任何 Jackson 类型（`tools.jackson.*` / `com.fasterxml.jackson.*`）：语言中立的核心模型不依赖序列化框架
- 如确有自定义序列化需求，不要改核心 DTO，在 web 层用 MixIn 或专门的 web DTO 处理

## T4 JavaSpringAnalyzer：JavaParser 类信息提取

**输入**：T2 的 `List<CodeUnitFileInfo>`
**输出**：`AnalyzeResult`，含 codeUnits（内含 annotations、fields）、methods、dependencies、failedFiles

**要求**：JavaParser 解析；遍历文件解析 CompilationUnit；提取包名 / 类名 / 类注解 / 字段 / 方法 / import；失败文件记录并跳过；语法级解析

**验收**：spring-petclinic 上提取出所有类；language = "java"，framework = "spring"；失败文件有日志

**禁止**：不用 Spoon；不实现 Java 以外语言；不在业务层写 Java 特有逻辑

## T5 Spring 注解识别

**核心注解**：`@RestController`、`@Controller`、`@Service`、`@Repository`、`@Component`、`@Autowired`、`@Bean`、`@SpringBootApplication`

**验收**：spring-petclinic 上 Controller 数量 ≥ 3；OwnerController 被识别为 @Controller；覆盖率 ≥ 80%

**禁止**：不识别 Java 以外注解；不在业务层硬编码注解列表（应可配置）

## T6 依赖图构建

**输出**：`List<DependencyEdge>` + Mermaid 可渲染图数据

**要求**：类级依赖图；来源 import / 字段类型 / 注解引用；输出语言中立

**验收**：spring-petclinic 上存在 OwnerController -> OwnerRepository 的边；图数据能渲染为 Mermaid

**禁止**：不做方法级调用图；不引入图数据库

## T7 图数据 REST API

**接口**：
- `POST /api/repos` 提交仓库 URL，返回分析任务 ID
- `GET /api/repos/{id}/graph` 获取依赖图 JSON
- `GET /api/repos/{id}/status` 查询分析状态

**验收**：返回结构稳定；`GET /api/repos/{id}/graph` 返回 `AnalyzeResult`（含 codeUnits、dependencies），前端只取需要的字段；状态区分 pending / running / done / failed；响应带 language 字段

**禁止**：
- 不做登录鉴权；不引入 MySQL
- 不要手动 `new ObjectMapper()`；使用自动配置的 `JsonMapper` Bean，import `tools.jackson.databind.json.JsonMapper`
- 不要引入 `jackson-datatype-jsr310` 等日期时间模块，也不要注册任何 Module（`JavaTimeModule` 类在 Jackson 3 中已不存在）

## T8 Vue 简单类列表 + Mermaid 图

**要求**：输入仓库 URL 输入框；类列表展示；点击类显示依赖图（Mermaid）；分析进度提示（可轮询）

**验收**：spring-petclinic 正常展示；Mermaid 能渲染；类列表与后端一致

**禁止**：不做登录页；不做学习路线 / 测验 / 成就 / 分享页

## T9 代码片段检索层

**输入**：用户问题 + 仓库分析结果
**输出**：`List<RetrievedSnippet>`，含 file、language、startLine、endLine、content

**要求**：关键词 + 类名 + 方法名检索；接口语言中立；不一定用向量数据库

**验收**：给定类名能检索到文件；给定方法名能检索到行范围；每条记录带 language

**禁止**：不引入向量数据库；不写 LLM 调用代码

## T10 LLM 问答接口

**输出**：`{ answer, references: [{ file, language, startLine, endLine }] }`

**要求**：行号来自检索层；文件路径 + 行号 + 代码片段一起发给 LLM；要求返回 JSON；引用逐条比对，不匹配丢弃重试

**验收**：选中 spring-petclinic 代码提问，返回可跳转行号引用；准确率 ≥ 90%（抽样 20 条）

**禁止**：
- 不把整个仓库塞给 LLM；不允许 LLM 编行号；不在业务层写 Java 特有逻辑
- 不要 import `com.fasterxml.jackson.databind.ObjectMapper`；使用 `tools.jackson.databind.json.JsonMapper`
- 异常捕获用 `JacksonException`，不要用 `JsonProcessingException`
- 不要引入 `jackson-datatype-jsr310` 等日期时间模块，也不要注册任何 Module（`JavaTimeModule` 类在 Jackson 3 中已不存在）
- 解析 LLM 输出不要改全局 `JsonMapper` Bean：Jackson 3 的 mapper 不可变，用 `jsonMapper.rebuild()` 派生 T10 专用实例，按需放开 `FAIL_ON_TRAILING_TOKENS`

## T11 内存缓存 + 限流

**缓存**：应用内缓存（Caffeine 或 ConcurrentHashMap + TTL），key 为 repo commit SHA + 文件路径 + 问题 hash + 模型版本
**限流**：按匿名 session / IP 的内存计数器，每日 token 上限
**接口约束**：定义 `CacheService` 与 `RateLimiter` 接口，MVP 只提供内存实现，为后续可替换留口

**接口设计**（业务层只依赖接口，不直接依赖内存实现类）：
```java
public interface CacheService {
    <T> Optional<T> get(String key, Class<T> type);
    void put(String key, Object value, Duration ttl);
    void evict(String key);
}

public interface RateLimiter {
    boolean allow(String key);
    long remaining(String key);
}
```
MVP 实现：`InMemoryCacheService`、`InMemoryRateLimiter`；后续可加 `RedisCacheService`、`RedisRateLimiter`，业务代码不改。

**验收**：相同仓库二次分析走缓存，响应 < 2 秒（P95）；超限返回明确错误；缓存有 TTL 或容量上限；应用重启后缓存清空属预期行为

**禁止**：不引入 Redis / MySQL；不缓存完整仓库代码；不在业务层直接依赖内存实现类，必须通过接口调用

## T12 端到端验收

**验收项**：
- 黄金样本通过全部验收标准
- 核心注解类识别覆盖率 ≥ 80%
- 问答引用行号准确率 ≥ 90%
- 缓存命中响应 < 2 秒
- 多语言扩展点检查：新增一个 Python 的 stub `LanguageAnalyzer` 实现，业务层代码不需要修改

**禁止**：不为通过验收写死数据
