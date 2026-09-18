-- T20 实测修正：笔记/测验触发点把单元 id（约 160 字符）写进 user_actions.ref_id，
-- VARCHAR(128) 装不下（运行时实测 Data too long）。与 V2 同源：T4 的单元 id 含 filePath。
ALTER TABLE user_actions MODIFY COLUMN ref_id VARCHAR(512);
