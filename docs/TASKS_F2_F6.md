# CodeCompass F2/F4/F5/F6 任务卡

每个任务粒度 0.5～2 天。每完成一个任务 commit，格式：`T{编号}: {任务名}`。

## T13 MySQL 持久化基础设施

**输出**：MySQL 8 接入 + MyBatis-Plus（或 JPA）+ Flyway 迁移脚本

**要求**：
1. 引入 `mysql-connector-j` + `mybatis-plus-spring-boot3-starter` + `flyway-core`
2. 配置 `spring.datasource.*` 从环境变量读
3. 建库脚本 + 首批迁移表：`anonymous_users`、`learning_paths`、`quizzes`、`share_snapshots`、`notes`、`progress`、`achievements`、`user_actions`
4. 提供 `FlywayMigrationTest`：启动时自动迁移成功
5. 不引入 Redis

**与 T11 的关系**：`CacheService` 接口保留不变，新增 `MysqlCacheService` 实现，MVP 默认切到 MySQL。`InMemoryCacheService` 保留但不再默认启用。F1/F3 的业务代码不改，只改装配层的 Bean 选择。

**验收**：`mvn test` 全绿；启动时 Flyway 执行迁移；H2 用于单元测试，MySQL 用于集成测试

**禁止**：不引入 Redis；不把连接串硬编码；不做读写分离

## T14 F2 学习路线后端

**输入**：AnalyzeResult
**输出**：`LearningPath` + REST 接口

**要求**：
1. `LearningPathService` 用拓扑排序生成顺序，入口类第一
2. 同层按 unitName 字典序
3. LLM 只写每步 reason（≤ 60 字），不决定顺序
4. 结果存 `learning_paths` 表，按 `(repoUrl, commitSha)` 唯一
5. `POST /api/repos/{id}/learning-path` 生成
6. `GET /api/repos/{id}/learning-path` 获取
7. 复用 T11 的 `CacheService`

**验收**：spring-petclinic 首步是入口类；不重复不遗漏；二次请求不走 LLM

**禁止**：不把整个仓库发给 LLM；不用 LLM 决定顺序；不做方法级调用图

## T15 F2 学习路线前端

**输入**：T14 的 REST 接口
**输出**：Vue 学习路线标签页

**要求**：
1. 类列表加「学习路线」Tab
2. 按 order 展示，每步可点击跳转
3. 显示预计时间
4. 支持展开 / 折叠

**验收**：spring-petclinic 路线可展示、可跳转

**禁止**：不做登录页；不做测验 / 成就 / 分享页

## T16 F4 自动测验后端

**输入**：AnalyzeResult + 选中的 codeUnitIds
**输出**：`Quiz` + REST 接口

**要求**：
1. 题型：单选、判断
2. 每题带 reference，行号来自检索层
3. LLM 返回 JSON，逐条校验 reference，不匹配丢弃该题
4. 生成 5～10 道题
5. 存 `quizzes` 表
6. `POST /api/repos/{id}/quiz` 生成
7. `POST /api/quizzes/{id}/submit` 提交判分

**验收**：spring-petclinic 上 ≥ 5 题；reference 全部合法；提交返回正确率

**禁止**：不允许 LLM 编行号；不把整个仓库发给 LLM

## T17 F4 测验前端

**输入**：T16 的 REST 接口
**输出**：Vue 测验页

**要求**：
1. 类列表加「测验」Tab
2. 选中类后点「生成测验」
3. 逐题作答，提交后显示得分、错题解析
4. 引用行号可点击跳转

**验收**：spring-petclinic 测验可完成、可判分

**禁止**：不做需要人工判分的题型

## T18 F5 匿名身份 + 笔记

**输入**：无
**输出**：`AnonymousUser` + `Note` + REST 接口

**要求**：
1. 首次访问生成 UUID 写 Cookie（`cc_client_id`，30 天，HttpOnly）
2. 拦截器解析 Cookie，写入请求上下文
3. `GET /api/me`、`PUT /api/me { nickname }`
4. 笔记 CRUD：`POST/PUT/DELETE /api/notes`、`GET /api/notes?repoUrl=`
5. 存 `anonymous_users`、`notes` 表

**验收**：换浏览器 Cookie 恢复；笔记保存后刷新仍在；不同用户数据隔离

**禁止**：不做登录 / JWT / Spring Security；不跨用户共享

## T19 F5 进度 + 成就

**输入**：AnonymousUser + 分析结果
**输出**：`Progress` + `Achievement` + REST 接口

**要求**：
1. `PUT /api/progress { repoUrl, codeUnitId, status }`，status ∈ {unread, reading, done}
2. `GET /api/progress?repoUrl=`
3. 定义 5～8 个成就，规则写在配置
4. 触发条件服务端判断
5. `GET /api/achievements` 返回已解锁
6. 存 `progress`、`achievements` 表

**验收**：进度持久化；完成第一个分析后 `FIRST_REPO` 解锁；成就规则可配置

**禁止**：不做社交排名；不做积分商城

## T20 F5 前端：进度标记 + 笔记面板 + 成就

**输入**：T18 / T19 的 REST 接口
**输出**：Vue 组件

**要求**：
1. 类列表每项加进度标记
2. 右侧加笔记面板
3. 顶部加成就入口
4. 成就解锁时弹提示

**验收**：三个功能都能在页面上操作

**禁止**：不做登录页

## T21 F6 分享领读页后端

**输入**：完成的分析结果
**输出**：`ShareSnapshot` + 短链

**要求**：
1. `POST /api/repos/{id}/share` 生成快照
2. 快照内容：仓库信息 + 依赖图 + 学习路线 + 前 10 条问答 + 已解锁成就
3. 生成前过滤敏感字段（API key、完整源码、他人笔记）
4. 过期时间默认 30 天，可配置
5. `GET /share/{id}` 返回 HTML
6. 存 `share_snapshots` 表

**验收**：无 Cookie 浏览器能打开；过期返回明确提示

**禁止**：不暴露 API key；不暴露完整源码；不暴露他人笔记

## T22 F6 分享页前端

**输入**：T21 的短链
**输出**：只读分享页

**要求**：
1. 分析完成后加「生成分享页」按钮
2. 弹出短链可复制
3. 分享页只读
4. 底部加「由 CodeCompass 生成」

**验收**：短链可访问；页面展示依赖图、学习路线、问答

**禁止**：不做编辑功能

## T23 端到端验收

**验收项**：
- F2：spring-petclinic 生成学习路线，首步为入口类，二次请求命中数据库
- F4：生成 ≥ 5 道题，reference 全部合法，提交返回正确率
- F5：Cookie 恢复身份，笔记 / 进度持久化，`FIRST_REPO` 解锁
- F6：短链在无 Cookie 浏览器打开，不暴露敏感信息
- 回归：F1、F3 功能不受影响
- 多语言扩展点：新增 Python stub 后业务层不改

**禁止**：不为通过验收写死数据

## T24 UI 统一修正

**输入**：T13～T23 全部产出
**输出**：统一的 Vue 页面

**要求**：
1. 依赖图默认不铺全图，点击类才显示邻域
2. 节点颜色按角色区分：entry 橙 / controller 蓝 / service 绿 / entity 灰 / mapper 紫
3. 页面布局统一：左栏类列表 + 进度标记，中栏源码 / 问答，右栏图 / 路线 / 测验
4. Tab 切换：学习路线 / 测验 / 笔记 / 成就 四个面板
5. 问答框保留，引用行号可点击跳转

**验收**：你自己打开后愿意天天用

**禁止**：不重写后端；不引入新图表库（继续用 Mermaid）
