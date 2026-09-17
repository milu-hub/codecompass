# CodeCompass MVP 任务书 v0.3

> 本文件是 CodeCompass MVP 的**唯一需求来源**。与《项目背景》《dsh 协作指南》冲突时，以本文件为准。产品目标面向多语言；MVP 只实现 Java / Spring 解析器，其余语言只预留接口，不实现。

## 01 垂直切片

- S1 输入 GitHub 公开仓库地址
- S2 后端浅克隆仓库
- S3 只扫描 `**/src/main/java`
- S4 JavaParser 提取包 / 类 / 注解 / 方法 / 依赖
- S5 输出 JSON（含 language 字段）
- S6 前端展示类列表和简单依赖图（Mermaid）
- S7 点击某个类，基于该类代码片段向 LLM 提问

**范围纪律**：学习路线、测验、成就、分享页、登录/鉴权、其他语言实现，全部往后放。

## 02 MVP 硬边界

### 自身技术栈
| 项 | 定义 |
|---|---|
| 语言与框架 | Java 21 + Spring Boot 4.x + Maven |
| 前端 | Vue3 + Vite + Pinia + Element Plus + Mermaid |
| 缓存 | 内存缓存（Caffeine 或 ConcurrentHashMap + TTL），MVP 不引入 Redis |

### 支持的解析目标
| 项 | 定义 |
|---|---|
| 产品定位 | 多语言代码理解与教学平台，不绑定单一语言 |
| MVP 首发解析器 | 只实现 Java / Spring 解析器（第一个 `LanguageAnalyzer`） |
| 解析范围 | 只解析 `**/src/main/java`，忽略 `src/test`，支持多模块 Maven 项目 |
| 仓库限制 | 单仓库文件数上限 1000，单文件大小上限 20MB，单仓库大小上限 500MB（大小按落盘字节计：`.git` + 已检出工作区） |
| 图粒度 | 先做类级依赖图，不做方法级调用图 |

### 架构约束
| 项 | 定义 |
|---|---|
| 解析层 | 必须抽象为 `LanguageAnalyzer` 接口，JavaParser 只是第一个实现 |
| 数据模型 | 核心结构必须含 `language`、`framework` 字段，命名语言中立（用 `CodeUnitInfo` 而非 `ClassInfo`） |
| 业务层 | 禁止出现 Java 特有判断（如 `if (language == "java")`），差异下沉到分析器实现 |
| 问答行号 | 必须基于检索到的代码片段，不允许 LLM 自己编行号 |
| 引用格式 | references 每条记录带 `file`、`startLine`、`endLine`、`language` |

### 第一版不做（写死，不讨论）
- Java 以外的解析器实现（Python / Go / TypeScript 等只预留接口）
- 学习路线、自动测验、笔记、成就、分享页
- 登录 / JWT / Spring Security
- MySQL / MyBatis / JPA
- Redis（MVP 用内存缓存）
- Spoon、向量数据库、方法级调用图
- GitHub stars / 语言分布等元数据展示

## 03 黄金样本

| 样本 | 用途 |
|---|---|
| spring-projects/spring-petclinic | 单模块、结构简单，第一版验证（源码在**根级** `src/main/java`，零前缀） |
| spring-petclinic/spring-petclinic-microservices | 多模块（8 个模块，无根级源码），测复杂场景 |
| 一个你熟悉的项目 | 人工判断解析结果是否合理 |

每个仓库需要手工标注：
- 入口类
- Controller / Service / Repository 列表
- 核心依赖关系
- 任选 5 个类的功能描述

## 04 验收标准

| 指标 | 目标 | 口径 |
|---|---|---|
| 核心注解类识别覆盖率 | ≥ 80% | 分母 = 黄金样本中人工标注的"带核心注解的类"总数 |
| 问答引用行号准确率 | ≥ 90% | 每次抽样 20 条，人工核对 file + startLine + endLine |
| 单次分析成本 | < 0.1 元 | 监控指标，不硬卡验收；超限触发复查 |
| 缓存命中后响应 | < 2 秒 | 缓存命中后返回依赖图 JSON 的 P95 |

## 05 高风险预研（先行）

### 预研 1：仓库获取
```bash
git clone --depth 1 --filter=blob:none --no-checkout <repo-url> repo
cd repo
git sparse-checkout init --no-cone
git sparse-checkout set 'pom.xml' '**/pom.xml' '**/src/main/java/**'
git checkout
# 分析完成后由应用删除临时目录（删除前校验路径在临时根目录下）
```

约束：禁用 submodule，不执行仓库内脚本，60 秒默认超时，路径模式从配置读取。

### 预研 2：JavaParser 信息提取
输入 Spring 项目目录，输出：包名、类名、类注解、字段及注解、方法签名及注解、import 关系。
先做语法级解析，不做符号求解。验证输出能否自然填充到语言中立的 CodeUnitInfo。

### 预研 3：行号引用准确性
流程：用户提问 → 后端检索到文件和行范围 → 文件路径 + 行号 + 代码片段发给 LLM → LLM 返回 JSON → 前端跳转。

```json
{ "answer": "...", "references": [{ "file": "...", "language": "java", "startLine": 0, "endLine": 0 }] }
```

## 06 四周计划

| 周 | 目标 |
|---|---|
| W1 | 项目骨架 + 仓库获取 + 文件扫描（路径模式从配置读取） |
| W2 | LanguageAnalyzer 接口 + JavaSpringAnalyzer + 类级依赖图 |
| W3 | 检索 + LLM 问答（行号必须来自检索层） |
| W4 | 部署 + 20 个用户试用 + 多语言扩展点验证 |

## 07 底线

| 类别 | 底线 |
|---|---|
| 缓存 key | repo commit SHA + 文件路径 + 问题 hash + 模型版本 |
| 用量控制 | 按匿名 session / IP 每日 token 上限（MVP 无登录） |
| 任务模型 | 分析任务异步化，用 SSE 推进度 |
| 成本控制 | 永远不要把整个仓库塞给大模型 |
| 数据安全 | 只分析公开仓库，不存储完整私有代码，分析完删除临时目录 |
| 克隆安全 | 禁用 submodule，不执行仓库内脚本，超时保护，删除前校验路径 |
| 凭据管理 | 用户 token 加密存储，不写日志 |
| 引用校验 | LLM 返回的 JSON references 逐条与检索结果比对，不匹配则丢弃重试 |
| 缓存 TTL | 内存缓存设置 TTL 或容量上限，不公开大段源码 |
| 单实例约束 | MVP 只支持单实例部署，应用重启缓存丢失可接受 |
| 多语言架构 | 业务层禁止 Java 特有判断；新增语言只应新增一个 LanguageAnalyzer 实现与一份配置 |
