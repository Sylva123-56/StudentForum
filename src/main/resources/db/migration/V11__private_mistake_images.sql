-- 错题图片不放入公开静态 uploads 目录；跟随错题可见性授权。
CREATE TABLE IF NOT EXISTS mistake_images (id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,stored_name VARCHAR(100) NOT NULL,mime_type VARCHAR(40) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,FOREIGN KEY(user_id) REFERENCES users(id),INDEX(user_id));
