# 同频学习社区（MVP）

按《学生学习论坛 MVP 项目设计文档.md》与《技术方案.md》实现的 Vue 3 + Spring Boot + MySQL 单体应用。第一版覆盖注册登录、学生资料、板块、发帖回帖、图片、标签搜索、收藏、通知、积分、采纳、精华与举报治理。后台提供概览、举报处理、帖子管理、用户状态与积分、添加板块标签和公告入口。

## 本地运行

需要 Node.js 20+、Java 21、Maven 和 MySQL 8。创建一个可建库的 MySQL 账号，设置 `DB_USER`、`DB_PASSWORD`，可选 `DB_URL`（默认 localhost:3306/student_forum）和 `UPLOAD_DIR`。首次启动时 Flyway 自动建表和初始化板块标签。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:DB_USER='root'
$env:DB_PASSWORD='你的本地密码'
$env:DEMO_PASSWORD='仅用于本地演示的安全密码'
mvn spring-boot:run
```

另开终端：

```powershell
npm install
npm run dev
```

打开 http://127.0.0.1:5173 。Vite 将 `/api` 与 `/uploads` 代理至 8080。设置 `DEMO_PASSWORD` 时，首次启动会创建 `student@example.test`、`helper@example.test`、`moderator@example.test`（数学板块）和 `admin@example.test`，共用指定密码。公开环境请勿设置演示密码。

## 当前边界

这是可演示的初版，不应直接公开运营。尚需完善完整后台 CRUD、通知分页、账号登录限流、严格图片解码与清理、敏感词配置、备份与合规页面。公开部署必须使用 HTTPS、持久化上传目录并设置 `COOKIE_SECURE=true`，同源提供前端与 API。
