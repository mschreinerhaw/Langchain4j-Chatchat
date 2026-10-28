# Runtime OS 部署配置修复与现场验证（2026-10-28）

已完成代码核对、统一离线入口、配置修复及现场隔离验证。**尚未完成生产切换，也未证明完整新装拓扑已全部上线。** 现有生产业务链路多项实际可用，不能根据原部署定义的缺口推断它们全部不可用。

目标仓库 `mschreinerhaw/Langchain4j-Chatchat`，本次基于本地分支 `refactor/runtime-os-review-cleanup-20261026`、HEAD `338f2f935` 的最新已同步代码和既有工作区改动。先前交互页面与模型请求超时改动保留；本次未把整套未发布 API 改动直接覆盖生产。

部署操作、依赖矩阵、配置真实读取键、默认值和注入机制统一记录在 [部署手册](../deploy/docker/RUNTIME-DEPLOYMENT.zh-CN.md)。入口为 [runtime-deploy.py](../deploy/docker/runtime-deploy.py)，提供 full / existing 两种模式，分别渲染基础设施和应用文件。下面是本次交付状态，而非新装成功声明。

## 原有问题与修复

| 问题 | 修复 | 实际验证范围 |
| --- | --- | --- |
| MySQL 基础设施与 PG 应用定义分开，容易重复部署或误用数据库 | 保留原入口，新增统一 manifest/编排入口；existing 只启动应用 | 现场既有配置规范化、数据库只读连通和 Schema 合同通过；PG/MySQL 定义均进行 Compose 检查 |
| News 缺少 PG 驱动/profile/schema，应用 Compose 未完整传入数据库与秘密 | 增加 PG 支持及四表 SQL；仅空卷初始化；独立密码文件、validate | 真实 PG18 中新建专用验收库、JPA validate 启动、采集 INDEXED 记录验证通过 |
| News OS 连接/认证依赖外部配置，旧配置使用 insecure SSL | 文件密码 + configtree、严格 TLS、保留公共根的 JVM truststore、匹配 SAN 域名 | 新 News 镜像隔离部署中严格证书校验成功；生产原 News 配置未切换 |
| 只读 config 目录中嵌套 key/truststore 挂载可导致启动失败 | key 移到 /run/secrets/news_internal_key，truststore 独立 /run/runtime-truststore.p12 | 实际容器启动验证通过 |
| News WSA/缓存配置不完整、外网失败可能只靠关闭能力规避 | 保留 WSA/cache 启用；凭据独立 Secret；缺失时验收 BLOCKED | 有效现场凭据下真实 WSA 返回3条；后续缓存命中；本地不足时按 intent/分析词补充 |
| 金融数据目录容易与 MCP 主数据库混淆 | 明确 Market 是 MCP 内模块；existing 强制检查原 LOCAL_H2 文件与持久化路径 | 现有金融数据召回1个数据集、20个观测值，未替换金融数据库/缓存实现 |
| embedded Temporal 被当作持久化服务 | 明确警告并要求显式确认；新增独立持久化 Temporal/PG 选项 | 定义规范化检查；现场没有启动持久化 Temporal，仍属上线缺项 |
| Temporal Compose 中内联数据库密码 | 文件 Secret 和进程内官方 POSTGRES_PWD 注入包装 | Compose 检查；目标 Temporal 镜像实际启动仍待验 |
| 离线包缺少完整基础设施镜像的显式输入 | 增加基础设施镜像清单参数与本机 ID 校验，沿用 docker save | 代码与语法检查；未在现场启动完整离线新装环境 |
| docker up 健康即被当作业务成功 | 十二项业务契约，缺项/错误返回非零；分别报告技术检查和业务验收 | 缺项阻断和结果下限断言有自动测试；现场链路证据见下 |

## 现场证据

服务器通过用户指定 SSH 地址验证，发布暂存目录 `/opt/chatchat-releases/unified-deployment-20261028`。秘密、原 Compose、原始请求/回答仅保存在受限私有目录；公开证据不包含密码、签名、模型密钥或私钥。

| 验收项 | 结果与证据 |
| --- | --- |
| Runtime OS 正常运行 | 原 API 容器保持运行；真实任务 `a361f83c-85b9-30a4-867f-cf331d4913db` 最终 SUCCEEDED，131条事件 |
| MCP 注册和调用 | Runtime 授权工具目录33项，统一 web_search 1项；Runtime → MCP → News SUCCESS。匿名 MCP tools/list 受发布/授权上下文约束，不能用其空列表判断内建工具不存在 |
| News 采集与检索 | 原服务37个源、现有新闻能召回。隔离新镜像 RSS 源采集 COMPLETED，PG 有1条 INDEXED，新闻检索命中1条 |
| OS 存储与查询 | 隔离 News 使用专用 index/cache 前缀、严格 TLS 和认证，实际写入/检索成功；宿主机严格 TLS 探测看到172个索引，集群 yellow，非 red |
| 金融召回 | 经 Runtime 统一 web_search 返回 financialDatasetCount=1、financialObservationCount=20、financialAssetCount=1 |
| WSA 实际外网 | 本地0条、cacheHit=false 的请求得到 externalWebCount=3、externalSearchSucceeded=true；后续请求 cacheHit=true |
| 本地优先/不足补充 | 本地充分时 newsIndexCount=3、externalWebCount=0；不足时0条本地证据、3条外网补充，原规则保留 |
| Cline 已配置模型 | 原已发布模型选择未改；4次模型调用、3次工具调用、2次代码执行，任务返回 CSV 1.25+2.50 的实际值3.75，Coding 子任务 COMPLETED。子任务 validationStatus=INCONCLUSIVE，不能宣称它自身全部验证项 PASS |
| 原数据/配置保护 | 六个原业务容器 ID、镜像 ID、按目的路径比对的全部挂载与采集前一致，仍在运行；现有新闻、金融数据和搜索缓存能读取。没有生产数据库迁移/容器替换，因此这不是“升级后恢复演练通过”的证据 |

隔离验收项目 `runtime-deployment-validation` 使用独立 PG/Feed/News 容器与专用索引，未复制生产业务库。验证后已停止这些测试容器，保留其数据和证据；没有对原业务索引执行删除操作。

公开证据目录：[unified-runtime-deployment-20261028-evidence](unified-runtime-deployment-20261028-evidence/)。`live-probes.json` 为当前业务实际响应摘要，`isolated-news-verification.json` 为新镜像采集/PG/TLS链路，`cline-verification.json` 为真实执行结果，`preservation.json` 为原容器及挂载一致性，`checks.json/preflight.json` 为统一入口现场检查。

## 自动测试结果

| 测试 | 结果 | 范围/限制 |
| --- | --- | --- |
| News 模块 Maven 测试 | 102 tests，0 failures/errors/skipped | 包含绑定、PG四表幂等兼容、采集/检索/缓存等原有回归；不是完整 Runtime OS 全模块回归 |
| 统一部署入口 Python | 11通过 | 数据库类型区分、已有H2约束、空库替代阻断、挂载/网络保留、TLS配置、profiles/extra_hosts、网关地址、迁移版本不匹配、业务契约缺项与结果不足阻断 |
| 现场 Compose/Nginx | 11通过 | PG应用及MySQL基础设施/Temporal定义、数据目录、认证与隔离、健康依赖无环、Nginx语法、动态DNS和跳转端口 |
| 新 News 实际部署 | 通过上述隔离业务链路 | 使用预载旧镜像作为构建基础，离线 --network none --pull=false 覆盖新JAR；没有拉取镜像完成构建 |
| 统一入口现场 check | 通过，业务验收标记 NOT_RUN | API库120表、MCP库63表，配置要求的表存在；现有News H2文件存在。无统一迁移版本账本，因此不虚构迁移版本 |
| Cline Runtime 实测 | SUCCEEDED，预期结果正确 | 使用现有生产模型与沙箱，新增一项验收任务记录，未更改原模型配置 |
| 其他检查 | Python编译、git diff --check通过 | 没有把整个项目原有测试失败或未执行测试计为通过 |

## 修复文件清单

本次部署工作涉及以下文件；工作区另有前序任务改动，不属于本次部署修复清单。

- `deploy/docker/runtime-deploy.py`：统一入口及安全前置/技术检查/业务契约。
- `deploy/docker/runtime-deployment.example.json`、`runtime-deployment.full.example.json`：已有接入和全量新装模板。
- `deploy/docker/RUNTIME-DEPLOYMENT.zh-CN.md`、`README.md`、`application/README.md`：代码依据、完整配置分层及部署操作。
- `deploy/docker/docker-compose.yaml`：保留MySQL入口，允许外部Secret/证书路径。
- `deploy/docker/application/compose.yaml`、`deployment.env.example`、`postgresql-init.sh`：News PG/配置/认证/TLS、空卷News初始化及Cline启用参数。
- `deploy/docker/application/tests/test_unified_deployment.py`、`test_application_deployment.py`、`test_infrastructure_bootstrap.py`：新增检查及既有测试输入适配。
- `deploy/temporal/docker-compose.yml`、`secret-entrypoint.sh`：独立持久化选项及文件密码。
- `chatchat-runtime-news/pom.xml`、`src/main/resources/application-postgresql.yml`：真实PG驱动和profile。
- `chatchat-runtime-news/src/test/java/com/chatchat/runtime/news/config/NewsDeploymentConfigurationTest.java`：真实环境变量绑定及schema测试。
- `chatchat-runtime-news/src/test/java/com/chatchat/runtime/news/search/cache/OpenSearchWebSearchCacheSpringContextTest.java`：引入真实查询优化配置，修复原测试上下文缺少所需bean。
- `database/init/postgresql/chatchat-runtime-news.sql`：News专属四表，不混入Market/Runtime控制面。
- `scripts/package-product-release.py`：离线基础设施镜像及统一部署入口随包交付。
- 本报告及公开证据目录。

## 缺失配置与未满足的现场条件

1. **生产未切换**：现场原 News 有运行中的采集任务，embedded Temporal 重启会丢失内存工作流状态。入口要求 Runtime任务/采集任务都为零的真实 quiescence_probe，不能直接重建。
2. **备份与恢复演练未执行**：未提供可验证的生产数据库、索引、H2/缓存及配置恢复回执，入口 up 会阻断。这是数据保护条件，不是新增测试密码要求。
3. **生产严格TLS变更待切换**：新镜像已验证，但原 News 仍是旧 insecure-ssl 配置。准备好的证书域名/CA信任库需要在安全切换时应用，不能把隔离结果等同于生产配置已经修改。
4. **持久化 Temporal 未上线**：配置选项已提供；现场仍 embedded。必须先准备对应离线镜像/版本、密码、持久卷与恢复策略，再验证服务实际启动、工作流跨重启恢复和现有调度迁移。
5. **完整离线镜像集合/新装验收未完成**：现场只对既有基础设施接入和隔离News做实际运行。没有另起完整PG/MySQL/Redis/OS新装来冒险影响现有端口/资源。MySQL整套实际启动、Temporal目标镜像、完整全量首次业务迁移仍待独立环境验收。
6. **站点业务契约仍需落地**：两份通用模板不携带客户账号、授权Token或固定数据集；acceptance为空时verify明确失败。现场手工编排了真实调用测试并留证，但尚未把十二项安装侧验收程序全部配置进生产manifest。不能宣称统一入口已自动通过十二项完整验收。
7. **迁移版本与升级保全**：当前检查验证真实连接/表契约；没有全局版本表时只报告该事实。若现场使用Flyway/Liquibase，应配置 migration_ledger 和 migration_version，入口读取实际ledger并比较。已有业务数据的正式升级与恢复演练必须在切换后补齐。
8. **外网与新装业务数据**：WSA当前凭据可用，但完全断网环境不能执行外网搜索验收；不得关闭功能来通过。全量新装还需要合法License、已配置模型、授权能力、Python环境、新闻源和金融数据导入，空容器不会自行具有这些业务资产。

没有删除或覆盖原业务库、索引、金融数据及缓存；没有关闭News、Web Search、OpenSearch或金融检索来通过测试。最终上线结论仍受以上明确条件约束。
