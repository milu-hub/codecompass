# CodeCompass F2/F4/F5/F6 作战手册

> 本文件只给你自己看，不要发给 dsh。dsh 需要的内容从 `FEATURE_SPEC_F2_F6.md`、`TASKS_F2_F6.md`、`SCHEMA_F2_F6.md`、`AGENTS_F2_F6.md` 复制。

## 一、开工前准备

### 1.1 确认环境

- MySQL 8 已启动（本地或 Docker）
- 环境变量已设置：

```text
DB_URL=jdbc:mysql://localhost:3306/codecompass
DB_USER=root
DB_PASSWORD=xxx
CODESCOMPASS_LLM_API_KEY=sk-xxx
```

### 1.2 新增文档

把以下 4 份放进 `docs/`：

- `FEATURE_SPEC_F2_F6.md`
- `TASKS_F2_F6.md`
- `SCHEMA_F2_F6.md`
- `AGENTS_F2_F6.md`

### 1.3 更新 PROGRESS.md

```markdown
## 当前任务
T13 MySQL 持久化基础设施

## 已完成
（保留 F1/F3 的历史）

## 决策记录
- 2026-09-18：F2/F4/F5/F6 一次性规划，分批实现
- 2026-09-18：引入 MySQL 持久化，不做登录，用匿名 Cookie
```

## 二、任务顺序

```text
T13 MySQL 持久化基础设施
T14 F2 学习路线后端
T15 F2 学习路线前端
T16 F4 自动测验后端
T17 F4 测验前端
T18 F5 匿名身份 + 笔记
T19 F5 进度 + 成就
T20 F5 前端：进度 + 笔记 + 成就
T21 F6 分享领读页后端
T22 F6 分享页前端
T23 端到端验收
T24 UI 统一修正
```

不要跳步。每个任务跑通再进下一个。

## 三、每个任务的标准流程

1. 从 TASKS_F2_F6.md 复制当前任务的输入/输出/要求/禁止
2. 填进 AGENTS_F2_F6.md 的通用模板
3. 发给 dsh
4. dsh 给设计 → 你审核
5. dsh 写代码 → 你复制到项目
6. 跑通 → commit → 更新 PROGRESS.md
7. 下一个任务

## 四、T13 完整示例

你发（用 AGENTS_F2_F6.md 模板）：

```text
你是 Java 后端工程师，正在开发 CodeCompass。
唯一需求来源是 docs/FEATURE_SPEC_F2_F6.md，本提示不改变其范围。

当前任务：T13 MySQL 持久化基础设施

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

输入：无
输出：MySQL 8 接入 + MyBatis-Plus + Flyway 迁移

要求：
1. 引入 mysql-connector-j + mybatis-plus-spring-boot3-starter + flyway-core
2. 配置 spring.datasource.* 从环境变量读
3. 建库脚本 + 首批迁移表：anonymous_users、learning_paths、quizzes、share_snapshots、notes、progress、achievements、user_actions
4. 提供 FlywayMigrationTest：启动时自动迁移成功
5. 不引入 Redis

不要做：
- 不要写登录 / JWT / Spring Security
- 不要引入 Redis / 向量数据库 / 图数据库
- 不要用 Jackson 2 的 import
- 不要手动 new ObjectMapper()；用自动配置的 JsonMapper Bean
- 不要注册 JavaTimeModule（Jackson 3 已内置）
- 不要重构 F1 / F3 已有代码

请先给接口定义、数据结构和关键流程，不要写完整实现。
列出你认为最容易出错的三个点。
```

dsh 给设计 → 你审核 → dsh 写代码 → 你复制到项目

验证：

```bash
mvn -f backend/pom.xml test
# 看 Flyway 是否执行迁移
```

提交：

```bash
git add .
git commit -m "T13: MySQL 持久化基础设施"
```

更新 PROGRESS.md：

```markdown
## 当前任务
T14 F2 学习路线后端

## 已完成
- T13 MySQL 持久化基础设施（2026-09-18）
```

## 五、T14～T24 照此循环

每个任务都套 AGENTS_F2_F6.md 的模板，输入/输出/要求/禁止从 TASKS_F2_F6.md 对应任务复制。

## 六、遇到问题的处理

代码报错：

```text
我运行了，报错如下：
[完整报错]

请分析原因，给出修改方案。
注意：不要重写整个类，只改 [具体部分]。
```

dsh 跑偏：

```text
这不在 MVP 范围内。请回退这部分，只保留当前任务要求的内容。
记住：不做登录 / Redis / 向量数据库 / 社交排名 / 积分商城。
```

dsh 说"已完成"但不确定：

```text
请为这个模块写验收测试，用 spring-petclinic 作为样本。
测试失败时输出实际结果和期望结果的差异。
```

## 七、验收清单（T23）

| 功能 | 验收点 | 通过？ |
|---|---|---|
| F2 | spring-petclinic 生成路线，首步是入口类 | ⬜ |
| F2 | 二次请求命中数据库，不走 LLM | ⬜ |
| F4 | 生成 ≥ 5 道题，reference 全部合法 | ⬜ |
| F4 | 提交返回正确率 | ⬜ |
| F5 | Cookie 恢复身份 | ⬜ |
| F5 | 笔记保存后刷新仍在 | ⬜ |
| F5 | 进度持久化 | ⬜ |
| F5 | FIRST_REPO 成就解锁 | ⬜ |
| F6 | 短链在无 Cookie 浏览器打开 | ⬜ |
| F6 | 分享页不暴露敏感信息 | ⬜ |
| 回归 | F1/F3 不受影响 | ⬜ |
| 多语言 | Python stub 后业务层不改 | ⬜ |
| UI | 四个新 Tab 与 F1/F3 视觉统一；依赖图点选邻域；T10 问答框可用（T24 完成后验收） | ⬜ |

## 八、每次会话开场白

新会话：

```text
请先读 docs/PROGRESS.md 和 docs/TASKS_F2_F6.md，
告诉我当前任务是什么，已经完成了哪些。不要写代码。
```

同会话继续：

```text
T[X] 已完成并提交。现在进入 T[X+1]，模板如下：
（粘贴 AGENTS_F2_F6.md 模板）
```

## 九、你需要记住的六件事

1. 每次只发当前任务需要的文件
2. 每次只做一个任务
3. 先设计后代码
4. 跑通再 commit
5. 每次加「不要做」
6. 更新 PROGRESS.md
