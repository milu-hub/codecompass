-- T13 首批基线：SCHEMA_F2_F6.md 的 8 张表 + cache_entries（第 9 张，T13 追加）。
--
-- 双通纪律：同一套脚本要在 H2（MODE=MySQL，单元测试）与 MySQL 8（集成/生产）都通过。
-- 因此只用两种方言的交集语法：
--   - 不用 ENGINE=InnoDB / CHARSET 等 MySQL 特有子句
--   - 索引一律独立 CREATE [UNIQUE] INDEX，不用 UNIQUE KEY 行内子句
--   - JSON / DATETIME / AUTO_INCREMENT 两种库都支持

-- 匿名用户（F5 身份）
CREATE TABLE anonymous_users (
  client_id VARCHAR(64) PRIMARY KEY,
  nickname VARCHAR(64),
  created_at DATETIME NOT NULL,
  last_seen_at DATETIME NOT NULL
);

-- 学习路线（F2）：按 (repo_url, commit_sha) 唯一
CREATE TABLE learning_paths (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  path_json JSON NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX uk_learning_paths_repo_commit ON learning_paths (repo_url, commit_sha);

-- 测验（F4）
CREATE TABLE quizzes (
  id VARCHAR(64) PRIMARY KEY,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  quiz_json JSON NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE INDEX idx_quizzes_repo ON quizzes (repo_url, commit_sha);

-- 分享快照（F6）
CREATE TABLE share_snapshots (
  id VARCHAR(32) PRIMARY KEY,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  snapshot_json JSON NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE INDEX idx_share_snapshots_expires ON share_snapshots (expires_at);

-- 笔记（F5）：每用户每仓库每类一条
CREATE TABLE notes (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  repo_url VARCHAR(500) NOT NULL,
  code_unit_id VARCHAR(128) NOT NULL,
  content TEXT NOT NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);
CREATE INDEX idx_notes_client_repo ON notes (client_id, repo_url);
CREATE UNIQUE INDEX uk_notes_client_repo_unit ON notes (client_id, repo_url, code_unit_id);

-- 阅读进度（F5）
CREATE TABLE progress (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  repo_url VARCHAR(500) NOT NULL,
  code_unit_id VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  updated_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX uk_progress_client_repo_unit ON progress (client_id, repo_url, code_unit_id);

-- 成就解锁（F5）
CREATE TABLE achievements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  code VARCHAR(64) NOT NULL,
  unlocked_at DATETIME NOT NULL
);
CREATE UNIQUE INDEX uk_achievements_client_code ON achievements (client_id, code);

-- 用户行为流水（F5 成就计数不依赖内存，在这张表上数）
CREATE TABLE user_actions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  repo_url VARCHAR(500),
  ref_id VARCHAR(128),
  created_at DATETIME NOT NULL
);
CREATE INDEX idx_user_actions_client_action ON user_actions (client_id, action, created_at);

-- 缓存表（T13 追加）：CacheService 的 MySQL 落点。
-- value_json 是通用命名 —— 现在存 AnswerResponse，将来 F2/F4 的缓存结果也进这张表。
CREATE TABLE cache_entries (
  cache_key VARCHAR(64) PRIMARY KEY,
  value_json JSON NOT NULL,
  expires_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE INDEX idx_cache_entries_expires ON cache_entries (expires_at);
