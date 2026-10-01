-- 系统公告独立成表。
-- 原先一条公告就是往 notifications 里给每个用户各插一行（各自持有自己的已读状态），
-- 除了 message 文本之外没有任何字段能标识"这些行属于同一条公告"，
-- 于是管理后台既列不出历史公告，也没法编辑或删除其中某一条。
-- 这里把公告本身记进 announcements，并给 notifications 挂上 announcement_id：
-- 改一次内容可以精确命中它派生出的那批通知行，删一条公告则由外键级联清掉它们。

CREATE TABLE IF NOT EXISTS announcements (id BIGINT PRIMARY KEY AUTO_INCREMENT, admin_id BIGINT NOT NULL, message VARCHAR(255) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NULL, INDEX(created_at), INDEX(admin_id), FOREIGN KEY(admin_id) REFERENCES users(id));

-- notifications 增加公告外键列（幂等：列已存在就跳过）
SET @has = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notifications' AND COLUMN_NAME='announcement_id');
SET @sql = IF(@has=0,'ALTER TABLE notifications ADD COLUMN announcement_id BIGINT NULL AFTER post_id','SELECT 1'); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 外键：删公告时级联删掉它派生的通知行。约束名从 INFORMATION_SCHEMA 动态查出来，写法同 V6。
SET @fk = (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='notifications' AND COLUMN_NAME='announcement_id' AND REFERENCED_TABLE_NAME='announcements' LIMIT 1);
SET @sql = IF(@fk IS NULL,'SELECT 1',CONCAT('ALTER TABLE notifications DROP FOREIGN KEY `',@fk,'`')); PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
ALTER TABLE notifications ADD CONSTRAINT notifications_announcement_fk FOREIGN KEY(announcement_id) REFERENCES announcements(id) ON DELETE CASCADE;

-- 回填历史公告：老数据里同一条公告的若干行 message 相同，按 message 分组即可还原出公告本身；
-- 发布人从 admin_logs 的 announcement 记录里取，时间取该组最早的一条。
-- NOT EXISTS 里套一层派生表，避开 MySQL "不能在 INSERT 的子查询里读目标表" 的限制；已登记过的 message 跳过，可重复执行。
INSERT INTO announcements(admin_id,message,created_at)
SELECT COALESCE((SELECT l.actor_id FROM admin_logs l WHERE l.action='announcement' AND l.detail=n.message ORDER BY l.id LIMIT 1),(SELECT MIN(id) FROM users)),n.message,MIN(n.created_at)
FROM notifications n
WHERE n.kind='system' AND n.post_id IS NULL AND n.announcement_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM (SELECT message FROM announcements) a WHERE a.message=n.message)
GROUP BY n.message;

UPDATE notifications n JOIN announcements a ON a.message=n.message
SET n.announcement_id=a.id
WHERE n.kind='system' AND n.post_id IS NULL AND n.announcement_id IS NULL;
