-- 兼容早期资料库表结构。旧版 resources 使用 edition、file_path/file_name，
-- 旧版 resource_files 使用 path/name；当前接口使用 textbook 与拆分文件字段。
-- 所有补列都先允许 NULL，再统一回填并收紧约束，保证已有数据可升级。

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='textbook')=0, 'ALTER TABLE resources ADD COLUMN textbook VARCHAR(80) NULL AFTER grade', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='edition')=0, 'ALTER TABLE resources ADD COLUMN edition VARCHAR(80) NULL AFTER textbook', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='file_path')=0, 'ALTER TABLE resources ADD COLUMN file_path VARCHAR(255) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='file_name')=0, 'ALTER TABLE resources ADD COLUMN file_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='file_size')=0, 'ALTER TABLE resources ADD COLUMN file_size BIGINT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='favorite_count')=0, 'ALTER TABLE resources ADD COLUMN favorite_count INT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='like_count')=0, 'ALTER TABLE resources ADD COLUMN like_count INT NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='reject_reason')=0, 'ALTER TABLE resources ADD COLUMN reject_reason VARCHAR(300) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resources' AND column_name='updated_at')=0, 'ALTER TABLE resources ADD COLUMN updated_at DATETIME NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE resources SET textbook=COALESCE(NULLIF(textbook,''),NULLIF(edition,''),''), edition=COALESCE(edition,''), file_path=COALESCE(file_path,''), file_name=COALESCE(file_name,''), file_size=COALESCE(file_size,0), favorite_count=COALESCE(favorite_count,0), like_count=COALESCE(like_count,0), reject_reason=COALESCE(reject_reason,''), updated_at=COALESCE(updated_at,created_at);
ALTER TABLE resources MODIFY description TEXT NOT NULL, MODIFY tags VARCHAR(500) NOT NULL DEFAULT '', MODIFY source VARCHAR(255) NOT NULL DEFAULT '', MODIFY exam VARCHAR(80) NOT NULL DEFAULT '', MODIFY textbook VARCHAR(80) NOT NULL DEFAULT '', MODIFY edition VARCHAR(80) NOT NULL DEFAULT '', MODIFY file_path VARCHAR(255) NOT NULL DEFAULT '', MODIFY file_name VARCHAR(255) NOT NULL DEFAULT '', MODIFY file_size BIGINT NOT NULL DEFAULT 0, MODIFY favorite_count INT NOT NULL DEFAULT 0, MODIFY like_count INT NOT NULL DEFAULT 0, MODIFY status VARCHAR(20) NOT NULL DEFAULT 'pending', MODIFY reject_reason VARCHAR(300) NOT NULL DEFAULT '', MODIFY updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_files' AND column_name='path')=0, 'ALTER TABLE resource_files ADD COLUMN path VARCHAR(255) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_files' AND column_name='name')=0, 'ALTER TABLE resource_files ADD COLUMN name VARCHAR(160) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_files' AND column_name='file_name')=0, 'ALTER TABLE resource_files ADD COLUMN file_name VARCHAR(255) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_files' AND column_name='file_path')=0, 'ALTER TABLE resource_files ADD COLUMN file_path VARCHAR(255) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
SET @sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='resource_files' AND column_name='mime_type')=0, 'ALTER TABLE resource_files ADD COLUMN mime_type VARCHAR(120) NULL', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

UPDATE resource_files SET file_name=COALESCE(NULLIF(file_name,''),NULLIF(name,''),'file'), file_path=COALESCE(NULLIF(file_path,''),NULLIF(path,''),''), mime_type=COALESCE(NULLIF(mime_type,''),'application/octet-stream'), name=COALESCE(name,''), path=COALESCE(path,'');
ALTER TABLE resource_files MODIFY path VARCHAR(255) NOT NULL DEFAULT '', MODIFY name VARCHAR(160) NOT NULL DEFAULT '', MODIFY file_name VARCHAR(255) NOT NULL DEFAULT '', MODIFY file_path VARCHAR(255) NOT NULL DEFAULT '', MODIFY mime_type VARCHAR(120) NOT NULL DEFAULT 'application/octet-stream';

-- 将旧版 resources 单文件字段转换为拆分文件记录；已有拆分记录不会重复插入。
INSERT INTO resource_files(resource_id,path,name,size_bytes,file_path,file_name,mime_type)
SELECT r.id,r.file_path,r.file_name,r.file_size,r.file_path,r.file_name,'application/octet-stream'
FROM resources r
WHERE r.file_path<>'' AND NOT EXISTS (SELECT 1 FROM resource_files f WHERE f.resource_id=r.id);

CREATE TABLE IF NOT EXISTS resource_likes (resource_id BIGINT NOT NULL,user_id BIGINT NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(resource_id,user_id));

-- 旧版本使用 resource_likes，统一迁移到当前点赞表；重复数据安全忽略。
INSERT IGNORE INTO resource_votes(resource_id,user_id,created_at)
SELECT resource_id,user_id,created_at FROM resource_likes;
