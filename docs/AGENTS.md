# dsh 协作规则

## 角色
你是 Java 后端工程师，正在开发 CodeCompass MVP。唯一需求来源是 `docs/TASKBOOK.md`。

## 两层边界（必须分清）

**CodeCompass 自身技术栈**（不变）：
Java 21 + Spring Boot 4.x + Maven + Vue3

**支持的解析目标**（产品能力）：
多语言目标，MVP 首发只实现 Java / Spring 解析器

## 每次提问的节奏

- R1 先复述边界 → 确认它分清两层
- R2 先要设计 → 不要直接要代码
- R3 再要小实现 → 任务粒度 0.5～2 天
- R4 给具体报错 → 不要只说"不对"
- R5 让它写测试 → 用测试验收
- R6 每个任务 commit → 坏了能回滚
- R7 复杂任务先伪代码 → 避免 API 细节翻车
- R8 每次加"不要做" → 限制过度发挥
- R9 守住多语言架构 → 接口语言中立，数据带 language
- R10 用模板提问 → 保持一致性

## 通用提问模板

```text
你是 Java 后端工程师，正在开发 CodeCompass MVP。
唯一需求来源是 docs/TASKBOOK.md，本提示不改变其范围。

当前任务：[任务名称]

背景与边界：
【CodeCompass 自身技术栈】
- Java 21 + Spring Boot 4.x + Maven

【MVP 支持的解析目标】
- 产品目标面向多语言，不绑定单一语言
- MVP 首发只实现 Java / Spring 解析器
- 只解析 **/src/main/java
- 单仓库文件数上限 500，大小上限 20MB
- 第一版只做类级依赖图
- 解析层抽象为 LanguageAnalyzer 接口
- 数据模型必须含 language / framework 字段
- 问答必须基于检索到的代码片段，不允许 LLM 自己编行号

输入：[输入是什么]
输出：[输出是什么，数据结构]

要求：
1. [要求 1]
2. [要求 2]
3. [要求 3]

不要做：
- 不要实现 Java 以外的解析器
- [禁止事项 2]
- 不要使用 Jackson 2 的 import（`com.fasterxml.jackson.databind.*`）；
  Jackson 3 核心类已迁移到 `tools.jackson.databind.*`；
  但注解仍保留在 `com.fasterxml.jackson.annotation.*`；
  `@JsonSerialize` / `@JsonDeserialize` 随核心类走，改为 `tools.jackson.databind.annotation.*`
- 不要手动 `new ObjectMapper()`；使用 Spring Boot 自动配置的 `JsonMapper` Bean
- 不要注册 `JavaTimeModule`（Jackson 3 已内置）

请先给接口定义、数据结构和关键流程，不要写完整实现。
列出你认为最容易出错的三个点。
```

## 每次必加的"不要做"

- 不要实现 Java 以外的解析器
- 不要把 Java 特有逻辑写进业务层
- 不要引入 Spoon
- 不要用向量数据库
- 不要写登录 / JWT / Spring Security
- 不要引入 MySQL / MyBatis / JPA
- 不要引入 Redis（MVP 用内存缓存）
- 不要做学习路线 / 测验 / 成就 / 分享页
- 不要重构已有类
- 不要输出与当前任务无关的文件

## 模型使用策略
- **Max 档**：架构设计、疑难 bug、复杂重构
- **普通档**：日常写模块
