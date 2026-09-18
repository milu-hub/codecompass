-- T20 实测修正（两个问题一起解决）：
-- 1) T4 的单元 id = repositoryId:filePath#全限定名，真机长度超 150，
--    notes/progress.code_unit_id VARCHAR(128) 写入报 Data too long；
-- 2) V1 的三列唯一索引 (client_id 64 + repo_url 500 + code_unit_id 128) 在 utf8mb4 下
--    加宽后超过 InnoDB 3072 字节上限（实测 MySQL 1071）。
-- 解法：单元 id 内含 repositoryId（T4 契约，同用户跨仓库即全局唯一），
-- 唯一索引缩为 (client_id, code_unit_id)，code_unit_id 加宽到 512。
ALTER TABLE notes DROP INDEX uk_notes_client_repo_unit;
ALTER TABLE notes MODIFY COLUMN code_unit_id VARCHAR(512) NOT NULL;
CREATE UNIQUE INDEX uk_notes_client_unit ON notes (client_id, code_unit_id);

ALTER TABLE progress DROP INDEX uk_progress_client_repo_unit;
ALTER TABLE progress MODIFY COLUMN code_unit_id VARCHAR(512) NOT NULL;
CREATE UNIQUE INDEX uk_progress_client_unit ON progress (client_id, code_unit_id);
