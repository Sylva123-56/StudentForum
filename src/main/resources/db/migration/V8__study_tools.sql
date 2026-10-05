-- 学习工具：学习打卡 / 番茄钟 / 学习计划 / 自习室。
--
-- 四个功能共用一个迁移，因为它们的表和积分、徽章是同一套闭环：
--   打卡目标 -> 每日打卡 -> 连续天数 -> 积分 + 徽章
--   番茄钟专注 -> 专注时长 -> 积分 + 可一键转成打卡
--   学习计划 -> 每日任务 -> 勾选完成 -> 积分 + 徽章
--   自习室 -> 多人同时专注 -> 成员专注时长榜（时长全部由 focus_sessions 汇总，不另存冗余总数）
--
-- 徽章以前只是前端按积分数硬编码出来的"等级"（src/api.ts 的 level()），没有实体，
-- 谁在什么时候拿到了什么徽章都查不到。这里补一张 user_badges 作为唯一的徽章凭证，前端只负责展示。
-- 打卡积分规则（每目标每日积分、连续天数奖励、是否允许补卡）放在 checkin_settings 单行配置表里，
-- 由管理后台 /admin/checkin 修改，避免把规则写死在代码里。

CREATE TABLE IF NOT EXISTS checkin_goals (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL,
  subject VARCHAR(40) NOT NULL DEFAULT '',
  frequency VARCHAR(10) NOT NULL DEFAULT 'daily',
  target_minutes INT NOT NULL DEFAULT 0,
  visibility VARCHAR(10) NOT NULL DEFAULT 'private',
  group_id BIGINT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'active',
  allow_makeup BOOLEAN NOT NULL DEFAULT TRUE,
  reminder_time VARCHAR(5) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX(user_id,status), INDEX(group_id),
  FOREIGN KEY(user_id) REFERENCES users(id),
  FOREIGN KEY(group_id) REFERENCES study_groups(id)
);

-- 一个目标一天只能打一次卡：(goal_id,checkin_date) 唯一键就是这条规则的载体。
-- make_up 标记这一条是不是事后补的；补卡照样计入连续天数，但管理后台能据此区分。
CREATE TABLE IF NOT EXISTS checkins (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  goal_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL DEFAULT '',
  duration_minutes INT NOT NULL DEFAULT 0,
  subject VARCHAR(40) NOT NULL DEFAULT '',
  image_url VARCHAR(255) NULL,
  visibility VARCHAR(10) NOT NULL DEFAULT 'private',
  checkin_date DATE NOT NULL,
  make_up BOOLEAN NOT NULL DEFAULT FALSE,
  source VARCHAR(10) NOT NULL DEFAULT 'manual',
  plan_id BIGINT NULL,
  task_id BIGINT NULL,
  points INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(goal_id,checkin_date),
  INDEX(user_id,checkin_date), INDEX(plan_id),
  FOREIGN KEY(user_id) REFERENCES users(id),
  FOREIGN KEY(goal_id) REFERENCES checkin_goals(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS user_badges (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  code VARCHAR(40) NOT NULL,
  name VARCHAR(40) NOT NULL,
  detail VARCHAR(160) NOT NULL DEFAULT '',
  earned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id,code),
  INDEX(user_id,earned_at),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

-- 单行配置表：管理后台改这一行就等于改全站打卡/专注/计划的积分规则。seed 出 id=1，之后只做 UPDATE。
CREATE TABLE IF NOT EXISTS checkin_settings (
  id BIGINT PRIMARY KEY,
  checkin_points INT NOT NULL DEFAULT 2,
  streak_bonus INT NOT NULL DEFAULT 1,
  streak_bonus_days INT NOT NULL DEFAULT 7,
  focus_points INT NOT NULL DEFAULT 1,
  focus_daily_cap INT NOT NULL DEFAULT 10,
  plan_task_points INT NOT NULL DEFAULT 1,
  plan_task_daily_cap INT NOT NULL DEFAULT 10,
  plan_complete_points INT NOT NULL DEFAULT 10,
  allow_makeup BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
INSERT IGNORE INTO checkin_settings(id) VALUES (1);

-- 专注时长一律以分钟存成整数：番茄钟前端按秒倒计时，但落库时只记分钟，
-- 统计（每日/每周/每月、时段分布）全部由这一张表聚合出来，不再另存汇总表。
CREATE TABLE IF NOT EXISTS focus_sessions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  subject VARCHAR(40) NOT NULL DEFAULT '',
  task VARCHAR(120) NOT NULL DEFAULT '',
  note VARCHAR(255) NOT NULL DEFAULT '',
  duration_minutes INT NOT NULL,
  started_at DATETIME NOT NULL,
  ended_at DATETIME NOT NULL,
  room_id BIGINT NULL,
  plan_id BIGINT NULL,
  task_id BIGINT NULL,
  source VARCHAR(10) NOT NULL DEFAULT 'timer',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX(user_id,started_at),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS study_plans (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(80) NOT NULL,
  subject VARCHAR(40) NOT NULL DEFAULT '',
  goal VARCHAR(200) NOT NULL DEFAULT '',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'active',
  group_id BIGINT NULL,
  template_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX(user_id,status), INDEX(group_id),
  FOREIGN KEY(user_id) REFERENCES users(id),
  FOREIGN KEY(group_id) REFERENCES study_groups(id)
);

-- 任务拆解：parent_id 指向父任务，子任务不额外算进度（进度只数顶层任务，
-- 子任务用作"把一件大事拆小"的清单），避免父子都计入导致分母虚高。
CREATE TABLE IF NOT EXISTS study_tasks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id BIGINT NOT NULL,
  parent_id BIGINT NULL,
  title VARCHAR(120) NOT NULL,
  due_date DATE NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'pending',
  completed_at DATETIME NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX(plan_id,status), INDEX(parent_id),
  FOREIGN KEY(plan_id) REFERENCES study_plans(id) ON DELETE CASCADE,
  FOREIGN KEY(parent_id) REFERENCES study_tasks(id) ON DELETE CASCADE
);

-- 计划模板：owner_id 为空表示官方内置模板，谁都能用；否则是用户自己分享出来的。
-- use_count 只在"使用模板新建计划"时 +1，用来给模板列表排序。
CREATE TABLE IF NOT EXISTS plan_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_id BIGINT NULL,
  title VARCHAR(80) NOT NULL,
  subject VARCHAR(40) NOT NULL DEFAULT '',
  goal VARCHAR(200) NOT NULL DEFAULT '',
  cycle_days INT NOT NULL DEFAULT 7,
  tasks VARCHAR(2000) NOT NULL DEFAULT '',
  visibility VARCHAR(10) NOT NULL DEFAULT 'shared',
  use_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX(owner_id), INDEX(visibility,use_count),
  FOREIGN KEY(owner_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS study_rooms (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner_id BIGINT NOT NULL,
  name VARCHAR(60) NOT NULL,
  goal VARCHAR(120) NOT NULL DEFAULT '',
  subject VARCHAR(40) NOT NULL DEFAULT '',
  visibility VARCHAR(10) NOT NULL DEFAULT 'public',
  status VARCHAR(10) NOT NULL DEFAULT 'active',
  muted BOOLEAN NOT NULL DEFAULT FALSE,
  min_minutes INT NOT NULL DEFAULT 25,
  group_id BIGINT NULL,
  member_count INT NOT NULL DEFAULT 0,
  focus_minutes INT NOT NULL DEFAULT 0,
  closed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX(status,visibility), INDEX(owner_id), INDEX(group_id),
  FOREIGN KEY(owner_id) REFERENCES users(id),
  FOREIGN KEY(group_id) REFERENCES study_groups(id)
);

-- 成员行的 total_minutes 是本人累计专注（跨天累计，用于"我的自习室记录"），
-- 房间榜的今日/本周时长仍然实时从 focus_sessions 聚合，避免这里成为第二份真相。
CREATE TABLE IF NOT EXISTS study_room_members (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  role VARCHAR(10) NOT NULL DEFAULT 'member',
  total_minutes INT NOT NULL DEFAULT 0,
  joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_active_at DATETIME NULL,
  UNIQUE(room_id,user_id),
  INDEX(user_id),
  FOREIGN KEY(room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS study_room_messages (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  kind VARCHAR(12) NOT NULL DEFAULT 'chat',
  content VARCHAR(200) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX(room_id,id),
  FOREIGN KEY(room_id) REFERENCES study_rooms(id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

-- 内置模板：owner_id 为 NULL。tasks 用换行分隔，第一行是任务标题，前面加 "N|" 表示第 N 天（可省略）。
INSERT INTO plan_templates(owner_id,title,subject,goal,cycle_days,tasks)
SELECT NULL,'7 天英语单词冲刺','英语','30 天背完四六级核心词，先跑通第一个 7 天',7,
'1|背诵 50 个新词并复习昨日词表\n2|背诵 50 个新词 + 精读 1 篇真题\n3|背诵 50 个新词 + 整理错词本\n4|背诵 50 个新词 + 听力精听 20 分钟\n5|背诵 50 个新词 + 复习错词本\n6|背诵 50 个新词 + 限时阅读 2 篇\n7|本周词表总复习 + 自测 100 词'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT title FROM plan_templates) t WHERE t.title='7 天英语单词冲刺');

INSERT INTO plan_templates(owner_id,title,subject,goal,cycle_days,tasks)
SELECT NULL,'暑假编程刷题计划','编程','每天一题，两个月打牢算法基础',14,
'1|数组与双指针：完成 2 道简单题\n2|链表：完成 2 道简单题\n3|栈与队列：完成 2 道中等题\n4|哈希表：完成 2 道中等题\n5|二叉树遍历：完成 2 道中等题\n6|递归与回溯：完成 1 道中等题 + 复盘\n7|第一周总结：整理错题与模板\n8|二分查找：完成 2 道中等题\n9|排序：完成 2 道中等题\n10|动态规划入门：完成 1 道中等题\n11|贪心：完成 2 道中等题\n12|图论初步：完成 2 道中等题\n13|模拟面试：限时 2 题\n14|两周复盘：整理题解笔记'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM (SELECT title FROM plan_templates) t WHERE t.title='暑假编程刷题计划');
