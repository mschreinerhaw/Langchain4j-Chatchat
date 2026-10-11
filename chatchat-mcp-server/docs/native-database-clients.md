# MCP 内置原生数据库客户端

数据库资产的 `driverClass` 是 MCP 原生客户端选择标识，不是外置 JDBC 类名。三种官方客户端通过 Maven 依赖随服务打包；查询、连接测试和元数据采集共用 `NativeDatabaseClient`，不再使用自建 `java.net.http.HttpClient` 查询驱动。

| 数据库 | 驱动标识 | 客户端 | 连接地址 |
| --- | --- | --- | --- |
| Neo4j | `neo4j-native` | `org.neo4j.driver:neo4j-java-driver:5.28.4` | `bolt://host:7687` 或 `neo4j://host:7687`，支持 `+s`、`+ssc` |
| OpenSearch | `opensearch-native` | `org.opensearch.client:opensearch-rest-client:2.12.0` | HTTP(S) 基础地址，可带网关路径前缀 |
| Elasticsearch | `elasticsearch-native` | `org.elasticsearch.client:elasticsearch-rest-client:8.15.3` | HTTP(S) 基础地址，可带网关路径前缀 |

Neo4j 5.28.4 使用 Bolt 连接组件 1.1.0，其 Netty 依赖属于 4.1 系列，与项目 Spring Boot 3.5.3 管理的依赖兼容。本次没有升级整个项目的 Netty 系列。

## 执行和资源管理

搜索 DSL、Mapping、别名和 Field Capabilities 通过对应厂商的官方 REST Client 执行，保留当前 JSON DSL 能力以及任意响应字段，不将 OpenSearch 查询转发给 Elasticsearch 客户端。Neo4j 使用官方 Java Driver 的 Bolt 协议、指定 Database、READ 会话、参数绑定和带超时的只读事务，固定内部元数据过程也通过该驱动执行。

客户端注册表最多保留 64 个活跃客户端，每个客户端使用官方连接池。连接配置、认证或超时变更后替换客户端；租约确保旧客户端不在请求执行中被关闭。容量统计包含尚未释放的旧客户端，忙碌时明确拒绝新增而不是无界创建。空闲客户端按最近使用顺序淘汰；应用关闭时释放资源。

MCP 排除 Spring Boot 的 Neo4j 和 Elasticsearch REST 默认客户端自动配置，避免仅因新增驱动依赖就创建未配置的 localhost 客户端。连接只来自资产配置和本次客户端管理器。

已有 MCP OpenSearch 的 `chatchat.mcp.lucene.open-search.insecure-ssl` 设置只在资产地址与该设置的 `url` 完全一致时沿用，包括协议、端口和路径。它不影响其他 OpenSearch 地址或 Elasticsearch 客户端。默认验证证书；私有 CA 可通过 JVM truststore 配置。客户端缓存指纹包含实际 TLS 策略，修改该策略后会替换连接。

搜索客户端设置每次请求的连接、连接池等待和读取超时，并在请求总等待到期或线程中断时取消异步请求。Neo4j 设置连接、连接池等待和服务端事务超时，关闭自动事务重试以免隐式重试扩大执行时间。Neo4j 节点、关系、路径、日期和空间值转换到既有结果契约；图结构使用 element ID，避免依赖已弃用的数值 ID。

原生资产误入 JDBC 执行或交易日 SQL 读取时会明确拒绝，不尝试动态加载伪驱动。关系数据库的外置 JDBC 驱动机制保留。

## 已有配置迁移

OpenSearch/Elasticsearch 的旧 `-http` 标识仍被识别，保存时统一转成 `-native`，HTTP(S) 地址和数据库资产中的账号、密码无需改变。旧 HTTP 检索资产也兼容：`search_engine` 分类明确表示 OpenSearch，Elasticsearch 使用 `elasticsearch` 分类。

Neo4j 的旧 `neo4j-http` 标识可以识别，但 HTTP 事务地址不再执行；需要将地址改成服务真实的 Bolt/neo4j URI。不要把 `7474` 或任何自定义 HTTP 端口直接当成 Bolt 端口，不自动猜测端口映射。建议在数据库资产配置中维护账号、密码和 Database 范围。Bolt 使用账号、密码认证；旧 HTTP Bearer 请求头不能直接转换成 Bolt 认证。

只读会话是路由和执行模式，不代替数据库账号权限。继续使用已有只读账号和业务查询验证器。

官方资料：[OpenSearch Java 客户端](https://docs.opensearch.org/latest/clients/java/)、[Elasticsearch Java 客户端](https://www.elastic.co/docs/reference/elasticsearch/clients/java)、[Neo4j Java Driver 事务](https://neo4j.com/docs/java-manual/current/transactions/)。

## 验证与发布包

12 个定向测试类共 77 项测试通过，覆盖官方搜索客户端的实际 HTTP 请求、认证轮换、超时取消与后续请求恢复、TLS 策略作用范围、租约和容量控制、Bolt 驱动加载、只读事务及图结构转换、连接探测、元数据采集、查询工作流、JDBC 隔离、SQL 和交易日回归。Neo4j 成功查询的结果转换使用 Driver 测试替身；实际 Bolt 驱动验证连接不可用时的异常和依赖加载，不等于已经完成生产 Neo4j 成功查询验收。

MCP 管理页面 `npm ci` / `npm run build` 通过；Maven reactor 定向测试和最终 `package` 通过。发布包为 `chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT.jar`，确认包含三种官方客户端、Neo4j Bolt 连接组件 1.1.0 和项目管理的 Netty 4.1.122.Final，不包含旧 `QueryHttpClient` 类。

## 2026-10-11 生产更新

生产只读资产清点发现一个启用的 OpenSearch 资产，没有 Neo4j 或 Elasticsearch 数据库资产，也没有旧 HTTP 图库/检索资产。现有 OpenSearch 地址与 MCP OpenSearch 配置完全一致，且操作员已配置 `insecure-ssl=true`；本次仅在该地址沿用现有设置，没有修改数据库资产、认证或服务配置。

部署保留当前生产依赖和其他功能，仅替换 14 组相关主类及其内部类、添加官方 Elasticsearch/Neo4j 依赖、更新管理页面，并删除旧 HTTP 查询客户端；同时更新 Boot classpath 索引。原 JAR 备份为 `/opt/chatchat-deploy-backup/native-clients-20261011/mcp.jar`。

- 更新前 SHA256：`7140f9b1c6c9f62987f12b277a7103a851556bceb84a5849e6f5bbd081d9c02f`。
- 更新后 SHA256：`cf986ed3c85b892df5951a6399f328dcadb688456788775e2db77ce26f45b9e7`。
- 独立验证进程使用待发布 JAR 的客户端对现有 OpenSearch 资产执行只读连接探测，成功，420ms；部署后使用实际生产 JAR 再次成功，439ms。资产未配置精确索引范围，未为了验收而扩大查询范围。
- 重启前 RUNNING、WAIT_MODEL、WAIT_TOOL、QUEUED、PENDING 用户任务均为 0。旧服务按现有停止脚本在 30 秒后强制退出；新进程 PID `2096493`，日志确认应用启动完成、gRPC 9091 启动。
- MCP 管理页面 HTTP 200、API 认证后的健康状态 UP；从 API 重新发现 MCP 工具成功，恢复 30 个工具。

生产暂未配置 Neo4j/Elasticsearch 资产，因此二者的验证限于回归夹具、官方驱动依赖及发布包检查。将来启用 Neo4j 时需要填写真实 Bolt 地址。
