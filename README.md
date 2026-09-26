# 同频学习社区

Vue 3 + Spring Boot + MySQL 学习社区。MVP 覆盖注册登录、资料、帖子回复、通知、积分、采纳与举报治理；V2 增加关注用户与标签、动态流、私信、投票、悬赏、草稿与定时发布、编辑历史、信誉分和申诉。

## 本地运行

需要 Node.js 20+、Java 21、Maven 和 MySQL 8。创建一个可建库的 MySQL 账号，设置 `DB_USER`、`DB_PASSWORD`，可选 `DB_URL`（默认 localhost:3306/student_forum）和 `UPLOAD_DIR`。首次启动时 Flyway 自动建表和初始化板块标签。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
$env:DB_USER='root'
$env:DB_PASSWORD='你的本地密码'
mvn spring-boot:run
```

另开终端：

```powershell
npm install
npm run dev
```

打开 http://127.0.0.1:5174 。Vite 将 `/api` 与 `/uploads` 代理至后端 8081 端口。设置 `DEMO_PASSWORD` 时，首次启动会创建 `student@example.test`、`helper@example.test`、`moderator@example.test`（数学板块）和 `admin@example.test`，共用指定密码。公开环境请勿设置演示密码。

## 当前边界

V2 使用 Flyway 的 `V2__social_content.sql` 自动升级现有数据库。私信默认仅接收自己关注的用户，登录后可在通知设置调整；信誉分低于 40 时不能发帖或私信。定时草稿每分钟检查一次，发布失败会保留草稿。长文正文支持标题（`#`）、围栏代码块和 `$$` 公式原文块；附件限 PDF/TXT/ZIP，每个最多 5MB。

这是可演示的初版，不应直接公开运营。帖子和通知每页展示 20 条；帖子搜索按标题关键词模糊匹配。尚需完善完整后台 CRUD、账号登录限流、严格图片解码与清理、敏感词配置、备份与合规页面。公开部署必须使用 HTTPS、持久化上传目录并设置 `COOKIE_SECURE=true`，同源提供前端与 API。
