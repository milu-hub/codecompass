-- T13 建库脚本：Flyway 管表、不管库。集成测试 / 部署前执行一次：
--
--   mysql -u root -p < backend/db/init-db.sql
--
-- 应用侧连接串（环境变量）：
--   DB_URL=jdbc:mysql://127.0.0.1:3306/codecompass?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8
--   DB_USER=codecompass
--   DB_PASSWORD=***

CREATE DATABASE IF NOT EXISTS codecompass
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
