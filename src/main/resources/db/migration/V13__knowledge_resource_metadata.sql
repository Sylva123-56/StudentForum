-- 兼容已执行旧版 V9 的数据库：资源元数据字段曾在迁移执行后补充到脚本，Flyway 不会重跑旧版本。
SET @textbook_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'resources' AND column_name = 'textbook');
SET @textbook_sql = IF(@textbook_exists = 0, 'ALTER TABLE resources ADD COLUMN textbook VARCHAR(80) NOT NULL DEFAULT '''' AFTER grade', 'SELECT 1');
PREPARE textbook_stmt FROM @textbook_sql;
EXECUTE textbook_stmt;
DEALLOCATE PREPARE textbook_stmt;

SET @exam_exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'resources' AND column_name = 'exam');
SET @exam_sql = IF(@exam_exists = 0, 'ALTER TABLE resources ADD COLUMN exam VARCHAR(80) NOT NULL DEFAULT '''' AFTER textbook', 'SELECT 1');
PREPARE exam_stmt FROM @exam_sql;
EXECUTE exam_stmt;
DEALLOCATE PREPARE exam_stmt;
