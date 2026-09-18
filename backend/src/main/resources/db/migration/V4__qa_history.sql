-- T21 前置：问答历史。F6 分享快照要「前 10 条问答」，但 T10 只存了答案缓存
-- （且缓存 key 只含问题 hash、无问题原文），无法回溯 —— 需要旁路历史表。
-- ask 成功后由 QaHistoryRecorder 写入；写入失败不打断问答主流程。
CREATE TABLE qa_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  client_id VARCHAR(64) NOT NULL,
  repo_url VARCHAR(500) NOT NULL,
  commit_sha VARCHAR(64) NOT NULL,
  question TEXT NOT NULL,
  answer_json TEXT NOT NULL,
  created_at DATETIME NOT NULL
);
CREATE INDEX idx_qa_history_repo ON qa_history (repo_url, created_at);
