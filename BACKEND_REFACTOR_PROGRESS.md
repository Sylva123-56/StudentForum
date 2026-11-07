# 后端重构进度记录

更新日期：2026-11-07

代码分支：`codex/rebuild`；按模块验证、提交并推送，最新提交以 `git log -1` 为准；历史提交与推送状态见第四节

目标：改善学习与维护体验，逐步形成 Controller → Service → Repository/Mapper → 数据库的分层结构。

> 当前状态：2026-11-07 已完成上一轮剩余的资料、合集/专栏和知识共享 SQL 下沉，并迁移调用方、删除论坛旧权限/校验转发。代码按模块测试、提交并推送。随后继续拆出学习计划模板、任务业务，统一两个创建入口的私有模板权限，并修复取消后重勾重复发任务积分。随后将小组帖子、文件业务与共用权限检查拆出，保持原有路由、响应和事务语义。最新全量 **299 项通过（含 31 项真实 MySQL + HTTP 集成测试），无失败、错误或跳过**。核心数据访问分层已贯通，但 Map 类型化、大业务类细分、所有并发边界和部署同版本 MySQL 8 验证尚未全部完成。

## 一、阶段成果与当前改动

### 1. 按职责建立包结构

Java 代码位于 `src/main/java/com/studentforum/`，应用入口 `ForumApplication` 仍保留在根包。

| 包 | 当前职责与示例 |
| --- | --- |
| `controller` | HTTP 路由、请求绑定与响应，例如 `AdminController`、`NoteController` |
| `service` | 业务流程、权限校验与事务，例如 `AccountService`、`PostEditService`、`NoteService` |
| `repository` | 使用现有 JdbcTemplate 的数据访问，例如 `GroupRepository`、`GroupAdminRepository`、`NoteRepository` |
| `mapper` | 保留 MyBatis 数据访问，目前为 `ForumMapper` |
| `model` | 业务对象与规则，例如 `Post`、`ModerationScope`、`StudyPlanPeriod`、`StudyStreak`、`SignedInAccount` |
| `dto` | 类型明确的接口数据，目前为 `AdminPostStatusRequest` |
| `config` | 安全、序列化、演示账号和静态上传资源配置 |
| `schedule` | 定时草稿发布任务 `DraftPublisher` |
| `exception` | 统一异常响应 `ApiErrors` |

包迁移时同步更新了导入、必要的跨包访问权限与现有测试调用。

### 2. 分离 HTTP 层与业务层

- 管理、小组及小组管理、打卡、专注、学习计划、自习室等模块已新增对应 Service，Controller 调用业务类处理请求。
- 收藏、错题及图片、笔记、资料、Wiki、社交与扩展内容等接口的主要业务逻辑已迁入 Service。
- 注册、登录、资料修改和密码修改迁入 `AccountService`；HTTP 会话创建与 SecurityContext 保存仍由 Controller 负责。
- 帖子编辑、删除迁入 `PostEditService`，这两个操作的事务边界随之移到 Service。
- 上传处理迁入 `UploadService`；静态资源映射独立为 `UploadResourcesConfig`。
- 其他已迁出的事务方法保留了事务注解；迁移过程中核对了原有路由注解，未主动修改 HTTP 路径。

**本轮补齐：** `ForumController` 已移除 `ForumMapper` 依赖。论坛只读接口进入 `ForumQueryService`；回复删除与收藏写入进入 `ForumService`；通知/公告进入 `NotificationService`；积分查询进入 `PointService`。请求路径、JSON 字段、分页大小及权限语义保持不变；认证会话仍由 HTTP 层处理。

### 3. 已下沉的数据库操作

| 模块 | 当前成果 | 仍需处理 |
| --- | --- | --- |
| 论坛 | `ForumService` 的全部 JDBC/SQL 移至 `ForumRepository`；提及与信誉读写移至 `MentionRepository` / `ReputationRepository` | 旧权限/内容规则转发已移除；保留真实论坛业务编排 |
| 徽章 | 指标查询、已获徽章查询及去重插入移至 `BadgeRepository` | 持续补充奖励与并发去重回归 |
| 打卡 | 全部 SQL 与查询构造进入 `CheckinRepository`，业务权限/校验留在 `CheckinService` | 继续补充补卡、奖励和并发边界回归 |
| 专注 | 全部 SQL 进入 `FocusRepository`，保持统计时间边界和 21 条预取分页；后台治理 SQL 已进入 `AdminStudyRepository` | 继续验证并发写入与房间总量联动 |
| 学习共享能力 | 规则配置、计划进度、自习室访问和科目候选分别移至专用 Service 与领域 Repository | `StudyToolService` 已删除 JDBC 及 `row/requiredRow` 入口，仅保留输入/日期/连续天数工具 |
| 学习计划、自习室及学习后台 | 计划/任务/模板、房间/成员/消息与后台统计治理分别进入 `StudyPlanRepository`、`StudyRoomRepository`、`AdminStudyRepository` | 继续整理过长业务方法、Map 与含糊命名 |
| 小组 | 检索、成员、帖子/回复、互动、文件、打卡和治理 SQL 全部进入 `GroupRepository` | `GroupService` 仍有多种业务职责，可按实际需要继续拆分 |
| 社交 | 关注、动态、通知设置、拉黑、私信与会话 SQL 全部进入 `SocialRepository`；直接依赖权限与内容规则 | 补充更多并发和消息边界回归 |
| 小组后台管理 | SQL 移至 `GroupAdminRepository`，业务权限及状态校验留在 `GroupAdminService` | 补充更多集成回归和审计行为验证 |
| 内容工作流 | 拆出投票、草稿、申诉及定时发布 Service，SQL 进入对应 Repository | 继续改善类型与业务方法可读性 |
| 笔记 | 列表、创建、更新、修订记录和编辑行锁移至 `NoteRepository` | 详情读取与可见性经 `KnowledgeService.note` → `KnowledgeRepository.note`，共享服务不再持有 SQL |
| 错题及图片 | 本批列表、写入、复习、统计、复习计划和图片元数据 SQL 移至 `MistakeRepository`、`MistakeImageRepository` | 详情、成员查询经 `KnowledgeService` → `KnowledgeRepository`，可见性仍由 Service 决定 |
| Wiki | 本批列表、编辑行锁、修订和治理查询移至 `WikiRepository`，显式注入 `AccountAccessService` | 审计经 `KnowledgeRepository` 写入，共享校验/可见性经无 SQL 的 `KnowledgeService` |
| 资料 | 列表、文件认领、下载扣分、互动和治理 SQL 已进入 `ResourceRepository`，显式依赖 `AccountAccessService` | 详情与审计经 `KnowledgeService` → `KnowledgeRepository`；文件与权限规则保持在 Service |
| 合集/专栏 | 检索、增改、条目排序/计数、关注收藏及连载与治理 SQL 已迁入 `CollectionRepository` | 详情查询已下沉；可见性与目标内容检查由 `KnowledgeService` 共享 |
| 知识共享服务 | 详情投影、有效小组成员与审计 SQL 进入 `KnowledgeRepository`；共享服务只保留可见性与校验，显式依赖账户权限与 `ContentRules` | 继续按实际业务需要类型化和细化职责 |

这里的“下沉”指按业务含义建立 Repository 方法，而不是将 JdbcTemplate 再包装成一个接收任意 SQL 的通用工具。

### 4. 已引入的对象与类型

- `ModerationScope`：封装管理员与版主的板块可见范围。
- `Post`：封装作者归属、可编辑状态与问答采纳权限。
- `StudyPlanPeriod`：封装学习计划日期范围判断。
- `StudyStreak`：封装连续打卡天数计算。
- `SignedInAccount`：承载认证后的账号信息，供 Controller 建立会话；它不负责操作 HTTP 会话。
- `AdminPostStatusRequest`：明确帖子状态修改请求的字段，仍接收原有 `status` JSON 字段。

这些类型已经用于业务调用，不是只创建了空类；但核心流程仍大量使用 Map，类型化工作只是开始。

### 5. 测试与学习文档

- 更新原有测试以适配包迁移和新的构造依赖。
- 新增账户规则、版主板块范围、帖子与计划对象、帖子编辑权限、Repository 参数绑定、笔记编辑行锁等回归测试。
- 新增小组排行 HTTP 响应及管理员状态请求 JSON 绑定测试。
- README 已加入包职责、笔记请求调用链以及业务对象与 DTO 的区别说明。

以下第 6–10 项保留各阶段当时的状态和测试数量；当前整体状态以第三、四节为准。

### 6. 2026-11-02 第一轮成果（历史阶段）

- 新增 `AccountAccessService`，集中身份、可写状态、管理员与版主板块权限。账户、论坛查询、通知、积分、小组、打卡、专注、学习计划、自习室、学习后台及上传模块按明确职责注入，不再为这些权限规则依赖论坛发布服务。
- 新增 `PointService`、`NotificationService`、`BadgeService` 和 `StudyRewardService`；注册/登录、小组与私信提及、信誉调整及学习激励改为直接调用对应服务。`ForumService` 已移除积分与信誉的旧转发入口。
- `StudyToolService` 不再依赖 `ForumService`，也不再持有积分、通知、徽章结算；从 375 行缩至 259 行，但设置、计划进度和自习室查询仍待继续拆分。
- 提取纯校验规则 `ContentRules`，复用原有文本、图片和附件路径约束，不引入额外框架或依赖。
- 修复删帖先清理文件、后续数据库回滚时可能丢文件的问题：仅在最外层事务提交后清理帖子上传文件。
- 举报对象的作者查找改为单一显式分支，避免两处重复的长嵌套三元表达式；缺失的消息举报对象返回 404 而不是索引异常。
- 新增论坛 HTTP 契约、提及隐私与去重、徽章阈值和目录、账户权限、积分流水、Repository 参数与清理顺序、学习激励编排回归。
- 在现有真实集成测试上新增 CSRF 拒绝、Session 轮换/失效、帖子与回复写入回滚、删帖外层事务回滚/提交、跨用户和版主操作边界用例。

### 7. 2026-11-02 第二轮成果（历史阶段）

- `CheckinService`、`FocusService` 已不再持有 `JdbcTemplate`、拼接 SQL 或调用 `tools.row/requiredRow`；读写通过具名 `CheckinRepository`、`FocusRepository` 方法完成。
- `StudySettingsService` / `StudySettingsRepository` 集中规则配置的默认值、读取与白名单更新；后台只提交已校验的配置值，不再拼接更新语句。
- `StudyPlanProgressService` / `StudyPlanRepository` 集中顶层任务进度、父任务汇总与计划状态同步，保留归档语义，写入仍在事务内。
- `StudyRoomAccessService` / `StudyRoomRepository` 集中私密房间的房主/成员访问策略，以及依据成员表和专注记录刷新房间总量。
- `StudyPreferencesService` / `StudyPreferencesRepository` 集中已加入小组与科目候选读取，保留顺序、去重和 20 项上限。
- `StudyToolService` 从上一轮的 259 行缩至 **108 行**，只保留输入/日期/连续天数工具和旧 `row/requiredRow` 兼容入口；后者仅供尚未完成的学习计划、自习室和学习后台调用，不能当作已完成的数据访问分层。
- 真实数据库验证发现原有计划接口/前端允许省略结束日期，但旧数据库 `end_date NOT NULL` 导致 500；新增 **V15** 将该列改为可空，保留现有数据，未修改 V1–V14 的历史脚本。
- 新增配置默认值/更新白名单、打卡查询范围与绑定、专注时间边界和分页、计划进度/归档、房间权限、科目候选等单元回归；新增 3 项真实 HTTP 学习流程回归。

### 8. 2026-11-05 学习领域数据访问迁移

- 计划 CRUD、任务、模板、自动打卡相关 SQL 全部进入具名 `StudyPlanRepository` 方法；动态筛选与模板可见性查询也在 Repository 内构造，Service 保留权限、参数校验和事务编排。
- 自习室 CRUD、成员、消息、排行与学习后台统计/治理分别进入 `StudyRoomRepository`、`AdminStudyRepository`。
- 删除 `StudyToolService` 的 JDBC 依赖和 `row/requiredRow` 任意 SQL 入口，仅保留输入/日期/连续天数工具。
- 新增 6 项 Repository 参数与范围回归、1 项真实 HTTP 模板/消息/治理回归；全部 **161 项测试（含 17 项独立数据库集成测试）通过，无跳过**。保留原有路由和安全配置，没有为满足测试开放新权限。
- 首批成果为 `95458d2`；本阶段学习领域成果为 `17e2218`，均已有本地推送成功记录。

### 9. 2026-11-05 小组与社交领域

- 小组成员、加入申请、帖子/回复/点赞/收藏、公告、资料、打卡和治理 SQL 全部迁入现有 `GroupRepository`，游客/登录态差异查询在 Repository 构造。
- 社交关注、动态、通知设置、拉黑、私信与会话 SQL 全部迁入 `SocialRepository`；`SocialService` 改为直接依赖账户权限与内容规则，不再依赖论坛发布服务。
- 小组与私信新增主键通过 JDBC GeneratedKeyHolder 获取，避免依赖连接池中上一连接的 LAST_INSERT_ID。
- 新增 5 项查询参数回归和 2 项真实 HTTP 回归；全部 **168 项测试（含 19 项独立数据库集成测试）通过，无跳过**。
- 本阶段成果为 `8d28c64`，已有本地推送成功记录。

### 10. 2026-11-05 内容工作流拆分

- 原 `ContentWorkflowService` 从 354 行缩至 97 行，仅保留增强发布与修订业务；投票/悬赏、草稿、申诉/信誉分别进入 `VoteService`、`DraftService`、`AppealService`，Controller 直接注入对应职责。
- SQL 进入 `VoteRepository`、`DraftRepository`、`AppealRepository` 和 `ContentRevisionRepository`；权限直接调用 `AccountAccessService`，纯内容校验复用 `ContentRules`。
- 定时器仅触发 `ScheduledDraftService`，业务服务保留逐草稿事务及失败隔离，SQL 共用 `DraftRepository`。
- 新增 6 项仓库回归和 2 项真实事务回归，验证增强发布跨服务回滚、投票结果隐私/重复投票、草稿越权、定时发布失败回滚且不中断后续草稿。全部 **176 项测试（含 21 项独立数据库集成测试）通过，无跳过**；定时失败用例中的 WARN 属于预期注入的失败。
- 本阶段成果已提交为 `a021ef2`，并已推送（远端从 `8d28c64` 更新至 `a021ef2`）。

### 11. 2026-11-05 错题、图片与 Wiki

- 新增 `MistakeRepository`，承接错题筛选、写入、复习记录、行锁、统计及复习计划/任务插入；新增 `MistakeImageRepository`，承接图片元数据与图片归属查询。
- 新增 `WikiRepository`，承接板块、页面、修订与治理查询；错题、图片、Wiki Service 显式注入 `AccountAccessService`，替换原有直接访问 `k.forum` 的调用。
- 保留既有私有图片访问策略与事务回滚清理、错题复习行锁、Wiki 板块管理范围、修订冲突 409 和发布可见性。Wiki 审计仍走 `KnowledgeService.audit`，错题详情等仍有共享服务 SQL，不能视为知识领域完整下沉。
- `KnowledgeService` 新增接收查询结果 Map 的 `required` 重载，保持原有 404 提示和复制返回值语义；原 JDBC、通用 SQL 入口与其他知识模块的调用仍保留。
- 新增 `KnowledgeRepositoryTest` 的 5 项回归，覆盖有效小组成员筛选、公开搜索参数、错题/Wiki 行锁、图片归属和版主板块范围；同步调整 `KnowledgeServiceTest` 的构造依赖。
- 最近一次全量报告为 **181 项全部通过，0 失败、0 错误、0 跳过，含 21 项独立数据库集成测试**。本批新增 5 项均为单元测试，集成测试数量未增加。
- 提交前重跑全量测试通过后，本批代码已提交为 `5a73798` 并推送；`git ls-remote` 确认远端分支指向该提交。

### 12. 2026-11-07 资料数据访问迁移

- 新增 `ResourceRepository`：资料搜索、上传元数据、文件认领、评论/收藏/点赞、下载与扣分日志、举报及后台治理均使用具名方法；`ResourceService` 不再调用 SQL 或穿透 `k.db` / `k.forum`。
- 保留资料下载行锁、首次扣费与重复下载免费语义、积分不足校验、文件归属行锁、路径校验、回滚文件清理和审计事务。
- 新增 6 项仓库回归和 1 项真实 HTTP 回归；后一用例确认创建资料时第二个文件越权会回滚资料行、第一个文件的认领和关联文件行。
- Java 21 + 专用 MySQL 26.7 实例上重跑 `mvn -q test`，**188 项通过，含 22 项集成测试，无失败、错误或跳过**；日志 `target/resource-full-test.log`。未触碰正常业务库，MySQL 8 验证边界仍保留。

### 13. 2026-11-07 合集与专栏数据访问迁移

- 新增 `CollectionRepository`，承接列表筛选、条目增改/排序与计数、关注/收藏、连载、行锁及后台推荐；`CollectionService` 显式依赖账户权限和 `ContentRules`，不再持有 SQL 或访问 `k.db` / `k.forum`。
- 合集/专栏表名限定为两个已支持选项，收藏和关注的用户条件改为参数绑定；保留公开/作者查询差异、21 条预取分页、草稿仅作者可见、父行锁及事务。
- 新增 6 项仓库回归和 1 项真实 HTTP 回归，覆盖跨合集条目隔离、排序、重复关注计数、取消关注与删除重计数、专栏草稿可见性和越权修改。
- 全量 **195 项通过，含 23 项集成测试，无失败、错误或跳过**；日志 `target/collection-full-test.log`。

### 14. 2026-11-07 知识共享服务边界

- 新增 `KnowledgeRepository` 承接资料、错题、笔记、合集/专栏的详情投影、已发布帖子查询、有效小组成员查询及审计写入；仅提供具名业务方法。
- `KnowledgeService` 删除 JDBC、`ForumService` 依赖及 `row(String)` / `required(String)` / `insert(String)` 通用 SQL 入口，直接注入 `AccountAccessService` 并使用 `ContentRules`。保留原有可见性、404/403/400 和复制结果语义。
- `NoteService` 直接依赖账户权限，错题、图片、Wiki、资料和合集/专栏的共享依赖统一命名为 `knowledge`，不再访问其他 Service 的字段。
- 新增 9 项回归：共享查询绑定、有效成员约束、审计参数顺序、结果复制、匿名短路、管理员/封禁/版主范围、停用合集与专栏差异及共享服务依赖边界。
- 全量 **204 项通过，含 23 项集成测试，无失败、错误或跳过**；日志 `target/knowledge-boundary-full-test.log`。

### 15. 2026-11-07 论坛权限/校验转发收尾

- `AdminService`、`GroupAdminService`、`PostEditService` 直接依赖 `AccountAccessService`；帖子编辑和论坛业务直接使用 `ContentRules`。`ForumService` 删除 `id/current/writable/moderate/admin/staff/text/image/attachment` 九个公开兼容转发方法。
- `GET /api/me` 进入账户领域的 `AccountService.current`；帖子修订、提及、删除与后台论坛治理仍调用真实的论坛业务，不把这些流程误迁成权限工具。
- 更新全部构造调用及测试，新增 4 项回归覆盖帖子内容校验、已删除帖子/非作者拒绝、小组后台权限失败短路和 `/api/me` 字段契约。
- 全量 **208 项通过，含 23 项集成测试，无失败、错误或跳过**；日志 `target/forum-boundary-full-test.log`。静态搜索确认 Service 中无 JdbcTemplate 或直接 SQL，知识模块无 `k.db` / `k.forum` 字段穿透。

### 16. 2026-11-07 学习计划模板职责拆分

- 新增 `StudyPlanTemplateService`，承接模板分类列表、创建、计划另存模板、删除及模板任务展开；Controller 直接调用模板服务，不保留原 Service 转发方法。
- `StudyPlanService` 仍编排创建计划与套用模板生成计划的完整事务；任务展开加入外层事务，无双向 Service 依赖，不新增 SQL、表或框架。
- 保留模板 JSON 字段、默认值、本人计划 404、非本人/官方模板删除 403、相对到期日/截止日截断、只保存根任务等现有行为。
- 修复原有权限遗漏：`POST /api/plans` 携带 `templateId` 不再绕过私有模板校验，与 `/templates/{id}/apply` 统一返回 400；验证拒绝访问和后续任务校验失败均回滚已创建计划及模板任务。这是本批唯一有意收紧的权限行为。
- 新增 13 项模板单元测试、3 项 HTTP 契约测试和 2 项真实集成测试；全量 **226 项通过，其中集成 25 项，无失败、错误或跳过**。日志：`target/plan-template-full-test.log`。

### 17. 2026-11-07 学习计划任务职责拆分

- 新增 `StudyPlanTaskService`，承接任务新增、修改、删除、父任务汇总、首次任务/计划奖励与可选打卡；Controller 的三个任务接口直接调用任务服务，原 Service 不保留转发。
- 三个任务写操作保留原 `@Transactional` 外层边界，进度和奖励服务加入同一事务；计划详情继续复用 `StudyPlanService.plan`。学习计划主服务从本轮开始时的 465 行缩至 246 行，去掉奖励和设置依赖，无双向依赖。
- 保留计划/任务归属 404、一层子任务限制、父任务不可直接勾选、归档状态不被覆盖、响应 `pointsAwarded` / `badges` / `checkin`、目标匹配与每日打卡去重。
- 真实测试暴露旧实现的积分漏洞：任务取消后重勾仍会再次发放任务积分。本批新增具名 `StudyPlanRepository.taskReward`，按用户、动作和任务引用查询既有奖励；已奖励任务不再重复发分，但仍允许进度更新与可选打卡。不改表结构，不更改共用积分服务的其他奖励策略。
- 新增 15 项任务单元测试、1 项 Repository 参数绑定测试、1 项 HTTP 路由契约测试及 2 项真实集成测试；验证非法状态使先前标题/日期更新回滚、跨计划/跨用户隔离、任务积分与完成计划奖励去重、自动打卡隐私和来源关联。全量 **245 项通过，其中集成 27 项，无失败、错误或跳过**。日志：`target/plan-task-full-test.log`。
- 此处积分去重已验证串行重复请求，不代表并发完成的奖励唯一性和每日上限均已专项验证；并发边界仍在待办。

### 18. 2026-11-07 小组帖子职责与权限边界拆分

- 新增 `GroupPostService`，承接帖子列表/详情、创建、回复、点赞/收藏切换及管理操作；Controller 的七个帖子接口直接调用，不保留旧 Service 转发方法。
- 原有小组查找、成员身份、私密小组可见性与组长/管理员检查集中到 `GroupAccessService`，原服务与帖子服务共同使用；无双向依赖，不复制权限策略。
- `GroupService.detail` 继续组装原有八个响应字段，帖子摘要仍限 20 条；列表 100 条、详情检索 1000 条及原有排序/过滤规则保持不变。主服务由 529 行缩至 417 行，移除提醒服务依赖。
- 保留非成员写入/私密读取 403、跨组或隐藏帖子 404、回复提及通知、活动计数、点赞/收藏个人标记及管理审计。不改 SQL、表结构或原来未声明事务的边界，不借拆分扩大功能。
- 新增 9 项共用权限单元测试、15 项帖子/详情组装单元测试、3 项 HTTP 契约测试和 2 项真实集成测试。全量 **274 项通过，其中集成 29 项，0 失败、0 错误、0 跳过**；日志：`target/group-post-full-test.log`。
- 保留原详情在最近 1000 条已发布帖子中查找的限制；并发切换、跨步骤失败原子性和批量通知不是本批专项验证范围。

### 19. 2026-11-07 小组文件职责拆分

- 新增 `GroupFileService`，承接文件列表、元数据登记、Multipart 上传及删除；Controller 的四个文件接口直接调用，权限复用 `GroupAccessService`，不保留旧 Service 转发。
- 上传目录配置、文件名清理、后缀白名单/JPEG 归一化、25,000,000 字节上限和存储清理集中到文件服务；小组主服务不再依赖文件系统、Multipart 或上传目录配置，由 417 行进一步缩至 341 行。
- 保留上传与登记分两次请求、元数据默认大小/下载标记、私密列表 403、上传者或组内管理员删除、按小组查找资料 404、原有审计及受限路径清理规则。不改 SQL、表、接口字段、错误码或原有事务边界。
- 新增 20 项单元测试、3 项 HTTP 契约测试和 2 项真实集成测试；覆盖扩展名/大小边界、账户写入限制、非成员拒绝、跨组删除、上传者/管理员权限、真实字节存储与删除、旧 UUID 路径及非白名单路径不清理。全量 **299 项通过，其中集成 31 项，0 失败、0 错误、0 跳过**；日志：`target/group-file-full-test.log`。
- 文件清理继续沿用原来的尽力而为：磁盘删除失败不撤销元数据删除；上传后未登记的文件回收、文件/数据库失败原子性和并发删除仍待专项处理，本批不宣称已解决。

## 二、尚未完成的部分

本轮已完成前一版本中资料、合集/专栏、知识共享 SQL 下沉及论坛旧权限/校验转发两项高优先级工作。后续不再重复迁移已经下沉的数据访问。

| 优先级 | 待办 | 完成标准 |
| --- | --- | --- |
| 中 | 继续拆分过大的业务类 | 学习计划模板、任务与小组帖子/文件已拆出；继续拆分 `GroupService` 的成员、加入申请等独立业务，不为分层新增空接口 |
| 中 | 渐进替换 Map 与含糊命名 | 从稳定请求、响应与核心状态开始，保持 JSON 字段和错误语义；知识模块的 `k` 已统一为 `knowledge` |
| 中 | 改善过长业务方法可读性 | 分阶段展开压缩写法、拆分复杂分支；本轮已展开共享可见性规则，但未宣称全项目完成 |
| 中 | 补齐验证边界 | 在与部署一致的 MySQL 8 专用实例重跑；补充更多并发、文件/数据库联动和浏览器流程覆盖 |
| 低 | 持续更新学习指南 | 阅读指南和当前调用链保持一致，每批验证后提交并推送 |

Service 负责权限、输入、可见性与事务编排，Repository/Mapper 负责具名查询与写入。`KnowledgeService` 保留跨知识类型的共享规则，但不再暴露数据库或其他服务实例，也不接收任意 SQL。核心链路完成不等于所有 Map、并发路径或部署兼容性都已验证。

## 三、验证记录与边界

### 最近一次代码验证（2026-11-07，小组文件职责拆分）

- 每批变更后执行 `mvn -q test` 并启用 `KNOWLEDGE_INTEGRATION=true`；最后一次退出码 0。汇总 `target/surefire-reports/TEST-*.xml`：**299 项通过，0 失败、0 错误、0 跳过**，其中 `KnowledgeIntegrationTest` **31 项**。
- Java **21.0.5**，路径 `C:\Program Files\Java\jdk-21`；专用连接为 `jdbc:mysql://127.0.0.1:3308/student_forum_knowledge_test_refactor`，通过 `KNOWLEDGE_TEST_DB_URL` 指定，保留库名包含 `knowledge_test` 的保护。未启用集成开关时，这组集成测试按设计跳过。
- 独立实例为 MySQL **26.7**，数据目录 `target/refactor-mysql`，本轮仅为回归重启此实例，测试完成后已核对父进程、数据目录及端口，仅关闭本轮启动的独立实例；未使用或修改正常业务数据库。该版本不替代部署 MySQL 8 的兼容性验证。
- 前四批日志分别为 `target/resource-full-test.log`、`target/collection-full-test.log`、`target/knowledge-boundary-full-test.log`、`target/forum-boundary-full-test.log`；后续四批日志为 `target/plan-template-full-test.log`、`target/plan-task-full-test.log`、`target/group-post-full-test.log`、`target/group-file-full-test.log`。
- 31 项真实集成测试含前序 27 项及本轮新增的小组帖子回复/互动/计数、管理权限/跨组隔离、文件上传/登记/删除/磁盘清理回归。覆盖 Session/CSRF、账户与版主范围、私有文件、事务回滚/提交后清理、学习联动、小组和社交隐私、增强发布及定时草稿失败隔离。定时发布失败注入的 WARN/堆栈为预期，最终测试报告无失败。
- `git diff --check` 作为额外格式检查；不替代业务测试。本轮未修改前端，未重跑前端构建或完整浏览器端到端流程。

### 历史验证结果

| 阶段 | 测试总数 | 集成测试数 | 记录说明 |
| --- | --- | --- | --- |
| 旧基线 | 98 | 未单独记录 | 89 通过、9 跳过 |
| 前序阶段 | 132 | 未单独记录 | 全部通过 |
| 共享服务、论坛与打卡/专注 | 154 | 16 | 全部通过、无跳过；提交 `95458d2` |
| 学习领域 | 161 | 17 | 全部通过、无跳过；提交 `17e2218` |
| 小组与社交 | 168 | 19 | 全部通过、无跳过；提交 `8d28c64` |
| 内容工作流 | 176 | 21 | 全部通过、无跳过；提交 `a021ef2` |
| 错题、图片与 Wiki | 181 | 21 | 全部通过、无跳过；提交 `5a73798` |
| 资料 | 188 | 22 | 全部通过、无跳过；提交 `1feecef` |
| 合集与专栏 | 195 | 23 | 全部通过、无跳过；提交 `2b24672` |
| 知识共享边界 | 204 | 23 | 全部通过、无跳过；提交 `240a30f` |
| 论坛权限/校验转发收尾 | 208 | 23 | 全部通过、无跳过；提交 `faad0c6` |
| 学习计划模板职责拆分 | 226 | 25 | 全部通过、无跳过；提交 `3f9f4c8` |
| 学习计划任务职责拆分 | 245 | 27 | 全部通过、无跳过；提交 `64799fd` |
| 小组帖子职责拆分 | 274 | 29 | 全部通过、无跳过；提交 `41c2d04` |
| 小组文件职责拆分 | 299 | 31 | 全部通过、无跳过；提交 `fef6c32` |

- 旧记录中的 `127.0.0.1:13306`、`target/refactor-mysql-data` 与带 `20261102` 后缀的专用库属于前序验证环境，不是最近一次报告的连接信息。旧库升级 V15、空库迁移的结论为前序阶段记录，本次未重新执行。
- 前序记录中的 `npm run build` 已通过，存在前端 chunk 大于 500 kB 的提示；本轮后续阶段未修改前端，本次未重新执行前端构建。

### 验证边界

- 最近报告的 MySQL 26.7 高于当前 Flyway 已测试的版本范围；**尚未在 README 要求的 MySQL 8 / 与实际部署一致的版本重跑**，不据此宣称生产兼容性完整验证。
- 尚未进行完整浏览器端到端回归；MockMvc 契约测试和真实 HTTP 集成测试不等同于浏览器流程验证。
- 已覆盖上述事务路径与定时发布失败隔离，但不代表所有并发写入、代理边界或文件与数据库联动均已验证。
- 本轮保留现有 URL、JSON 字段和数据库表；历史迁移脚本不改写，仅新增 V15 解决可选结束日期与存储约束不一致。不更换 ORM、不引入新的架构框架。

## 四、提交与推送记录

2026-11-05 推送后曾确认远端包含 `5a73798`；随后文档提交 `607bc62` 也已推送。本轮按模块继续提交，推送结果逐批核实，不以本地提交代替远端成功。

| 提交 | 内容 | 提交 / 推送状态 |
| --- | --- | --- |
| `c197118` | 后端 Java 包结构迁移与测试引用更新 | 历史记录已推送；属于本地跟踪分支历史 |
| `e7ac4e7` | HTTP/业务职责分离、部分 Repository、业务对象、DTO、测试及阅读指南 | 历史记录已推送；属于本地跟踪分支历史 |
| `4ee0879` | 帖子与学习计划规则封装，帖子编辑/删除 Service 与对应测试 | 历史记录已推送；属于本地跟踪分支历史 |
| `95458d2` | 共享服务、论坛、打卡和专注数据访问拆分 | 已提交；本地 reflog 记录推送成功 |
| `17e2218` | 学习计划、自习室与学习后台 Repository 边界 | 已提交；本地 reflog 记录推送成功 |
| `8d28c64` | 小组与社交数据访问隔离 | 已提交；本地 reflog 记录推送成功 |
| `a021ef2` | 投票、草稿、申诉领域服务及定时发布拆分 | 已提交并推送（`8d28c64..a021ef2`） |
| `5a73798` | 错题、图片与 Wiki Repository 迁移及 5 项回归测试 | 已提交并推送（`a021ef2..5a73798`），`git ls-remote` 已确认 |
| `607bc62` | 上一轮进度文档 | 已推送 |
| `1feecef` | 资料 Repository 与下载事务回归 | 已提交并推送，`git ls-remote` 已确认 |
| `2b24672` | 合集与专栏 Repository 及回归 | 已提交并推送 |
| `240a30f` | 知识共享 SQL 与依赖边界 | 已提交并推送 |
| `faad0c6` | 论坛权限/校验转发收尾及阅读指南 | 已提交并推送，`git ls-remote` 已确认 |
| `585c0fd` | 上一轮分层收尾文档 | 已推送 |
| `3f9f4c8` | 学习计划模板拆分、私有模板权限与事务回归 | 已提交并推送，`git ls-remote` 已确认 |
| `64799fd` | 学习计划任务拆分、重复奖励修复及联动回归 | 已提交并推送，`git ls-remote` 已确认 |
| `41c2d04` | 小组帖子业务及共用权限边界拆分 | 已提交并推送，`git ls-remote` 已确认 |
| `fef6c32` | 小组文件业务拆分与权限/存储回归 | 已提交并推送，`git ls-remote` 已确认 |

用户授权每完成一部分即提交并推送。2026-11-05 完成错题、图片与 Wiki 后暂停；2026-11-07 恢复，完成上列四批重构并逐批推送。随后继续完成学习计划模板与任务职责拆分；两批代码提交的远端状态均已通过 `git ls-remote` 核实，提交号已补录。其后小组帖子与文件拆分分别完成全量回归、提交和推送；两批远端状态均经 `git ls-remote` 核实并补录。最新文档提交仍以 `git log -1` 为准。

## 五、下一步建议与阅读顺序

1. 优先准备与实际部署一致的 MySQL 8 独立测试环境，再跑当前 31 项集成测试；保持独立库保护，不对正常业务库运行破坏性回归。
2. 学习计划模板、任务和小组帖子/文件业务已拆分；继续按业务边界拆分 `GroupService` 的成员、加入申请等职责，逐批保留权限及联动回归，不一次重写全部模块。
3. 渐进整理 Map、请求/响应类型和过长业务方法，每批同步文档、验证、提交及推送。

小组帖子可读 `GroupController → GroupPostService → GroupRepository`，文件可读 `GroupController → GroupFileService → GroupRepository / 文件系统`；权限共用 `GroupAccessService`，小组详情由 `GroupService.detail` 组装。

任务写入可读 `StudyPlanController → StudyPlanTaskService → StudyPlanRepository / StudyPlanProgressService / StudyRewardService`，详情继续由 `StudyPlanService.plan` 组装。

学习计划模板可读 `StudyPlanController → StudyPlanTemplateService → StudyPlanRepository`，计划创建事务可读 `StudyPlanService.createPlan/applyTemplateEndpoint → StudyPlanTemplateService.applyTasks`。

推荐先读 `ForumController → ForumQueryService → ForumMapper`，再读 `StudyRewardService → PointService / BadgeService → BadgeRepository`。知识列表/写入可读 `NoteController → NoteService → NoteRepository`，详情可读 `NoteService.detail → KnowledgeService.note → KnowledgeRepository.note`，这条链路已无 Service SQL。资料文件与扣分可读 `ResourceService.create/download → ResourceRepository`；合集条目与专栏可读 `CollectionService → CollectionRepository`，目标内容可见性统一由 `KnowledgeService` 校验。`ForumService.hardDeletePost` 与 `DraftPublisher → ScheduledDraftService → DraftRepository` 展示提交后文件清理和逐草稿事务隔离。
