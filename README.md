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

这是可演示的初版，不应直接公开运营。帖子和通知每页展示 20 条；帖子搜索按标题关键词模糊匹配。后台除概览和系统公告外均按每页 20 条分页，并支持模糊搜索：举报按举报人昵称或原因，内容按帖子标题，用户按昵称，板块按名称或标识，操作记录按操作类型或详情，申诉与信誉分按用户昵称。后台支持板块与标签的新增、编辑、停用与恢复，板块和标签采用停用而非物理删除，历史关联会保留；管理员可在用户管理中设置角色，版主必须指定负责的板块，且系统至少保留一位管理员。尚需完善账号登录限流、严格图片解码与清理、敏感词配置、备份与合规页面。公开部署必须使用 HTTPS、持久化上传目录并设置 `COOKIE_SECURE=true`，同源提供前端与 API。

## V2 P1-03：知识沉淀

- 资料库：`/resources`；上传图片、PDF、Office、TXT、ZIP（25MB/文件，最多10个），学科/年级/教材/考试筛选、标签搜索、收藏点赞评论举报、下载记录。资料默认待审核，管理员发布或下架。首次下载扣积分，同一用户重复下载不再扣分；上传者免费。
- 错题本：`/mistakes`；题目、答案、错因、知识点、标签、来源和图片；默认私有，支持公开或有效小组共享。记录复习结果、自动安排下次复习，按学科/知识点统计错误率，可选择错题生成现有学习计划。帖子详情可一键进入记录错题，并关联原讨论。
- 笔记：`/notes`；创建、编辑、草稿/发布、作者可查看版本历史。支持现有安全 Markdown 文本渲染。
- 合集：`/collections`；收录已发布帖子、资料、笔记，编辑封面/简介和条目排序，关注、收藏、复制分享链接。管理员推荐合集进入首页/精华区；原条目下架后不再展示。
- 专栏：`/columns`；创建专栏、按期连载、草稿/发布、编辑已写期次，支持从自己的已发布笔记导入正文快照。
- Wiki：`/wiki` 或 `/wiki/:boardId`；板块版主/管理员维护，支持搜索正文/标签、关联帖子、版本历史、草稿与发布。更新需提交最新 `baseRevisionId`，过期版本返回409，避免协作覆盖。
- 管理入口：`/admin/resources`、`/admin/wiki`、`/admin/collections`；资料/合集仅管理员治理，Wiki 按版主所属板块授权；审核/下架写入操作记录。资料举报接入原后台举报队列。

### 数据迁移与文件存储

启动后由 Flyway 自动执行 V9–V14，不需要手工建表。备份后再对正式数据库启动升级。
资料和错题图片保存在 `knowledge-files/`，不在公开的 `uploads/` 中；可通过 `KNOWLEDGE_FILE_DIR` 环境变量配置目录。该目录必须与公开静态上传目录分开，部署时同时备份数据库与这个目录。
资料通过已授权下载接口提供文件；错题图片通过错题可见性校验提供，公开改私有或退出小组后原图片接口即失去访问权。

### 测试

`mvn test` 执行单元回归；真实 MySQL/HTTP 集成测试默认跳过。使用专用测试数据库运行：

```powershell
$env:KNOWLEDGE_INTEGRATION='true'
$env:KNOWLEDGE_TEST_DB_URL='jdbc:mysql://localhost:3306/student_forum_knowledge_test?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai'
$env:KNOWLEDGE_TEST_DB_USER='root'
$env:KNOWLEDGE_TEST_DB_PASSWORD='<测试数据库密码>'
mvn test
```

测试库名称须含 `knowledge_test`；测试会创建独立测试用户与内容，不清空原有业务数据。集成测试文件仅写入 `target/knowledge-test-files`。前端类型检查与打包使用 `npm run build`。
