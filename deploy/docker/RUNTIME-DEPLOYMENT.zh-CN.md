# Runtime OS 统一离线部署与现场验收

本入口复用原有 Compose，不合并应用和基础设施的维护边界。`runtime-deploy.py` 只使用 Python 3.9+ 标准库和现场 Docker Compose v2，不执行 pip、镜像拉取或构建。

| 模式 | 入口模板 | 行为 |
| --- | --- | --- |
| 全量新装 | `runtime-deployment.full.example.json` | 分别启动基础设施、可选独立 Temporal、应用；仅允许明确的新安装和空卷 |
| 现有环境接入 | `runtime-deployment.example.json` | 仅部署应用，独立验证既有数据库和 OpenSearch；保留原 Compose 的数据挂载、License 和网络隔离 |

现场验证记录及未满足条件见 [2026-10-28 交付报告](../../docs/unified-runtime-deployment-20261028.md)。`check` 成功只代表技术检查完成；只有 `verify` 的十二项业务断言全部通过才能验收。模板中的 `acceptance: {}` 会阻断业务验收，不能当成成功配置。

## 运行依赖矩阵

以下依据配置类、资源文件和真实请求验证，不以 README 作为配置来源。

| 服务/能力 | 必须依赖 | 条件或可选依赖 | 既有环境的处理 |
| --- | --- | --- | --- |
| API / Runtime OS | API 业务数据库、MCP、有效 License/身份、选中模型 | 检索后端、Python 环境与 Coding 随所用能力需要 | 保留模型配置、任务表、挂载和数据库身份 |
| MCP | MCP 业务数据库、主机 License 标识、API 授权通信 | Python 执行需要 Docker socket、已发布 Python 环境及预装沙箱镜像 | 不初始化替代业务库 |
| News Runtime | 自己的数据库、内部签名凭据 | 完整新闻能力必须有 OpenSearch、采集源及调度；WSA 需要外网及有效凭据 | 支持保留明确指定的已有 H2 文件；也支持 MySQL/PostgreSQL，互不自动转换 |
| Market | MCP 内的模块，不是额外容器；金融数据存储及资产元数据 | 默认专用 LOCAL_H2、快照、RocksDB 查询缓存；PRIMARY 由现有配置显式选择 | 不把 MCP 业务数据库等同于金融专库存储；保存原目录 |
| 统一 web_search | MCP → News、内部认证；金融资产/新闻的本地召回 | 本地证据不足时由 News 调 WSA；结果缓存使用 OpenSearch | 保留一个公开入口、本地优先、已有工具授权规则 |
| Coding Agent / Cline | API → loopback sidecar → Node；共享调度密钥；模型配置/回调；执行环境 | Python 数据分析需 MCP 沙箱；Node monitor 有独立令牌 | 保持内部网络、回调、任务/日志和 monitor 数据挂载 |
| OpenSearch | TLS、CA、匹配 SAN 的域名、认证、持久化卷 | 新闻、搜索缓存、API/MCP 索引各自用途不同 | 复用索引前缀和已有卷，不重建集群安全配置 |
| Redis | 原部署的 ACL/密码及持久化 | 金融查询默认使用 RocksDB，不能把 Redis 当成所有检索缓存的必需后端 | 保留原缓存；不 flush，不切换缓存实现来通过测试 |
| Temporal | News 采集调度需要 Temporal | embedded 是 JVM 内存测试服务；生产持久化使用独立外部 Temporal | 可复用已有 target；新装可单独部署持久化 PG + Temporal |

## 代码实际读取的配置

Spring 环境变量采用 relaxed binding：属性路径去掉连字符，点转换为下划线并大写。不能想当然地为每个驼峰词加下划线。`NewsDeploymentConfigurationTest` 用真实 `SystemEnvironmentPropertySource` 验证绑定。

| 功能 | 实际配置/来源 | 默认与注入 |
| --- | --- | --- |
| API/MCP 数据源 | `datasource-postgresql.yml` / `datasource-mysql.yml`、`spring.datasource.*` | 入口按每个服务的 engine 选择驱动、配置导入、URL、用户名；密码 configtree `spring.datasource.password`；JPA validate |
| News PG | 新 `application-postgresql.yml` | `CHATCHAT_NEWS_POSTGRESQL_URL/USERNAME/PASSWORD`；统一入口也注入通用 datasource 属性；不缺省连接新库 |
| News MySQL/H2 | 原 `application-mysql.yml` / `application-h2.yml` | 明确选择 profile；existing H2 必须指定并检查旧 `.mv.db`，不自动迁移 |
| News OpenSearch | `NewsRuntimeProperties.openSearch` | 类默认 disabled、HTTP localhost；资源 YAML 启用但原有 insecure-ssl；入口显式 enabled=true、HTTPS、insecure=false |
| News OS 环境 | `CHATCHAT_RUNTIME_NEWS_OPENSEARCH_ENDPOINT/USERNAME/INSECURESSL/INDEXNAME/RETENTIONDAYS` | `open-search` 整段绑定为 OPENSEARCH；密码文件目标 `chatchat.runtime.news.open-search.password` |
| 日索引 | `chatchat.runtime.news.open-search.index-name/retention-days/retention-cron/zone-id` | `runtime-news-yyyy.MM.dd`；7 天、`0 15 0 * * *`、Asia/Shanghai |
| WSA | `chatchat.runtime.news.web-search.*` | endpoint `https://wsa.tencentcloudapi.com`、mode=0、请求超时10s；secret-id/key 用 configtree；不发送付费 Cnt 参数，除非原配置显式启用 |
| WSA 缓存 | `web-search.cache.*` | enabled=true，`runtime-web-search-cache`、7天、相似度0.72、`0 45 0 * * *`；入口强制保留缓存能力，不强制绕过缓存 |
| 本地优先 | `web-search.minimum-local-results/maximum-local-query-terms` | 3 / 8；外搜需要分析过的 queryTerms、keywords 或 intent，原始 query 不足以触发外搜 |
| MCP → News | `chatchat.mcp.news-runtime.base-url` | 资源默认 localhost:8091；容器入口覆盖 `CHATCHAT_MCP_NEWS_RUNTIME_BASE_URL=http://news:8091`；原调用超时20s保留 |
| News → MCP | `chatchat.runtime.news.mcp-server.base-url` | 入口 `CHATCHAT_RUNTIME_NEWS_MCP_SERVER_BASE_URL=http://mcp:8090`，供真实 MCP 模型/分析通信使用 |
| 内部认证 | `chatchat.internal-credential.encrypted-secret/crypto-key-file` | 密文与解密主密钥分别只读挂载；News key=/run/secrets/news_internal_key；API/MCP 使用原 key 挂载；各端解密后凭据必须一致 |
| 金融存储 | MCP `chatchat.mcp.market.query-pool.*` | `CHATCHAT_MARKET_STORAGE=LOCAL_H2`；`CHATCHAT_MARKET_H2_JDBC_URL`、SNAPSHOT_ROOT 等见原 application-prod.yml；RocksDB 查询缓存默认开启、TTL1800s |
| 采集 | News `temporal.*`、news_source 的 enabled/schedule_cron | 默认 embedded，队列 chatchat-news-collection；活动最长7200s、重试3次；不是模型请求超时 |
| Cline | Compose `CODING_NATIVE_SDK_ENABLED` → Node `CLINE_NATIVE_SDK_ENABLED` | 默认改为 true；实际模型来自 API 当前选择，不能用部署模板内另一份模型冒充 |

代码定位：`chatchat-runtime-news/src/main/java/com/chatchat/runtime/news/config/NewsRuntimeProperties.java`、`chatchat-runtime-news/src/main/resources/application.yml`、`chatchat-mcp-server/src/main/resources/application-prod.yml`、`chatchat-runtime-market/src/main/java/com/chatchat/runtime/market/storage/FinancialDataStore.java`。原采集日志清理独立为7天、00:30，不能与 OS 日索引保留策略混淆。

## 配置分层和准备

1. 普通环境：复制 `application/deployment.env.example` 到安装目录的 config/deployment.env。填写版本镜像、端口、真实主机 NIC、各目录；原 api.env/mcp.env/news.env 保留。`prepare` 仅为新装创建缺失目录、空 env、普通空配置和代理配置，不生成业务数据、密钥或 License。
2. 密钥：`*_PASSWORD_FILE`、`*_SECRET_FILE` 和 manifest secrets 全部填写绝对文件路径。每个文件只有一个值。目录0700，root 服务可用0600；非 root Node/数据库进程使用合适组的0640并验证可读。不得使用世界可读权限。不要把私钥、原 docker inspect、渲染 Compose 或解密凭据纳入公开交付包。
3. 数据库：manifest 分别填写 API/MCP/News 的 engine、JDBC URL、username、password_file、client_image、expected_tables。可混用已有 PG/MySQL；驱动必须与 URL 一致。已有 H2 指向原文件；更换为 PG 是独立数据迁移项目，不能靠换 profile 完成。生产固定 `ddl-auto=validate`。
4. OpenSearch：填写 HTTPS endpoint、用户、密码文件、CA PEM、JVM truststore；域名必须匹配证书 SAN。truststore 从原 JVM 公共 CA 库复制后导入私有 CA，保留 WSA/模型 HTTPS 的公共根。`changeit` 只是公开信任库完整性密码，不是 OS 账户密码；信任库不含私钥。需要时在 manifest.extra_hosts 为 API/MCP/News 各自提供证书域名到现场 IP 的映射。probe_endpoint 是宿主机可达且 SAN 匹配的独立探测地址。
5. 外网供应商：WSA SecretId/SecretKey 独立文件；保留 enabled=true。缺少有效凭据时外搜验收 BLOCKED，不能通过关闭功能规避。
6. 内部通信：保留原加密凭据、对应 master key、Node/API dispatch secret、monitor token、MCP/API 授权配置；这些不是同一个秘密。API/MCP/News 的公共内部凭据解密后应一致，但密文可以由各自主密钥产生。统一模板的单一密文文件要求配套主密钥一致，已有环境应保留经核验的原密文/密钥，不能随意替换。
7. 数据和备份：manifest persistent_directories 记录原 API/MCP/News 目录；金融 existing_file 指向原 LOCAL_H2。保存 H2 快照、RocksDB、采集库、模型/License/密钥、Node 历史、OpenSearch 快照和索引 UUID、Redis 持久化。运行中的 H2 不能只复制单个文件充当一致备份。备份回执必须包含 `status: VERIFIED, restoreVerified: true`，并真实完成恢复演练。
8. 资源：manifest.resources 允许 mem_limit/cpus/pids_limit；基础设施沿用原 Compose 资源。Temporal 单独使用 TEMPORAL_MEMORY_LIMIT、TEMPORAL_DATABASE_MEMORY_LIMIT。根据数据量、并发与主机余量调整，不能延长任务总超时掩盖模型问题。

不要在现有环境运行新装初始化脚本。全量模式只对自己的空卷创建三个明确命名的业务库。已有模式永不建库/执行初始化 SQL。新装仍必须审查 API/MCP 初始化 SQL 是否匹配目标实体；缺少业务迁移时启动 validate 失败是阻断条件，禁止改成 update 后宣称生产迁移完成。

日索引及缓存保留参数必须沿用客户原策略。入口不清理既有索引，但运行中的原保留任务仍会按前缀清理过期日索引；上线前核实前缀的所有权和保留天数。测试使用独立前缀，不借用生产前缀。

## 离线交付

构建机准备匹配版本的六个应用/代理镜像及完整基础设施镜像，所有基础镜像、npm/Maven 输入和沙箱镜像在离线前准备好。`scripts/package-product-release.py --infrastructure-images-file FILE` 接受 `{"images":[{"tag":"postgres:16.9","imageId":"sha256:实际ID"},...]}`，核验本机身份并一并 docker save；不提供该参数的包只适合复用现场已有基础设施。Temporal 镜像及其 PG 镜像也应列入。

宿主机预装 Docker Engine/Compose v2、Python3；核验包 SHA256SUMS 和发行清单，先 `docker load`。manifest 的 image_ids、infrastructure_image_ids 必须是已验证本机镜像 ID；tag 本身不是内容身份。入口所有 up 都使用 --no-build --pull never。临时访问 registry、首次拉取沙箱或临时安装工具不属于离线交付成功。

保留解压后的目录结构，Compose 的 SQL、健康检查和代理脚本使用相对位置。密钥、授权 License、客户配置和证书私钥单独加密交付，不能混入镜像包。TLS CA 是公开材料，但需验证来源。

## 操作顺序

```bash
# 新装从 full 模板复制；接入已有环境从 existing 模板复制。
python3 deploy/docker/runtime-deploy.py prepare --manifest /secure/site.json --output /secure/rendered
# prepare 仅新装使用；配置及秘密准备完整后：
python3 deploy/docker/runtime-deploy.py plan --manifest /secure/site.json --output /secure/rendered
python3 deploy/docker/runtime-deploy.py check --manifest /secure/site.json --output /secure/rendered
python3 deploy/docker/runtime-deploy.py snapshot --manifest /secure/site.json --output /secure/before
# 填写真实备份回执；existing 另填 quiescence_probe.argv，输出 activeTasks:0,activeCollections:0。
python3 deploy/docker/runtime-deploy.py up --manifest /secure/site.json --output /secure/rendered
python3 deploy/docker/runtime-deploy.py verify --manifest /secure/site.json --output /secure/after --write-probe
```

全量新装的 check 在基础设施启动前只能检查本地文件/镜像；数据库、OS 检查在 up 后执行。snapshot 需要服务已存在。existing 的 check 在部署前就检查数据库/OS。输出目录私有，包含原 Compose 与可能含旧环境秘密的错误详情。`--write-probe` 仅写唯一 `runtime-deployment-probe-*` 测试索引并读回，不删除任何索引；记录它以便客户按策略管理。

应用与基础设施被渲染为两个文件，existing 不启动原 Compose 的 DB/Redis/OpenSearch。可用 `--services news` 缩小 existing 变更范围。更换 API 会重建共享其网络命名空间的 node-proxy 并 reload gateway。失败不自动回滚、不删卷；恢复前保留新任务记录，对照 `original.compose.private.json` 还原服务定义，代理命名空间也必须同步恢复。

## Temporal 持久化选项

推荐 existing 指向已运维的持久化 Temporal（mode=external、target/namespace/task_queue/tls_enabled），不部署第二套。新装可 provision=true，使用独立 `deploy/temporal/docker-compose.yml` 和专用 PG 卷/密码。已有 Temporal 升级需按其版本执行受控 schema 升级，不能重复 auto-setup。

新增 secret-entrypoint.sh 只在启动进程内读取文件密码到官方 POSTGRES_PWD，不修改依赖源码。SKIP_DB_CREATE/SKIP_SCHEMA_SETUP 是官方 auto-setup 选项，见 [entrypoint](https://raw.githubusercontent.com/temporalio/docker-builds/main/docker/entrypoint.sh) 和 [auto-setup](https://raw.githubusercontent.com/temporalio/docker-builds/main/docker/auto-setup.sh)；目标镜像的具体版本仍需离线预验。embedded 只有显式 allow_ephemeral_temporal=true 才允许保留，并持续报告持久化未验收。

## 自动检查与业务契约

自动检查覆盖 Compose 规范化、profiles、镜像 ID、Secret 完整性和容器用户可读性、绑定目录存在/可写、数据库连接及表契约、OS 认证和严格 TLS/读写。数据库没有统一迁移版本表时只报告 schema contract，不能捏造版本；使用 Flyway/Liquibase 的现场可在数据库条目配置 migration_ledger 为 flyway 或 liquibase，migration_version 为目标版本，入口会只读查询真实账本并比较，不一致即阻断。

`acceptance` 包含下面十二个同名键，每个是 HTTP 探测或 argv 数组执行的安装侧测试程序。测试程序需 stdout 输出 JSON，返回非零即失败；argv 无 shell 求值。HTTP 支持 url/method/headers_file/body_file/timeout_seconds，敏感请求留私有文件。expect 使用点路径断言；数值下限用 `{"minimum":1}`。不能把历史结果文件读取成功冒充新一轮业务执行。

| 契约键 | 必须断言的业务结果 |
| --- | --- |
| runtime_start | Runtime OS 已启动且任务服务可访问、License 有效 |
| mcp_registration | 真实授权上下文的 Runtime 工具目录包含 web_search；不是匿名 tools/list |
| mcp_news | Runtime → MCP → News 的调用成功且新闻命中非零 |
| news_collection | 专用测试源采集完成，不能只看提交成功 |
| news_search | 上一步的文章可检索且文本/源标识一致 |
| financial_search | 已有金融数据集、观测值/资产非零，不是空库健康检查 |
| news_opensearch | 同一文章在对应日索引可读写，认证和证书严格验证 |
| external_web_search | 有效凭据调用 WSA 成功，不使用仅缓存命中作为外网请求证据 |
| local_fallback | 本地证据不足，按原分析词/intent 规则得到外网补充 |
| runtime_web_search | 真实 Runtime 授权上下文执行统一 web_search，结果返回 |
| cline_model | 已选模型的 Cline 多轮调用与代码执行完成，结果正确 |
| data_preservation | 升级前后业务记录/原配置、索引 UUID、金融数据与缓存可用；允许新增正常业务记录 |

例如一个现场程序契约：`"cline_model":{"argv":["python3","/secure/acceptance/cline.py","--run"],"expect":{"state":"SUCCEEDED","expectedTotalReported":true,"modelCalls":{"minimum":1}}}`。这些程序必须实现真实调用，不能返回固定成功值。仓库已有 `scripts/verify-runtime-workbench-stage.py` 可作为 Cline 现场验收入口；登录状态、已发布模型和 Python 环境必须来自安装现场。不同客户的授权/数据不同，因此模板不伪造统一测试账号或数据集。

最终缺项会记录为 BLOCKED，verify 返回非零。容器健康、单条 HTTP 200、只读配置绑定测试都不等价于整套业务通过。
