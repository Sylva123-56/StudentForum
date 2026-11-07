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

## 后端代码阅读指南

Java 入口是 `src/main/java/com/studentforum/ForumApplication.java`，从这里向下阅读各包：

| 包 | 职责 | 阅读示例 |
| --- | --- | --- |
| `controller` | 声明 HTTP 路径、参数与响应；将请求交给业务类 | `ForumController`、`NoteController` |
| `service` | 校验输入、执行权限与业务流程；需要原子性的方法标注 `@Transactional` | `ForumQueryService`、`AccountAccessService`、`StudyRewardService` |
| `repository` / `mapper` | 保存查询和数据库读写；前者使用现有 `JdbcTemplate`，后者保留 MyBatis | `ForumRepository`、`BadgeRepository`、`NoteRepository`、`ForumMapper` |
| `model` | 表达业务概念和规则，而不负责 HTTP 参数绑定 | `ModerationScope`、`Post`、`StudyPlanPeriod`、`SignedInAccount` |
| `dto` | 表达接口输入或输出，字段应与现有 JSON 契约一致 | `AdminPostStatusRequest` |
| `config` / `schedule` / `exception` | 安全与序列化配置、定时发布、统一错误响应 | `SecurityConfig`、`DraftPublisher`、`ApiErrors` |

**跟一次请求：** `GET /api/notes` 先到 `NoteController.list` 绑定查询参数，再由 `NoteService.list` 检查访问条件与分页，由 `NoteRepository.search` 执行带参数的 SQL，最后按原有 `items` / `hasNext` 结构返回。写操作可以从 `NoteService.create` 继续追踪：同一事务内创建笔记与修订记录。

**再跟论坛和学习激励：** `GET /api/search` → `ForumController.search` → `ForumQueryService.search` → `ForumMapper.posts`。搜索仍返回原来的数组，查询 21 条为每页 20 条提供下一页判断；通知仍返回 `items` / `hasNext` / `unread`。论坛写操作由 `ForumService` 编排，补充 SQL 在 `ForumRepository`，提及通知在 `NotificationService` / `MentionRepository`，积分和信誉在 `PointService` / `ReputationRepository`。打卡、专注和任务奖励由 `StudyRewardService` 在同一事务内组合积分与 `BadgeService` 的徽章结算；`BadgeRepository` 只负责查询指标和记录已获徽章。

**学习工具的分层：** `POST /api/checkin` → `CheckinService` → `CheckinRepository`，专注记录/统计 → `FocusService` → `FocusRepository`；这两个业务服务不再持有 `JdbcTemplate` 或 SQL。共用规则配置由 `StudySettingsService` / `StudySettingsRepository` 处理，任务进度由 `StudyPlanProgressService` / `StudyPlanRepository` 处理，自习室可见性与总量刷新由 `StudyRoomAccessService` / `StudyRoomRepository` 处理，科目和已加入小组候选由 `StudyPreferencesService` / `StudyPreferencesRepository` 处理。权限、输入校验、响应组装仍留在 Service，Repository 不返回 HTTP 异常。

**学习计划模板：** 模板分类、创建、计划另存模板和删除由 `StudyPlanTemplateService` 负责；`StudyPlanService.createPlan/applyTemplateEndpoint` 仍持有生成计划的外层事务，模板任务展开复用同一权限校验并加入该事务。两个创建入口都不能使用他人的私有模板，失败不会留下半条计划或任务。

**知识模块的完整链路：** 笔记详情 `NoteService.detail` → `KnowledgeService.note` → `KnowledgeRepository.note`；共享服务负责可见性，仓库只查询投影。`KnowledgeService` 没有 JDBC、论坛发布服务或任意 SQL 入口。资料流程 `ResourceController` → `ResourceService` → `ResourceRepository` 保留私有文件访问、文件认领行锁和下载首次扣分事务；合集/专栏 `CollectionController` → `CollectionService` → `CollectionRepository` 保留条目计数、排序、关注计数和作者草稿权限。跨类型收录的可见性仍统一调用 `KnowledgeService.item`，不复制安全策略。

**事务与文件：** 帖子硬删除先处理数据库，上传文件仅在最外层事务提交后清理；如果事务回滚，仍被帖子引用的文件会保留。真实 MySQL 集成测试包含回滚与提交两条路径，不能只通过检查 `@Transactional` 注解来验证这种行为。

**对象与 DTO 不同：** `ModerationScope` 封装版主可见板块，`Post` 封装作者编辑与采纳规则，`StudyPlanPeriod` 校验计划日期；`AdminPostStatusRequest` 只负责接收 `{ "status": "hidden" }`。不必为每个数据表建立类，也不要为只有一个实现的 Service 强行添加接口。新增功能可先找到同领域 Controller，再沿 Service → Repository/Mapper 查找原有约束与 SQL。

**渐进迁移边界：** 现有接口、字段名、权限与数据库表不变。当前 Service 不再直接持有 JdbcTemplate 或编写 SQL，数据访问进入具名 Repository / MyBatis Mapper；但大量请求/响应仍保留 `Map<String, Object>`，长业务类和类型化继续按领域渐进整理，不一次性改写接口结构。权限直接复用 `AccountAccessService`，内容规则复用 `ContentRules`；`ForumService` 不再提供旧权限/校验转发，仍负责真实的论坛业务编排。

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

启动后由 Flyway 自动执行尚未应用的迁移，不需要手工建表。知识功能包含 V9–V14；新增 V15 将 `study_plans.end_date` 改为可空，使数据库与接口/前端原有的可选结束日期一致，保留已有日期，不改写历史迁移脚本。备份后再对正式数据库启动升级。
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

测试库名称须含 `knowledge_test`，必须是专用库；测试会创建独立测试用户与内容，并包含为兼容迁移构造旧表结构的用例，不能指向业务库。当前 16 项真实集成测试覆盖完整 Spring Bean 装配、真实 HTTP 的 Session/CSRF、用户/版主边界、私有文件访问、失败写入回滚、删帖文件的提交后清理，以及打卡奖励、专注与自习室总量联动、计划任务汇总/归档和可选结束日期。集成测试文件仅写入 `target/knowledge-test-files`。前端类型检查与打包使用 `npm run build`。
