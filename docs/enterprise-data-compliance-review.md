# 企业级数据合规、资源授权及 Token 消费治理

## Phase 0 源码评审

| 范围 | 可复用实现 | 尚需完成 |
| --- | --- | --- |
| 身份和菜单 | `EnterpriseAdminService`、`ApiAuthenticationFilter`、权限中心工作区、`SysRolePermission` | 租户、用户和角色生效窗口，续期入口，运行中权限复验 |
| 资源授权 | `ResourceGrant`、`ResourceAuthorizationService`、现有角色与 Agent 绑定，MCP 有独立工具规则 | 已在资源授权复用结束时间并增加开始时间。独立 MCP 工具规则及每条实际执行链路还需验收 |
| HTML 和 PDF | `UserAssetController`、`LightCodingMonitorController`、工作区 HTML bundle，以及当前 PDFBox 草稿 | 服务端依据可信报告或会话 ID 重新做报告权限校验，从受控数据生成 PDF。当前页面截图输入不能证明与指定报告一致 |
| 操作审计 | `SysAuditLog` 和权限中心现有查询 | 已增加 PDF 请求标识、哈希、文件大小和生成与传输状态。待补可信报告、角色快照、筛选和审计权限 |
| Java 模型消费 | `MeteredChatModel`、`ModelUsageMetrics` 与运行期预算 | 逐请求区分供应商实测与缺失，保存可幂等的企业消费流水，确认普通对话及厂商直接调用的覆盖 |
| Native 模型消费 | `ClineModelReceiptStore` 及 Node 的 `model-receipt.mjs`，运行时任务监控已使用请求回执 | 同一请求以可追踪的厂商 Usage 结算，缺失继续记为待核对。Node 仅回传证据，企业额度归 Java |

## 当前改动和数据迁移

- 用户资产下载及任务监控下载共用 HTML 媒体判断。监控预览保留现有纯文本安全显示，业务报告仍可通过受控 HTML bundle 在线查看。
- PDF 导出沿用原有审计表。导出成功表示服务端已生成文件，传输仍未确认。客户端传入的 reference 仅作请求参考，无法充当报告身份。
- 现有授权编辑器的资源授权支持永久或指定期限。后端区间包含生效时间，排除到期时间。已有记录的 `effective_at` 为空表示没有开始限制，原有到期时间仍有效。保存其他字段时默认沿用原有期限。
- H2、MySQL、PostgreSQL 各有对应的 `V20261126_01__resource_grant_effective_at.sql`，初始化 SQL 同步。生产使用 schema validate，需要在候选和正式升级前分别执行相应迁移。应用回滚前确认暂不启用新的未来生效授权，因为旧版本只识别到期时间。
- 已在用户管理加入消费角色配置，只接受该用户在同租户已分配且启用的角色。同一用户更新操作移除该角色时，系统清除配置。现有用户默认未指定。对应迁移为 `V20261126_02__user_token_billing_role.sql`。这一配置尚未接入 Runtime 的任务消费快照和账本结算。

## 按量治理实施和生产门槛

角色共享池和个人池应在同一事务内预占，单位为供应商真实 Token。管理员为每位用户预先指定一个有效消费角色，任务在创建时固定该角色。任务恢复沿用原归属，后续新建任务才采用新配置。预占记录要保存周期和请求 ID，原任务恢复和迟到回执继续关联原周期。失败或取消后仍根据已实际发生的请求结算。缺少用量时保留核对状态及必要预占。网关或 Cline 的合成零值不能记成真实消费。

管理员已确认仅明确授权的角色允许导出，默认启用中文水印“企业内部资料 · 严禁外传”。报告源绑定、身份授权期限、消费角色的执行链路和统一账本及并发预占、界面消费展示、迁移回滚、真实 Runtime 与 Cline 的恢复和取消验证均是本任务的独立交付项。生产入口可做只读基线检查。生产发布指针在这些 P0 验收完成后切换。

## 部署磁盘

2026-11-26，服务器 `/opt` 约 94% 已用，是 Docker 数据目录所在分区。候选构建工作区为 `/mnt/disk01/enterprise-compliance-candidate`。构建上下文、源码归档、升级证据及备份放在该数据盘，Docker 新增层和镜像仍占用 `/opt`。本轮回收 Docker 构建缓存 815.1 MB，将两份约 1.09 GB 的隔离候选构建迁移到数据盘，原路径保留符号链接。完成后 `/opt` 可用约 13 GiB，数据盘可用约 82 GiB。数据库、容器卷、生产和回滚镜像继续保留。

## 验证记录

- 六个 Java 测试类共 46 项通过。覆盖报告访问、PDF 默认授权与水印及审计、任务监控下载、资源授权窗口和消费角色配置。
- 44 项前端定向测试和生产前端构建通过。两份迁移分别在隔离 PostgreSQL 18.7、MySQL 8.4.7 和 H2 2.3.232 上重复执行，均保留旧授权到期时间和旧用户的空消费角色。
- 源码候选 `enterprise-compliance-6bb0f533d.bundle` 与迁移验证日志已保存在服务器候选工作区。生产镜像和数据库发布指针仍使用原版本。
- 生产 `18100` 首页和管理员会话查询返回 HTTP 200。生产现有镜像的 PDF 导出能力接口返回 HTTP 404，符合本轮只执行基线验证的结果。

## 2026-11-27 整改候选

- PDF 请求只提交归档文件 ID 或会话与消息 ID。HTML 先校验所属用户、任务、运行记录及归档文件摘要。会话回答通过关联的终态任务读取执行事件。导出审计保存归档资源清单摘要和最终 PDF 摘要。
- API 通过 `CHATCHAT_REPORT_EXPORT_RENDERER_URL` 访问资源库的 `/api/v1/report-pdf`，令牌由 `CHATCHAT_REPORT_EXPORT_RENDERER_TOKEN` 注入。预览 Worker 需要包含 `report-print.mjs`。还需验证升级后的真实报表样式和历史会话中的归档关联。
- Native 模型请求通过 Java 预占用户与角色额度，再转发给模型。用量回执与预占共享请求 ID。迟到用量按原周期结算，缺少 Usage 的记录保留预占并标记待核对。Java Coding 模型桥也使用同一账本。
- Java Agent 规划、任务分类、会话摘要、提问建议、SQL 元数据、图像理解、通知和技能编译复用同一账本。已核实的任务身份在提交时绑定消费角色，同一运行的 Native 和直接调用共享归属。缺少可核验用户的可选步骤使用原有本地回退。
- 测试接入隔离的 PostgreSQL 18.7，验证 Native 预占会拦截同角色的直连请求，额度释放后通过真实 LangChain4j HTTP 调用读取供应商回执并结算。还覆盖了同一运行冻结消费角色、其他身份到期时阻止模型调用和旧周期迟到回执。
- 生产发布门槛仍包含真实 Runtime、Cline、模型服务和报告导出的联合验收。需要继续验证管理员额度界面及现场升级、回滚。
- 部署服务器使用 JDK 17 和隔离 Node 容器构建 API 与 Vue。候选包位于 `/mnt/disk01/enterprise-compliance-candidate/direct-governance.nwmQaRVy/source/chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT.jar`。SHA-256 为 `0d6653eedfb75c7882034ebefea874a583ac22773b62a343c6378c0991273e4f`，JAR 内已核对新版额度控制类和前端入口。

## 2026-11-27 服务器联合验收

隔离 API 监听 `127.0.0.1:19520`，使用独立数据库和 Temporal 任务队列，以及隔离的 Native 执行器、PDF 渲染进程和模型配置。编码调度与权限工作区的授权均来自现场 MCP 的签名只读接口。验收使用合成 CSV，独立核对预期结果为 3 行、合计 42。任务快照、回执、构建日志和产物保存在服务器 `/mnt/disk01/enterprise-compliance-candidate/direct-governance.nwmQaRVy/live-qa.su2y2fmi`。

| 场景 | 实测结果 | 证据 |
| --- | --- | --- |
| 归档报告与 PDF | Runtime 与 Cline 实际执行 Python，交付 HTML 和 JSON。服务端验证归档摘要、生成中文水印 PDF，写入导出审计 | 请求 `enterprise-p0-b6d75398-5f60-4943-be77-af7f3591d338`，PDF SHA-256 `768532aa755091cdacc4f3bb50836a7fdf39c7ca538867999672e6d9e1219772`，审计请求 `b924538d-8c6c-4c2f-b923-b1c1b6c4801d` |
| 角色、报告所有权和身份到期 | 未明确获准的管理员导出返回 403。已获准的其他用户导出归档返回 404。原始 HTML 下载返回 403。测试账户到期后，旧会话返回 401，重新登录被拒绝 | `ReportExportController`、`ReportExportSource`、`ApiAuthenticationFilter` |
| Token 预占和结算 | 配置个人和角色共享额度。两条供应商回执各为 200，两池各增加 400。本次任务预留释放。重置新周期并保留历史记录 | 请求 `enterprise-p0-6e271e47-d74c-40d0-a460-4ab87d3379e5`，模型请求 `684314a2-8f6e-4a2d-9ba8-bcbec667169a`、`8203c46c-ce44-4237-bb2a-e7b19e754c59` |
| LiteLLM FRAMEWORK 和公网 Qwen3.8-27B | 真实 Runtime、Cline、Gateway、LiteLLM 1.110.3 完成任务。17 个模型请求均为 HTTP 200，Usage 均为 UNKNOWN。归档 JSON 的行数和总额独立核对一致 | 请求 `litellm-live-neiz_emr`，会话 `native-a0021e17-b544-4de5-9eab-0913a7bd45d2`，结果 SHA-256 `1b36e8f72394bbaec4ade3aca7fdd7e0ffa288e8c833a98bc79517a3a3a790c5b` |
| FRAMEWORK 用量缺失且额度不足 | 三次调用后两个池各保留 101574 Token，已消费均为 0。后续请求在模型调用前被拒绝，审计原因 `RUNTIME_TOKEN_QUOTA_EXHAUSTED`，`providerDispatched=false` | 请求 `litellm-live-r5znyacx`、Native 会话 `native-6af05220-a088-430a-9f8b-269fad69074c` |

本轮发现两个发布问题。额度及报告审计接口显式声明 Spring 路由参数名称，避免生产构建缺少参数名时得到 HTTP 400。生产 API 原镜像缺少中文字体，新构建纳入 Noto CJK；隔离环境通过渲染结果目视复核。Gateway 生产容器已使用带错误文件记录的镜像，确认 `/app/data/model-errors.ndjson` 存在且权限为 0600。

| 恢复与发布验证 | 结果 |
| --- | --- |
| Runtime 取消直达上游 | 测试任务 `litellm-live-3fj_l1w4` 进入 `CANCELLED`，从发出取消到确认约 1.77 秒，模拟供应商验证连接提前关闭。 |
| 单次模型故障恢复 | 测试任务 `litellm-live-r7__9e4q` 的首次推理请求收到 HTTP 503，跟踪 ID 为 `4e586ec5-5ce1-41d7-a083-9ee426495f89`。后续调用及原生工具执行完成并交付经摘要核对的分析结果。 |
| 原生 Worker 退出 | 使用任务自己的专用 Docker 执行器容器注入进程退出故障。修复前任务用时 181.01 秒收敛，修复后 9.83 秒确认旧执行器停止且无待协调状态。通用任务保持失败记录，重新提交后完成交付。 |
| 生产结构与回滚演练 | 从生产 PostgreSQL 导出结构至隔离数据库，三条增量迁移连续执行两轮，新 API 和上一个生产 API 都通过启动检查。回滚保留已迁移列和新增审计记录。 |

## 18100 发布和回滚

本次 API JAR SHA-256 为 `aef946a6023d80ddf3402f95aefc7a36a6b5618ca17d97c8b38817053cecc753`。API 和渲染镜像版本为 `enterprise-p0-20261127-aef946a6023d`，Native 节点镜像为 `enterprise-p0-20261127-worker-0b332ac41a`。Gateway 使用带错误日志的 `1.2.0-rc.1-model-errors-f2f9b6beb`，LiteLLM 以 `v1.110.3` 的 OCI 摘要部署。

- 生产数据库备份、原镜像清单、Compose 文件、模型路由修订和验收日志存于服务器 `/mnt/disk01/enterprise-compliance-candidate/direct-governance.nwmQaRVy/live-qa.su2y2fmi/production-18100-r2`。备份通过 `pg_restore --list` 并保留 SHA-256。
- 发布的应用配置由 `/opt/ros21/compose.json` 与该目录的 `app-framework-compose.json` 合并，项目名为 `runtime-os-source-production`。预览服务使用候选目录的 `render-production-resolved.json` 和 `render-production-candidate.json`，保留 seccomp 与 no-new-privileges。
- 模型网关由 `/opt/llm-compatibility-gateway/docker-compose.yml`、`compose.site.yml` 和发布目录的 `gateway-production-compose.json` 合并，项目名为 `llm-gateway`。新模型名为 `qwen38-framework`，使用专用 Runtime Managed 凭证与受信任的内网 TLS，现有默认模型仍使用原配置。凭证文件仅限发布主机管理员读取。
- 回滚模型先停用平台模型 `qwen38-framework`，按 `gateway-release-result.json` 中的版本回滚 Gateway 配置，然后以原 Compose 复原模型网关并停用 LiteLLM。应用按 `original-images.txt` 恢复 API、Native 节点和渲染服务，渲染 Worker 沿用有效的 seccomp 配置。保持增量数据库列和账本记录。仅在需要恢复数据库时使用完整备份，并先核对备份后产生的生产写入。

生产 Gateway 配置从版本 12 升至 13。首次生产合成任务 `enterprise-18100-a3a9e9901459` 完成交付，实际结果为 3 行、合计 42。10 条模型回执均为 HTTP 200，逐条匹配 Gateway 的 `runtime_managed` 和 `FRAMEWORK` 请求。归档的 SHA-256 为 `4f9ff6d4c76e033281ee750ed1a8616f4b483d9d7a2f2ee537634d4c3f46a327`。第一次请求的结果带有额外统计字段，后续以显式 JSON Schema 复验严格的两字段交付。

严格复验任务 `enterprise-18100-4fea92cfb13d` 状态为 `COMPLETED`。归档恰含 `rowCount=3` 和 `total=42`，SHA-256 为 `326a7fd186cabdcde2d805c836762a81c00077c4b7f0c501c45925b8b9a62281`。6 条模型回执均匹配实际 Gateway 的 Runtime Managed FRAMEWORK 请求，最后一条 Gateway 请求 ID 为 `aaa3eb5d-5221-4802-bcfe-c5401ffd4f4a`。同一次运行在生产 PostgreSQL 账本中有 6 条 `UNKNOWN` 记录，实际用量均为空。发布后的 `18100` 首页返回 HTTP 200，API、Native、渲染、Gateway 和 LiteLLM 容器均通过健康检查。
