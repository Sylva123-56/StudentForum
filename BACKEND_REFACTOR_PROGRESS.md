# 后端重构进度记录

更新日期：2026-11-05  
代码分支：`codex/rebuild`；2026-11-02 的未提交成果已在本轮验证，按下方记录分阶段提交与推送  
目标：改善学习与维护体验，逐步形成 Controller → Service → Repository/Mapper → 数据库的分层结构。

> 当前状态：已完成目录迁移、论坛 Controller 的 Mapper 清理、论坛及徽章数据访问下沉、共享权限/积分/通知/徽章拆分、打卡和专注 SQL 下沉，以及学习规则/计划进度/自习室访问能力拆分，并已运行独立 MySQL/HTTP 集成测试；**尚未完成全部后端分层重构**。本记录依据当前源码、已有测试报告和本轮重构执行结果整理，不将编译通过等同于完整业务验证。

## 一、已完成的部分

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

| 模块 | 已完成 | 仍需处理 |
| --- | --- | --- |
| 论坛 | `ForumService` 的全部 JDBC/SQL 移至 `ForumRepository`；提及与信誉读写移至 `MentionRepository` / `ReputationRepository` | 论坛仍保留旧调用方使用的权限/校验转发方法，后续随调用方迁移删除 |
| 徽章 | 指标查询、已获徽章查询及去重插入移至 `BadgeRepository` | 其他学习工具 SQL 尚未全部下沉 |
| 打卡 | 全部 SQL 与查询构造进入 `CheckinRepository`，业务权限/校验留在 `CheckinService` | 保持现有分页、补卡、奖励和访问语义；继续补充并发回归 |
| 专注 | 全部 SQL 进入 `FocusRepository`，保持统计时间边界和 21 条预取分页 | 学习后台的专注治理 SQL 仍在 `AdminStudyService` |
| 学习共享能力 | 规则配置、计划进度、自习室访问和科目候选分别移至专用 Service 与领域 Repository | 学习计划剩余 CRUD/模板、自习室 CRUD/消息与后台查询尚未迁完 |
| 小组检索 | 排行、推荐、筛选查询移至 `GroupRepository` | 小组成员、帖子、文件、打卡等操作仍有 SQL 留在 `GroupService` |
| 小组后台管理 | SQL 移至 `GroupAdminRepository`，业务权限及状态校验留在 `GroupAdminService` | 补充更多集成回归和审计行为验证 |
| 笔记 | 列表、创建、更新、修订记录和编辑行锁移至 `NoteRepository` | 详情读取及其访问检查仍经 `KnowledgeService.note`，其中仍有 SQL |

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

### 6. 2026-11-02 本轮新增

- 新增 `AccountAccessService`，集中身份、可写状态、管理员与版主板块权限。账户、论坛查询、通知、积分、小组、打卡、专注、学习计划、自习室、学习后台及上传模块按明确职责注入，不再为这些权限规则依赖论坛发布服务。
- 新增 `PointService`、`NotificationService`、`BadgeService` 和 `StudyRewardService`；注册/登录、小组与私信提及、信誉调整及学习激励改为直接调用对应服务。`ForumService` 已移除积分与信誉的旧转发入口。
- `StudyToolService` 不再依赖 `ForumService`，也不再持有积分、通知、徽章结算；从 375 行缩至 259 行，但设置、计划进度和自习室查询仍待继续拆分。
- 提取纯校验规则 `ContentRules`，复用原有文本、图片和附件路径约束，不引入额外框架或依赖。
- 修复删帖先清理文件、后续数据库回滚时可能丢文件的问题：仅在最外层事务提交后清理帖子上传文件。
- 举报对象的作者查找改为单一显式分支，避免两处重复的长嵌套三元表达式；缺失的消息举报对象返回 404 而不是索引异常。
- 新增论坛 HTTP 契约、提及隐私与去重、徽章阈值和目录、账户权限、积分流水、Repository 参数与清理顺序、学习激励编排回归。
- 在现有真实集成测试上新增 CSRF 拒绝、Session 轮换/失效、帖子与回复写入回滚、删帖外层事务回滚/提交、跨用户和版主操作边界用例。

### 7. 2026-11-02 第二轮继续重构

- `CheckinService`、`FocusService` 已不再持有 `JdbcTemplate`、拼接 SQL 或调用 `tools.row/requiredRow`；读写通过具名 `CheckinRepository`、`FocusRepository` 方法完成。
- `StudySettingsService` / `StudySettingsRepository` 集中规则配置的默认值、读取与白名单更新；后台只提交已校验的配置值，不再拼接更新语句。
- `StudyPlanProgressService` / `StudyPlanRepository` 集中顶层任务进度、父任务汇总与计划状态同步，保留归档语义，写入仍在事务内。
- `StudyRoomAccessService` / `StudyRoomRepository` 集中私密房间的房主/成员访问策略，以及依据成员表和专注记录刷新房间总量。
- `StudyPreferencesService` / `StudyPreferencesRepository` 集中已加入小组与科目候选读取，保留顺序、去重和 20 项上限。
- `StudyToolService` 从上一轮的 259 行缩至 **108 行**，只保留输入/日期/连续天数工具和旧 `row/requiredRow` 兼容入口；后者仅供尚未完成的学习计划、自习室和学习后台调用，不能当作已完成的数据访问分层。
- 真实数据库验证发现原有计划接口/前端允许省略结束日期，但旧数据库 `end_date NOT NULL` 导致 500；新增 **V15** 将该列改为可空，保留现有数据，未修改 V1–V14 的历史脚本。
- 新增配置默认值/更新白名单、打卡查询范围与绑定、专注时间边界和分页、计划进度/归档、房间权限、科目候选等单元回归；新增 3 项真实 HTTP 学习流程回归。

## 二、尚未完成的部分

| 优先级 | 待办 | 完成标准 |
| --- | --- | --- |
| 高 | 继续将其他 Service 中的 SQL 下沉 | 论坛/徽章/打卡/专注已完成；继续处理学习计划其余操作、自习室 CRUD/消息、学习后台、小组、社交、内容工作流和知识模块，保持原有参数与返回含义 |
| 高 | 继续收敛共享 Service 的剩余职责 | 权限、积分、提及、徽章、设置、进度和自习室访问职责已拆出；继续迁移 `ForumService` 旧权限/校验调用方，并在迁完学习计划/自习室/后台后删除 `StudyToolService.row/requiredRow` |
| 中 | 继续拆分过大的业务类 | 按内聚职责拆分 `GroupService`、`StudyPlanService`、`ContentWorkflowService` 等；不只是把大 Controller 原样换成大 Service |
| 中 | 降低共享服务内部耦合 | 减少经 `KnowledgeService` 访问数据库字段或其他服务实例，改为按明确职责注入依赖 |
| 中 | 渐进替换 Map 与含糊命名 | 优先为稳定的请求、响应和核心业务状态引入类型，逐步减少强制转换、字符串字段名及 `k` 等过短变量名 |
| 中 | 改善方法内部可读性 | 展开仍压缩成一行的方法，拆分复杂分支与长 SQL，保持错误处理和校验语义，不引入无实际用途的接口或继承体系 |
| 中 | 扩充业务和 HTTP 回归 | 覆盖管理员/版主/普通用户边界、跨用户内容访问、分页、写入失败、文件清理和定时发布失败场景 |
| 低 | 随模块迁移更新学习指南 | 为已完成的典型模块补充阅读顺序，保持文档与真实代码一致 |

SQL 迁移重点包括 `AdminStudyService`、`StudyPlanService`、`StudyRoomService`、`GroupService`、`SocialService`、`ContentWorkflowService` 和 `StudyToolService` 的旧任意 SQL 入口，以及仍通过 `KnowledgeService` 执行数据库操作的知识内容模块。

## 三、验证记录与边界

### 本轮实际验证

- 使用 Java 21.0.5、Maven，显式启用 `KNOWLEDGE_INTEGRATION=true` 后全量运行 `mvn test`：**154 项，154 项通过，0 失败，0 错误，0 跳过**；其中真实 MySQL/HTTP 集成测试 **16 项**。默认不设置开关时，这 16 项仍按设计跳过。
- 独立启动仅绑定 `127.0.0.1:13306` 的本机 MySQL，数据目录为 `target/refactor-mysql-data`，上一轮测试库为 `student_forum_knowledge_test_refactor_20261102`，本轮先验证该专用库升级 V15，再用全新专用库 `student_forum_knowledge_test_study_20261102` 完成全量回归；未使用或修改正常业务库。测试结束后关闭该独立实例，保留忽略目录内的测试数据供排查。
- 验证了旧专用库升级 V15、新专用库从空库执行全部迁移、Spring Bean 装配、真实 HTTP Session/CSRF、用户/版主权限边界、私有知识文件访问、失败写入回滚、删帖外层事务回滚保留文件与提交后清理，以及打卡奖励/跨用户边界、私密自习室与专注记录/后台清理联动、任务汇总/归档/重复完成和可选结束日期。
- `npm run build`：通过；仍有前端 chunk 大于 500 kB 的现存提示，本轮未修改前端代码或构建策略。
- `git diff --check`：通过。
- 旧基线结果为 98 项（89 通过、9 跳过），上一轮为 132 项全通过；以上 154 项是本轮重新执行的结果，不沿用旧报告冒充验证。

### 验证边界

- 本机可用服务器版本为 MySQL 26.7.0；Flyway 提示其高于当前版本已测试的数据库版本范围。虽然迁移和测试已通过，**尚未在 README 要求的 MySQL 8 / 与实际部署一致的版本重跑**，不据此宣称生产兼容性完整验证。
- 尚未进行完整浏览器端到端回归；MockMvc 契约测试和真实 HTTP 集成测试不等同于浏览器流程验证。
- 已验证上述事务路径，但不代表所有业务的并发写入、所有代理边界或其他文件操作与数据库联动都已验证；定时发布失败等场景仍需扩充。
- 保留现有 URL、JSON 字段和数据库表；历史迁移脚本不改写，仅新增 V15 解决可选结束日期与存储约束不一致。不更换 ORM、不引入新的架构框架。

## 四、提交与推送记录

以下三个历史代码提交已在上一重构阶段推送至 `origin/codex/rebuild`：

| 提交 | 内容 |
| --- | --- |
| `c197118` | 后端 Java 包结构迁移与测试引用更新 |
| `e7ac4e7` | HTTP/业务职责分离、部分 Repository、业务对象、DTO、测试及阅读指南 |
| `4ee0879` | 帖子与学习计划规则封装，帖子编辑/删除 Service 与对应测试 |

2026-11-05：接续本记录，已使用 JDK 21 重跑全部 154 项测试（含 16 项独立 MySQL/真实 HTTP 集成测试），全部通过、无跳过。测试使用本轮新建的独立实例 `127.0.0.1:3308` 与专用 `knowledge_test` 数据库，未连接业务库；实例版本仍为 MySQL 26.7.0，MySQL 8 兼容性边界保留。先提交现有共享服务、论坛、打卡及专注重构成果，再继续剩余领域。

本轮用户已明确授权每完成一部分即提交并推送；每批更新本记录，具体提交与推送状态以 Git 为准。

## 五、建议继续顺序

1. 在与部署一致的 MySQL 8 专用测试实例上重跑现有 16 项集成测试，保留现有独立库保护。
2. 下一轮优先把 `StudyPlanService` 的计划/任务/模板 SQL 收入现有 `StudyPlanRepository`，随后迁移自习室 CRUD/消息与 `AdminStudyService`；迁完后三者即移除 `StudyToolService.row/requiredRow`，不创建任意 SQL 的新包装器。
3. 随后迁移小组、社交、内容工作流与知识模块；同时减少 `KnowledgeService` 的字段穿透访问。
4. 每处理一个领域，同时整理对象、命名和过长方法并补回归；更新本记录，明确未覆盖边界。按本轮用户授权，每完成并验证一个领域即提交并推送。

推荐先读 `ForumController → ForumQueryService → ForumMapper`，再读 `StudyRewardService → PointService / BadgeService → BadgeRepository` 和 `ForumService.hardDeletePost` 的提交后清理。`NoteController → NoteService → NoteRepository` 仍是列表/写入示例；笔记详情仍走 `KnowledgeService`，不要当作已完成迁移的全链路。
