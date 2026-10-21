# Runtime OS 用户文件与分析资产：SILO 实施与验收

分支：`feature/runtime-unified-assets-silo`，起点 `dced9f6cf`。根据本轮提供的《统一用户文件与分析资产层》设计实施；先进行本地验证，再更新授权的隔离服务器。服务器验收结果在本文末尾记录。

## 实施边界

Java 继续掌握身份、API 权限、文件登记、配额、任务授权与数据供给。Cline 继续负责分析和代码，现有 MCP Python Runtime 执行生成的程序。SILO 只持久化对象，不承担任务数据库、推理或检索职责。

- P1：私有、启用版本控制的 `runtime-assets` 桶；用户文件上传、列表、下载、删除；数据库登记与配额；服务端用户/租户隔离。
- P2：授权输入快照、每次代码提交、停止后的日志和产物归档；本地输入缓存丢失时按原任务清单和哈希恢复；结果可从对象存储流式读取。复用既有任务协议、证据数据库及 Python 隔离，不建立第二套执行系统。
- P3 基础：任务上下文及结构化结果归档，用户显式选择历史上下文供本次分析使用。没有自动遍历文档库或注入全部历史；语义检索、自动关联及知识索引尚未建设，也没有恢复已移除的旧文档 RAG。

这版新入口位于数据车间页面的“我的文件与分析记录”。既有搜索文档库、聊天附件、Python Studio 旧文件和历史本地文件没有批量搬迁；新资产接口和已经接入的 Coding 链路使用 SILO。存储统一的其他入口可以在同一授权服务上继续接入，不能把本轮范围解释为全量历史迁移完成。

## 元数据、对象身份与一致性

`runtime_user_workspace` 管理租户、用户、配额和已预留字节；`runtime_workspace_file` 管理逻辑资产、类别、哈希、长度、S3 对象版本、任务/Run 绑定、来源、状态与删除时间。原有 Durable Coding Task/Evidence 继续管理任务、数据引用和验收状态，因此没有重复建立 `analysis_task` 等同义表。

对象位于 `tenants/{tenantHash}/users/{ownerHash}/{documents|datasets|tasks|artifacts|context}/{assetId}/{sha256}`。增加租户层防止同名用户跨租户碰撞；路径中不包含用户上传的路径文本。对象 key、版本和身份均由 Java 生成，不接受客户端指定。

发布过程为：数据库锁定用户空间并预留配额 → 提交 `STAGED` 元数据 → 写入版本化对象 → 回读并核对长度/SHA-256 → 提交 `AVAILABLE`。只有 AVAILABLE 可下载或进入任务。对象传输故障保留 STAGED 预留；按相同用户、任务、Run、来源、文件名和内容重试会恢复同一身份，不重复计算配额。对象库与 SQL 不宣称跨系统原子提交。

同名文件内容改变产生独立资产身份，已有任务继续绑定旧版本。文件名仅用于展示和下载，不拼接存储路径。上传支持 PDF、DOCX、XLSX、CSV、Parquet、TXT、Markdown、JSON/JSONL；基础签名检测不等于完整内容验证或病毒扫描。实际解析由隔离分析过程完成，缺少解析依赖或文件无效不能作为空数据处理。

默认单文件 64 MiB、每用户配额 2 GiB，均可配置；实际上传还受既有网关与 Spring multipart 限制。分析选择限制为最多 8 个源文件、4 个历史上下文，总原始大小 16 MiB，同时受现有 Coding 输入预算限制。原文件按 1 MiB 分块进行 Base64 传输，避免单条记录超过已有快照限制；Python 必须验证每块、顺序、完整文件长度和 SHA-256 后解析。Base64 是现有快照协议上的兼容桥接，会增加传输体积，不是业务数据模型。

用户可删除自己的非任务文件，成功后释放配额；删除有任务绑定的快照、代码和证据返回 409，保留追溯链。原始上传、任务快照和上下文分别登记。当前没有后台按期限清理证据、自动归档分层或配额管理界面；任务资产默认保留。上传崩溃产生的未登记 S3 版本需依据 SQL 登记做运维核对，不能按对象前缀直接批量删除。服务端不自动清理旧版业务文件。

## 访问控制与分析流程

接口基址：`/api/v1/data-science/user-assets`，沿用数据库维护的 `capability:data-science` API 权限。身份来自现有登录过滤器；用户列表、下载、删除再次按服务端 tenant/user 查询，不允许管理员绕过文件所有权。下载通过有身份验证的 Java 接口，返回 attachment、no-store 和 nosniff，不签发可转交的裸对象 URL。

| 方法与路径 | 用途 |
| --- | --- |
| `GET /space` | 当前用户的配额与已预留空间 |
| `GET /?kind=&taskId=&limit=` | 当前用户的资产，最多 100 条 |
| `POST /` multipart `file` | 上传原始资料 |
| `GET /{id}` | 元数据，不返回 S3 key 和凭据 |
| `GET /{id}/content` | 经授权和完整性检查的下载 |
| `DELETE /{id}` | 删除无任务绑定的自有文件 |
| `POST /analyses` | 将显式选择的文件交给既有 Cline/Python 分析链路 |

分析请求包含稳定 `requestId`、`fileIds`、可选 `contextIds`、`instruction` 和可选已登记 `modelName`。Run 身份由服务端租户、用户和请求 ID 派生。Runtime 读取指定的不可变原文，构造任务快照，Cline 编程并经现有隔离 Python 执行，归集器校验执行证明、结果与引用；结构化报告、上下文和原始产物分别保存。

未选择的文档和上下文不会进入任务。系统模板不通过个人资产上传接口创建；上传代码不被视为可执行权限。任务文件读取另外要求 taskId 和 runId 精确匹配。Cline 不持有对象存储账号，Python 不挂载对象存储密钥；其网络隔离与既有流程相同。

停止的失败/取消执行也归档可用的清单与诊断，标记 `resultValidity=NOT_EVALUATED`。归档成功不等于业务验收通过；结果仍由既有 Coding/Evidence 协议裁决。

## 部署与配置

镜像固定为 `docker.io/pgsty/silo:RELEASE.2026-10-18T00-00-00Z`，注册表 digest 为 `sha256:2636fc4155d9a7e41ff58cdb65ddd13ba9304e67184e13c25bc5cb34016269d7`。本地 Docker 新镜像存储和服务器 Docker 展示的 image ID 不同；离线导出包的 config SHA-256 为 `c676f34789e6598af571b590a782499df279d0d1cca41600752826634420d0bb`，服务器须匹配这个内容身份。服务器不能访问 Docker Hub，因此实际使用本地拉取后离线导入。

服务端部署脚本为 `scripts/deploy-user-assets-stage.py`，验收为 `scripts/verify-user-assets-stage.py`。部署在现有 `/opt/runtime-os-docker-stage` 范围内，容器 `silo`，数据目录 `silo/data`。端口只绑定服务器 `127.0.0.1:9000/9001`；控制面 API 通过 Docker control 网络访问 `http://silo:9000`。控制台可通过 SSH 隧道访问，不需要向外开放根管理端口。

根管理员与 API 存储账号分离；启动时随机生成强密码，保存在服务器 `silo/secrets/`，不写入 Git。API 账号只访问 `runtime-assets/tenants/*` 对象和必要的桶查询，没有存储管理员权限。API 使用只读挂载的凭据文件，不使用 SILO root 密码。

配置项前缀 `chatchat.assets`：`enabled` 默认 false，`endpoint`、`bucket`、`region`、`access-key-file`、`secret-key-file`、`max-file-bytes`、`user-quota-bytes`。本轮文件快照复用已有 `chatchat.coding.input-files` 配置与共享目录；它是经哈希验证的沙箱交付缓存，SILO 是持久化源。

PostgreSQL 迁移为 `database/migration/postgresql/V20261021_01__runtime_user_assets.sql`。新表的所有者跟随已有 `runtime_evidence_entry` 的运行账号，避免 postgres 执行迁移后 API 没有权限。H2 测试使用同结构的 `assets/schema.sql`。本轮不宣称已经验证 MySQL 迁移、集群部署、高可用、备份恢复或所有文件格式的真实模型解析。

部署前检查父任务、Python 容器和 Cline 活跃会话；备份现有 API Jar、Compose 和 deployment.env。失败时恢复原配置并重建 API/node-proxy，恢复既有 worker 防火墙并重载网关。数据库新增表与用户对象保留，回退不删除证据。服务器根账号信息、模型密钥和会话 token 不进入此文档或版本库。

官方来源：[SILO 发布](https://github.com/pgsty/silo/releases)、[SILO 下载及容器启动](https://silo.pgsty.com/download/)、[MinIO Java SDK](https://github.com/minio/minio-java)。

## 验证记录

本地定向测试 **36 项通过、0 失败、0 错误、0 跳过**，包含真实固定版本 SILO、容器重启、H2 元数据、HTTP 参数绑定与下载、跨用户/租户访问、并发配额、重复上传、删除后重新上传、失败重试、内容篡改、任务/Run 绑定、缓存恢复及失败执行诊断。前端相关回归 18 项通过，前端构建和后端打包成功。完整仓库测试没有作为本轮放行依据，已有全量失败仍见此前 Runtime 重构报告；需要 SQLGlot 环境的 8 项 Workspace 测试在早期检查中按原条件跳过，不计入这 36 项通过。[本地记录](runtime-user-assets-silo-20261021-evidence/local-validation.json)。

服务器为本轮授权的 `192.168.195.221` 上现有隔离 Stage，页面入口为 `http://192.168.195.221:18100/#/w/data-science/dataScienceEnvironment`。展开“我的文件与分析记录”，本轮真实验收选择 `qwen3.7-plus`。界面可选择已登记模型，没有修改全局默认模型。默认模型的可用性与资产存储可用性分开验收。

真实任务 `analysis-coding-493d6ca5382c5b9ff407a21b10e40308f03855abf74844bf70ff3d95c8f361c0` 使用 qwen3.7-plus，3 次模型调用、1 份生成程序、1 次 Python 执行；SDK 观测耗时约 58 秒。原文件 SHA-256 为 `6ea9e5971c1c57ec46e6d7fbb647820180b697e7f8ba7166da9bffe230024f5a`。归档代码实际经 `runtime_input.load_rows` 读取快照、解码 Base64、校验哈希与长度，使用 `csv.DictReader` 和 `Decimal` 计算；结果 **2 条记录、42.75 CNY**，不是把预期总额写进程序。14 个归档对象逐一下载并验证 SHA-256，覆盖 CODE / DATASET / LOG / ARTIFACT / CONTEXT，六项 Evidence 引用存在。[真实分析记录](runtime-user-assets-silo-20261021-evidence/analysis-acceptance.json)。

基础服务器验收通过：跨用户读取/元数据/删除隔离、伪造 userId 无效、匿名下载拒绝、不同所有者不同 ID、重复上传幂等、删除后重传新身份、SILO 重启后原文一致以及私有桶。[基础验收](runtime-user-assets-silo-20261021-evidence/foundation-acceptance.json)。临时验收账号和角色已删除；Node 无法连接 SILO，也未挂载存储凭据；API 存储账号调用管理员接口被拒绝。[账号与边界验收](runtime-user-assets-silo-20261021-evidence/finish-acceptance.json)。

最终前端使用真实 Chromium 连接服务器，上传、下载字节一致、模型选择、勾选和删除通过，浏览器异常及资产 API 错误均为 0；这次浏览器检查不再提交模型任务，真实模型/Python 结果以独立分析记录为准。[浏览器验收](runtime-user-assets-silo-20261021-evidence/browser-acceptance.json)。最终 API 重建后再次逐一读取原始文件及成功任务的 14 个对象，哈希仍一致。[重启留存核对](runtime-user-assets-silo-20261021-evidence/restart-retention.json)。

本次生成的程序在 Python 沙箱内真实执行；Workspace 代码生成功能仍维持“生成与解析不等于执行 SQL”的原边界。业务通用验证状态为 INCONCLUSIVE，简单夹具的独立算术核对不代替通用业务验收。计费遥测未完成，不把账本 consumed.costAmount=0 解释成模型免费或未计费。

两次先行模型尝试保留，没有替换成成功样本：

- `analysis-coding-3d0afcf1866ed99919683e1a2e25cb830cd9bc8f4fc36b7a9183b220a945ab6b` 的 qwen3.8-max 在 SDK 侧 300 秒超时，没有提交程序；晚到模型回执由原协议归集，Durable Task 最终为 FAILED，启动操作已协调。
- `analysis-coding-9a5ce4ee4d19aa665b7cb6b10d5fdff07990888d81abc921b3b315216164d4f4` 的 deepseek-v4-flash 返回 `Insufficient Balance`，没有代码执行。既有模型协议没有记录可用于结算的该失败回执，Durable Task 保留 CANCEL_REQUESTED / reconciliationRequired，预算预留保留。**这是本轮仍未关闭的协调项**；没有篡改账本、伪造停止证明或把未知用量清零。

收尾时物理 Cline 活跃会话为 0、Python 执行容器为 0；成功任务 COMPLETED / executorStopped=true / reconciliationRequired=false。这不等于所有先行失败任务都已经完成协调。[完整任务与模型观测](runtime-user-assets-silo-20261021-evidence/task-control-summary.json)。因此本轮确认核心存储/分析闭环通过，不宣称全量生产门禁通过。

最终包基于定向测试通过的后端 Jar `117203efb33b44b97eb5072428723d934cb48ffd752f381552e2c1c8016e1474`，使用仓库既有组包工具替换新构建的前端；逐项确认 1125 个非前端归档项字节未变，包含全部后端类和依赖。[组包核对](runtime-user-assets-silo-20261021-evidence/module-package.json)。精确最终包和部署镜像见 [部署记录](runtime-user-assets-silo-20261021-evidence/deployment.json)。

原始设计前的 API 和配置备份仍保留在 `/opt/runtime-os-docker-stage/evidence/user-assets-silo-20261021/backup-1792545829`；后续每次候选也各有备份。回退到原始版本时恢复该目录的 Compose / deployment.env 并仅重建 API / node-proxy，再恢复既有 worker 网络限制、重载网关；保留 SILO 数据和新增表。浏览器截图、完整响应、生成源码、会话凭据和原始错误保存在受控 evidence 或本地忽略的 target 目录，不提交版本库。
