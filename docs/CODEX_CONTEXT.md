项目：CodeCompass
自身技术栈：Java 21 + Spring Boot 4.x + Maven + Vue3
产品目标：多语言代码理解与教学平台
MVP 首发：只实现 Java/Spring 解析器

MVP 边界：
- 只解析 **/src/main/java
- 只做类级依赖图
- 解析层抽象为 LanguageAnalyzer 接口
- 数据模型含 language/framework 字段，命名语言中立
- 问答必须基于检索片段，行号来自检索层

第一版不做：
其他语言解析器、学习路线、测验、笔记、成就、分享页、登录/JWT、
Spring Security、MySQL、MyBatis/JPA、Spoon、向量数据库、方法级调用图、
Redis（MVP 用内存缓存）。

唯一需求来源：docs/TASKBOOK.md
协作规则：docs/AGENTS.md
任务卡：docs/TASKS.md
数据模型：docs/SCHEMA.md
