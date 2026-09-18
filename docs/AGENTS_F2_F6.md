# dsh 协作规则 · F2/F4/F5/F6 追加

> 本文件是 `AGENTS.md` 的补充。做 F2/F4/F5/F6 任务时，在通用模板基础上追加以下约束。

## 追加的「不要做」

- 不要写登录 / JWT / Spring Security
- 不要引入 Redis
- 不要引入向量数据库
- 不要引入图数据库
- 不要用 Jackson 2 的 import（`com.fasterxml.jackson.databind.*`）
- 不要手动 `new ObjectMapper()`；用自动配置的 `JsonMapper` Bean
- 不要注册 `JavaTimeModule`（Jackson 3 已内置）
- 不要把整个仓库发给 LLM
- 不允许 LLM 编行号
- 不要在分享页暴露 API key / 完整源码 / 他人笔记
- 不要做社交排名 / 积分商城

## 追加的「必须做」

- 匿名身份用 Cookie（`cc_client_id`，30 天，HttpOnly）
- 持久化用 MySQL 8 + MyBatis-Plus（或 JPA）+ Flyway
- 行号必须来自检索层
- F2/F4 的结果按 `(repoUrl, commitSha)` 缓存
- F6 快照生成前过滤敏感字段
- 每张表有索引和唯一约束，参考 `SCHEMA_F2_F6.md`

## 通用任务指令模板（F2/F4/F5/F6 版）

```text
你是 Java 后端工程师，正在开发 CodeCompass。
唯一需求来源是 docs/FEATURE_SPEC_F2_F6.md，本提示不改变其范围。

当前任务：[T编号 + 任务名]

背景与边界：
【CodeCompass 自身技术栈】
- Java 21 + Spring Boot 4.x + Maven
- 持久化：MySQL 8 + MyBatis-Plus + Flyway
- 身份：匿名 Cookie，不做登录

【产品目标】
- 多语言代码理解与教学平台
- MVP 首发 Java / Spring 解析器
- 解析层抽象为 LanguageAnalyzer 接口
- 数据模型含 language / framework 字段
- 问答行号来自检索层

输入：[从 TASKS_F2_F6.md 对应任务复制]
输出：[从 TASKS_F2_F6.md 对应任务复制]

要求：
1. [从 TASKS_F2_F6.md 对应任务复制]
2. [从 TASKS_F2_F6.md 对应任务复制]
3. [从 TASKS_F2_F6.md 对应任务复制]

不要做：
- 不要写登录 / JWT / Spring Security
- 不要引入 Redis / 向量数据库 / 图数据库
- 不要用 Jackson 2 的 import
- 不要手动 new ObjectMapper()；用自动配置的 JsonMapper Bean
- 不要注册 JavaTimeModule（Jackson 3 已内置）
- 不要把整个仓库发给 LLM
- 不允许 LLM 编行号
- 不要在分享页暴露 API key / 完整源码 / 他人笔记
- 不要做社交排名 / 积分商城
- 不要重构 F1 / F3 已有代码

请先给接口定义、数据结构和关键流程，不要写完整实现。
列出你认为最容易出错的三个点。
```
