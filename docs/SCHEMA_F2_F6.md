# CodeCompass F2/F4/F5/F6 数据模型

## 数据库表

### anonymous_users

```sql
CREATE TABLE anonymous_users (
  client_id VARCHAR(64) PRIMARY KEY,
  nickname VARCHAR(64),
  created_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL
);
```

### learning_paths

```sql
CREATE TABLE learning_paths (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  path_json JSON NOT NULL,
  created_at DATETIME NOT NULL,
  UNIQUE KEY uk_repo_commit (repo_url, commit_sha)
);
```

### quizzes

```sql
CREATE TABLE quizzes (
  id VARCHAR(64) PRIMARY KEY,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  quiz_json JSON NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_repo (repo_url, commit_sha)
);
```

### share_snapshots

```sql
CREATE TABLE share_snapshots (
  id VARCHAR(32) PRIMARY KEY,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  snapshot_json JSON NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL,
  INDEX idx_expires (expires_at)
);
```

### notes

```sql
CREATE TABLE notes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  repo_url VARCHAR(500) NOT NULL,
  code_unit_id VARCHAR(128) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL,
  INDEX idx_client_repo (client_id, repo_url),
  UNIQUE KEY uk_note (client_id, repo_url, code_unit_id)
);
```

### progress

```sql
CREATE TABLE progress (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  repo_url VARCHAR(500) NOT NULL,
  code_unit_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_progress (client_id, repo_url, code_unit_id)
);
```

### achievements

```sql
CREATE TABLE achievements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  code VARCHAR(64) NOT NULL,
  unlocked_at DATETIME NOT NULL,
  UNIQUE KEY uk_client_code (client_id, code)
);
```

### user_actions

```sql
CREATE TABLE user_actions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,        -- analyze / ask / note / quiz_submit / path_step_done
  repo_url VARCHAR(500),
  ref_id VARCHAR(128),                -- 可选：关联的 codeUnitId / quizId
  created_at DATETIME NOT NULL,
  INDEX idx_client_action (client_id, action, created_at)
);
```

F5 的成就在这张表上计数，不依赖内存。

### cache_entries（T13 追加）

```sql
CREATE TABLE cache_entries (
  cache_key VARCHAR(64) PRIMARY KEY,  -- CacheKey 四组件（commitSha/文件/问题hash/模型）的 hash
  value_json JSON NOT NULL,           -- 缓存值：现在存 AnswerResponse，将来 F2/F4 共用
  expires_at DATETIME NOT NULL,       -- TTL 惰性清理
  created_at DATETIME NOT NULL,
  INDEX idx_cache_expires (expires_at)
);
```

`CacheService`（T11 接口）的 MySQL 落点：`MysqlCacheService` 用这张表持久化；
字段名 `value_json` 刻意通用 —— 学习路线、测验结果等后续缓存也进这张表。

## JSON 结构

### LearningPath

```json
{
  "repoUrl": "string",
  "commitSha": "string",
  "steps": [
    {
      "order": 1,
      "codeUnitId": "string",
      "reason": "先读入口类，了解启动流程",
      "estimatedMinutes": 15
    }
  ]
}
```

### Quiz

```json
{
  "id": "string",
  "repoUrl": "string",
  "commitSha": "string",
  "questions": [
    {
      "id": "q1",
      "type": "single_choice",
      "question": "OwnerController 的主要职责是什么？",
      "options": ["A...", "B...", "C...", "D..."],
      "answer": "A",
      "explanation": "...",
      "reference": {
        "file": "src/main/java/.../OwnerController.java",
        "language": "java",
        "startLine": 40,
        "endLine": 55
      }
    }
  ]
}
```

### ShareSnapshot

```json
{
  "repoUrl": "string",
  "commitSha": "string",
  "generatedAt": "ISO8601",
  "graph": { "nodes": [], "edges": [] },
  "learningPath": { "steps": [] },
  "qaSamples": [
    { "question": "...", "answer": "...", "references": [] }
  ],
  "achievements": ["FIRST_REPO"]
}
```

### AnonymousUser

```json
{
  "clientId": "uuid",
  "nickname": "string | null",
  "createdAt": "ISO8601"
}
```
