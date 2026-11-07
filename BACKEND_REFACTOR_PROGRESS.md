# 后端重构进度记录

更新日期：2026-11-07  
代码分支：`codex/rebuild`；按模块验证、提交并推送，最新提交以 `git log -1` 为准；历史提交与推送状态见第四节

目标：改善学习与维护体验，逐步形成 Controller → Service → Repository/Mapper → 数据库的分层结构。

> 当前状态：2026-11-07 已恢复重构。资料写入、下载及治理 SQL 已迁入 `ResourceRepository`，资料详情共享查询和访问策略仍经 `KnowledgeService`。本轮全量测试 **188 项通过（含 22 项集成测试），无失败、错误或跳过**；合集/专栏和知识共享服务的剩余迁移继续进行，尚未宣称完成全部后端重构。

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
| 论坛 | `ForumService` 的全部 JDBC/SQL 移至 `ForumRepository`；提及与信誉读写移至 `MentionRepository` / `ReputationRepository` | 论坛仍保留旧调用方使用的权限/校验转发方法，后续随调用方迁移删除 |
| 徽章 | 指标查询、已获徽章查询及去重插入移至 `BadgeRepository` | 持续补充奖励与并发去重回归 |
| 打卡 | 全部 SQL 与查询构造进入 `CheckinRepository`，业务权限/校验留在 `CheckinService` | 继续补充补卡、奖励和并发边界回归 |
| 专注 | 全部 SQL 进入 `FocusRepository`，保持统计时间边界和 21 条预取分页；后台治理 SQL 已进入 `AdminStudyRepository` | 继续验证并发写入与房间总量联动 |
| 学习共享能力 | 规则配置、计划进度、自习室访问和科目候选分别移至专用 Service 与领域 Repository | `StudyToolService` 已删除 JDBC 及 `row/requiredRow` 入口，仅保留输入/日期/连续天数工具 |
| 学习计划、自习室及学习后台 | 计划/任务/模板、房间/成员/消息与后台统计治理分别进入 `StudyPlanRepository`、`StudyRoomRepository`、`AdminStudyRepository` | 继续整理过长业务方法、Map 与含糊命名 |
| 小组 | 检索、成员、帖子/回复、互动、文件、打卡和治理 SQL 全部进入 `GroupRepository` | `GroupService` 仍有多种业务职责，可按实际需要继续拆分 |
| 社交 | 关注、动态、通知设置、拉黑、私信与会话 SQL 全部进入 `SocialRepository`；直接依赖权限与内容规则 | 补充更多并发和消息边界回归 |
| 小组后台管理 | SQL 移至 `GroupAdminRepository`，业务权限及状态校验留在 `GroupAdminService` | 补充更多集成回归和审计行为验证 |
| 内容工作流 | 拆出投票、草稿、申诉及定时发布 Service，SQL 进入对应 Repository | 继续改善类型与业务方法可读性 |
| 笔记 | 列表、创建、更新、修订记录和编辑行锁移至 `NoteRepository` | 详情读取及其访问检查仍经 `KnowledgeService.note`，其中仍有 SQL |
| 错题及图片 | 本批列表、写入、复习、统计、复习计划和图片元数据 SQL 移至 `MistakeRepository`、`MistakeImageRepository` | 错题详情、成员访问检查等仍经 `KnowledgeService` |
| Wiki | 本批列表、编辑行锁、修订和治理查询移至 `WikiRepository`，显式注入 `AccountAccessService` | 审计及部分校验仍依赖 `KnowledgeService` |
| 资料 | 列表、文件认领、下载扣分、互动和治理 SQL 已进入 `ResourceRepository`，显式依赖 `AccountAccessService` | 共享详情读取与审计仍经 `KnowledgeService` |
| 合集/专栏及知识共享服务 | 保留已有 HTTP/Service 分离成果 | `CollectionService` 及 `KnowledgeService` 仍有 SQL 和字段穿透访问 |

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

## 二、尚未完成的部分

| 优先级 | 待办 | 完成标准 |
| --- | --- | --- |
| 高 | 完成知识模块剩余 SQL 下沉 | 处理 `ResourceService`、`CollectionService` 及 `KnowledgeService` 中的详情读取、成员查询、审计和通用 SQL 入口；保留权限、参数、错误与返回语义 |
| 高 | 收敛共享 Service 的剩余职责 | 随调用方迁移移除 `ForumService` 的旧权限/校验转发，降低知识模块对 `KnowledgeService` 字段及其他服务实例的穿透访问；`StudyToolService.row/requiredRow` 已删除 |
| 中 | 继续拆分过大的业务类 | 优先评估 `GroupService`、`StudyPlanService` 等仍集中的职责；内容工作流已按投票、草稿、申诉、定时发布拆分 |
| 中 | 渐进替换 Map 与含糊命名 | 优先为稳定的请求、响应和核心业务状态引入类型，逐步减少强制转换、字符串字段名及 `k` 等过短变量名 |
| 中 | 改善方法内部可读性 | 展开仍压缩成一行的方法，拆分复杂分支与长 SQL，保持错误处理和校验语义，不引入无实际用途的接口或继承体系 |
| 中 | 补齐验证边界 | 在与部署一致的 MySQL 8 专用实例重跑集成测试；继续补充并发、文件联动与浏览器流程覆盖。定时发布失败回滚和后续草稿继续执行已有集成覆盖 |
| 低 | 随模块迁移更新学习指南 | 为已完成的典型模块补充阅读顺序，保持文档与真实代码一致 |

学习后台、学习计划、自习室、小组、社交和内容工作流已完成本轮 SQL 下沉，不再列为待迁移领域。当前 SQL 迁移重点为资料、合集/专栏及知识共享服务；错题和 Wiki 已迁出的部分仍有对 `KnowledgeService` 的共享依赖，需后续收敛。Repository 应使用具名业务方法，不新增任意 SQL 包装器。

## 三、验证记录与边界

### 最近一次代码验证（2026-11-05 16:08，提交 `5a73798` 前重跑）

- 提交错题、图片和 Wiki 本批改动前重新执行 `mvn -q test`，退出码 0；汇总 `target/surefire-reports/TEST-*.xml` 得到：**181 项，181 项通过，0 失败，0 错误，0 跳过**，其中 `KnowledgeIntegrationTest` 为 **21 项**。
- 报告中的运行环境为 Java **21.0.5**，路径 `C:\Program Files\Java\jdk-21`；测试使用 Maven，并启用 `KNOWLEDGE_INTEGRATION=true`。默认不开启此开关时，集成测试按设计跳过。
- 日志 `target/knowledge-commit-test.log` 为本次运行输出；上一轮日志 `target/knowledge-partial-test.log` 记录测试连接为 `jdbc:mysql://127.0.0.1:3308/student_forum_knowledge_test_refactor`（含创建数据库与编码/时区参数），数据库版本 MySQL **26.7**；本轮独立实例数据目录为 `target/refactor-mysql`。本次测试前该实例已停止，按同一数据目录和 3308 端口重新启动后运行，通过 `KNOWLEDGE_TEST_DB_URL` 指向该专用库。未使用正常业务库；测试实例是否仍在运行以实时进程状态为准。
- 21 项集成测试覆盖 Session/CSRF、账户与版主权限、私有知识文件、写入回滚、删帖提交后清理、打卡/专注/计划/自习室联动、小组和社交隐私，以及增强发布回滚和定时草稿失败隔离。定时发布失败用例的 WARN/堆栈属于预期失败注入，测试报告无失败。
- 本次文档修改后运行 `git diff --check`，检查文档格式；未将该检查当作业务测试。

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

本轮用户已明确授权每完成一部分即提交并推送；后续又要求完成当前小任务后暂停。2026-11-05 已完成错题、图片与 Wiki 后暂停；2026-11-07 用户要求继续，现按模块验证、提交并推送。

## 五、恢复工作后的建议顺序

1. 继续迁移资料、合集/专栏及 `KnowledgeService` 的剩余数据访问和共享职责，减少 `k.db`、`k.forum` 等字段穿透调用。
2. 在与部署一致的 MySQL 8 专用测试实例上重跑现有 21 项集成测试，保留独立库保护。
3. 随调用方迁移删除 `ForumService` 的兼容转发，并逐步整理 Map、命名和过长业务方法；每批保留行为回归，验证后提交并推送。

推荐先读 `ForumController → ForumQueryService → ForumMapper`，再读 `StudyRewardService → PointService / BadgeService → BadgeRepository` 和 `ForumService.hardDeletePost` 的提交后清理。`NoteController → NoteService → NoteRepository` 仍是列表/写入示例；笔记和错题详情仍走 `KnowledgeService`，不能视为已完成迁移的全链路。定时任务可读 `DraftPublisher → ScheduledDraftService → DraftRepository`，关注逐草稿事务与失败隔离。
