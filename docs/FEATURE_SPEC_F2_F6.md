# CodeCompass F2 / F4 / F5 / F6 需求规格

> 本文件是 F2、F4、F5、F6 的唯一需求来源。与《项目背景》《dsh 协作指南》冲突时，以本文件为准。不改变 F1、F3 的既有边界。

## 通用约束

- 自身技术栈：Java 21 + Spring Boot 4.x + Maven + Vue3
- 持久化：MySQL 8 + MyBatis-Plus（或 Spring Data JPA），不使用 Redis
- 身份：匿名 Cookie（不做登录 / JWT / Spring Security）
- 解析目标：仍面向多语言，MVP 首发 Java；业务层禁止 Java 特有判断
- LLM 调用：走现有 `LlmConfigService.getDefault()`，不新增配置入口
- 行号：所有引用必须来自检索层，不接受 LLM 编造
- 缓存：可复用 T11 的 `CacheService` 接口；F2/F4 结果按 repo commit SHA 缓存

## F2 学习路线

### 目标
根据 AnalyzeResult，生成一个推荐阅读顺序，让初学者按步骤读源码。

### 输入
- AnalyzeResult（T6 产出）
- 可选：用户指定的起止类

### 输出
`LearningPath { repoUrl, commitSha, steps: [{ order, codeUnitId, reason, estimatedMinutes }] }`

### 后端要求
1. 用拓扑排序确定顺序：被依赖多的先读，入口类排第一
2. 同层按 `unitName` 字典序，保证确定性
3. 每步用 LLM 生成一句「为什么先读它」，控制在 60 字内
4. LLM 只接收该类的元数据（名字、注解、依赖、方法签名），**不接收完整源码**
5. 结果按 `(repoUrl, commitSha)` 存入 `learning_paths` 表
6. 提供 `POST /api/repos/{id}/learning-path` 生成、`GET /api/repos/{id}/learning-path` 获取

### 前端要求
1. 类列表上方加「学习路线」标签页
2. 按 order 展示步骤，每步可点击跳转到对应类
3. 显示预计时间
4. 支持「展开全部 / 只看前 5 步」

### 验收
- spring-petclinic 生成的路线首步为入口类
- 路线不重复、不遗漏任何 codeUnit
- 每步有 reason，非空
- 二次请求命中数据库，不重复调 LLM

### 禁止
- 不做方法级调用图
- 不把整个仓库发给 LLM
- 不引入图数据库
- 不用 LLM 决定顺序（顺序由拓扑排序定，LLM 只写 reason）

## F4 自动测验

### 目标
根据分析结果和用户选中的类，生成测验题，检验是否真的看懂。

### 输入
- AnalyzeResult
- 用户选中的 codeUnitIds（至少 1 个，最多 5 个）

### 输出
`Quiz { id, repoUrl, commitSha, questions: [{ id, type, question, options, answer, explanation, reference }] }`

### 后端要求
1. 题型至少覆盖单选、判断两种
2. 每题带 reference（file + language + startLine + endLine），行号来自检索层
3. LLM 返回 JSON，解析后逐条校验 reference，不匹配丢弃该题
4. 至少生成 5 道题，最多 10 道
5. 结果存入 `quizzes` 表
6. 提供 `POST /api/repos/{id}/quiz` 生成、`POST /api/quizzes/{id}/submit` 提交答案判分

### 前端要求
1. 类列表上方加「测验」标签页
2. 选中类后点「生成测验」
3. 逐题作答，最后提交
4. 显示得分、错题解析、引用行号可点击跳转

### 验收
- spring-petclinic 上生成 ≥ 5 道题
- 每题 reference 全部落在检索片段内
- 提交后返回正确率
- 二次请求命中数据库

### 禁止
- 不允许 LLM 编行号
- 不把整个仓库发给 LLM
- 不做填空、简答等需要人工判分的题型

## F5 笔记 · 进度 · 成就

### 目标
让用户记录学习笔记、追踪阅读进度、解锁成就。

### 输入
- 匿名用户身份（Cookie）
- 仓库分析结果

### 输出
- `Note { id, clientId, repoUrl, codeUnitId, content, createdAt, updatedAt }`
- `Progress { clientId, repoUrl, codeUnitId, status, updatedAt }`（status: unread / reading / done）
- `Achievement { code, name, description, unlockedAt }`

### 后端要求

**匿名身份**
1. 首次访问生成 UUID，写入 Cookie（`cc_client_id`，30 天，HttpOnly）
2. 每次请求由拦截器解析 Cookie，写入 ThreadLocal 或 RequestScope
3. 提供 `GET /api/me` 返回 clientId
4. 允许设置昵称：`PUT /api/me { nickname }`，存 `anonymous_users` 表

**笔记**
1. CRUD：`POST/PUT/DELETE /api/notes`、`GET /api/notes?repoUrl=`
2. 笔记绑定到 `(clientId, repoUrl, codeUnitId)`

**进度**
1. `PUT /api/progress { repoUrl, codeUnitId, status }`
2. `GET /api/progress?repoUrl=` 返回该仓库所有进度
3. 前端类列表显示进度标记

**成就**
1. 定义 5～8 个成就，规则写在配置里
2. 触发条件在服务端判断，例如：
   - `FIRST_REPO`：完成第一个仓库分析
   - `FIRST_NOTE`：写下第一条笔记
   - `TEN_QUESTIONS`：提问 10 次
   - `QUIZ_MASTER`：一次测验正确率 100%
   - `PATH_FINISHED`：完成一条学习路线
3. `GET /api/achievements` 返回当前用户已解锁成就
4. 解锁时记录到 `achievements` 表

**成就触发点**
- `FIRST_REPO`：`POST /api/repos` 分析成功后写入 `user_actions(action=analyze)`，计数 ≥ 1 时解锁
- `FIRST_NOTE`：`POST /api/notes` 成功后写 `user_actions(action=note)`，计数 ≥ 1 时解锁
- `TEN_QUESTIONS`：`POST /api/repos/{id}/ask` 成功后写 `user_actions(action=ask)`，计数 ≥ 10 时解锁
- `QUIZ_MASTER`：`POST /api/quizzes/{id}/submit` 返回正确率 100% 时解锁
- `PATH_FINISHED`：用户在 progress 表把学习路线的全部步骤标为 done 时解锁

所有判断在服务端 `AchievementService.check(clientId, action)` 里做，规则从配置读。

### 前端要求
1. 类列表每项加进度标记（未读 / 阅读中 / 已完成）
2. 右侧加笔记面板，可编辑当前类的笔记
3. 顶部加成就入口，展示已解锁 / 未解锁
4. 成就解锁时弹出提示

### 验收
- 换浏览器后 Cookie 仍能恢复身份
- 笔记保存后刷新页面仍在
- 进度状态能持久化
- 完成第一个分析后 `FIRST_REPO` 成就解锁
- 匿名用户之间数据隔离

### 禁止
- 不做登录 / 密码 / 邮箱
- 不做社交排名
- 不做积分商城
- 不跨用户共享笔记

## F6 可分享「领读页」

### 目标
把一次分析的成果（依赖图 + 学习路线 + 问答记录）导出为静态页，生成短链分享。

### 输入
- 一次完成的分析结果
- 可选的笔记、问答记录

### 输出
- `ShareSnapshot { id, repoUrl, commitSha, snapshotJson, expiresAt, createdAt }`
- 短链：`/share/{id}`

### 后端要求
1. `POST /api/repos/{id}/share` 生成快照，返回 `{ shareId, url }`
2. 快照内容：仓库信息 + 依赖图 + 学习路线 + 前 10 条问答 + 已解锁成就（脱敏）
3. 生成前过滤：不包含 API key、不包含完整源码、不包含其他用户笔记
4. 快照过期时间默认 30 天，可配置
5. `GET /share/{id}` 返回 HTML 页面（不需要登录）
6. 过期快照返回 404 或「已过期」页

**渲染方式**
`GET /share/{id}` 返回一个独立的 HTML 页面，由后端用模板引擎（Thymeleaf 或手写字符串）渲染，不复用前端 SPA。原因是分享页要被无 Cookie 的浏览器打开，且不能依赖前端打包产物。页面内嵌 Mermaid CDN 渲染依赖图。

**成就脱敏**
分享页只展示成就的 code 和 name，不展示 clientId、不展示 unlockedAt。分享页不继承原用户的任何身份信息。

### 前端要求
1. 分析完成后加「生成分享页」按钮
2. 生成后弹出短链，可复制
3. 分享页只读，不显示编辑按钮
4. 分享页底部加「由 CodeCompass 生成」标识

### 验收
- 生成的短链在无 Cookie 的浏览器能打开
- 分享页展示依赖图、学习路线、问答记录
- 分享页不包含任何用户私有信息
- 过期后访问返回明确提示

### 禁止
- 不在分享页暴露 API key
- 不暴露完整源码
- 不暴露其他用户的笔记
- 不做登录才能看分享页
