# OpenSearch 与外置驱动持久化

## 改动与配置

- 原生 OpenSearch / Elasticsearch 客户端支持按完整资产地址匹配私有 CA PEM，可指定证书 DNS 身份，仍验证证书链及名称。其他地址保留默认 JVM 信任规则。CA 内容、证书名称进入连接池指纹，更新后重建客户端。
- 连接成功后通过 [Resolve Index API](https://docs.opensearch.org/latest/api-reference/index-apis/resolve-index/) 发现普通索引、别名、数据流，返回 `diagnostics.metadataScopeOptions` 并复用现有范围选择器。过滤点号、hidden/system 索引；缺少发现权限返回警告，连接仍报告成功。
- 保存范围后复用现有 Mapping / Field Caps 采集器，支持嵌套字段、多字段、向量类型、别名和字段检索/聚合能力，不采集业务文档。
- Compose 的 API / MCP 只读挂载主机整个驱动目录，保留 `oracle/`、`dm/` 等类型子目录与依赖。禁止自动创建缺失的主机目录，驱动不打入镜像。

`deployment.env.example` 新增 `JDBC_DRIVERS_DIR` 和 `NATIVE_SEARCH_TLS_DIR`。先创建目录，将驱动与依赖完整保留在 `JDBC_DRIVERS_DIR/<数据库类型>/`；复制 `native-search-tls.example.yml` 为 `NATIVE_SEARCH_TLS_DIR/application.yml`，并放入可信 CA PEM。策略 URL 必须与资产地址一致；名称不同时配置 `server-name`，仍执行名称校验。公开 CA 连接可使用空配置目录。

服务器沿用 `/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers`。TLS 配置在 `/opt/runtime-os-docker-stage/config/native-search-tls`，仅适用于指定的 OpenSearch 地址，证书身份为 `node-0.example.com`，未全局关闭校验。

## 验证与部署

- 针对实际已部署依赖编译变更类，27 项 JUnit 测试通过，覆盖私有 CA、正确/错误证书名称、地址隔离、连接协议、元数据及类型子目录驱动加载。未执行完整 Maven reactor 构建，本地已有依赖缺失，见前序修复记录。
- 服务器上 3 项真实 Compose 配置测试通过，验证持久化目录、只读驱动/证书挂载、服务依赖。
- 已部署 `runtime-os-mcp:opensearch-drivers-20261102-r1`，仅替换 7 个变更或新增 class，其余后端、前端及依赖保留；API 镜像未变。备份和报告在 `/opt/chatchat-releases/opensearch-drivers-20261102-r1`。
- 浏览器实际点击连接测试成功，发现 176 个范围候选并验证选择交互。资讯资产原范围为空，已配置为 `runtime-news-*`；实际刷新 7 个索引、776 个字段，RocksDB 报告 `PERSISTED`，快照查询成功。
- 重建后，两应用内 8 个驱动 JAR 的 SHA-256 与主机相同。MCP 实际加载 Oracle、达梦、Kingbase、OceanBase、MySQL、Inceptor、SQL Server、DB2 驱动成功；5 个现有 MySQL 连接和 Oracle 连接测试成功。
- 达梦资产的主机 5236 端口未监听，仍返回通信错误，驱动类已成功加载。未更改该数据库服务或连接配置。

浏览器回归：设置 `E2E_MCP_PASSWORD` 后运行 `node scripts/verify-opensearch-live.mjs`，证据保存在 `target/opensearch-drivers/`。脚本测试连接、只读元数据并编辑未保存的范围，不保存资产变更。
