# CodeCompass 任务卡

每个任务粒度 0.5～2 天。每完成一个任务就 commit，commit message 格式：`T{编号}: {任务名}`。

## T0 项目骨架

**输出**：Java 21 + Spring Boot 4.x 后端工程；Vue3 + Vite + Pinia + Element Plus 前端工程

**验收**：后端能启动；前端能启动；前后端通过健康检查接口连通

**禁止**：不引入 MySQL / MyBatis / JPA；不写登录 / JWT / Spring Security；不实现业务逻辑

## T1 GitHub 仓库浅克隆服务

**输入**：GitHub 公开仓库 URL
**输出**：`CloneResult { localPath, success, errorMessage }`

**要求**：ProcessBuilder 调 git；稀疏浅克隆，路径模式从配置读取；禁用 submodule；60 秒超时；目标目录已存在先删除（校验路径）；分析完删除临时目录（校验路径）

**验收**：spring-petclinic 真实克隆测试（@Tag("integration")）；成功后 localPath 存在 pom.xml；超时有明确错误信息

**禁止**：不逐文件调 GitHub API；不执行仓库内脚本；不用 --recurse-submodules

## T2 源码文件扫描器

**输入**：T1 的 localPath
**输出**：`List<CodeUnitFileInfo>`，含相对路径、包名、类名、language 字段

**要求**：路径模式从配置读取；只扫 `**/src/main/java` 下的 .java；忽略 package-info.java / module-info.java；支持多模块

**验收**：文件数与实际一致；每条记录 language = "java"；多模块项目每个模块都扫到

**禁止**：不写死 Java 特有路径；不在业务层判断文件语言

## T3 LanguageAnalyzer 接口定义

**输出**：`LanguageAnalyzer` 接口 + 语言中立 DTO

**要求**：方法名语言中立；DTO 含 `CodeUnitInfo`、`MethodInfo`、`DependencyEdge`；每条记录带 `language`、`framework`

**验收**：接口不出现 Java 特有命名；DTO 能容纳 Java 解析结果；预留扩展点

**禁止**：不出现 `ClassInfo`、`JavaClass` 等命名；不实现具体分析器

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

**禁止**：不做登录鉴权；不引入 MySQL

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

**禁止**：不把整个仓库塞给 LLM；不允许 LLM 编行号；不在业务层写 Java 特有逻辑

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
