# chatchat-runtime-news

详细文档：

- [设计文档](doc/design.md)
- [内部接口与工具契约](doc/contract.md)

独立部署的资讯采集与检索运行时。MCP 服务保留配置页面和工具注册，但不再加载本模块的 Bean、JPA Entity 或爬虫依赖。

## 启动

```bash
mvn -pl chatchat-runtime-news -am package
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar
```

数据库 Profile 可直接切换：

```bash
# 默认 H2 文件数据库
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar --spring.profiles.active=h2

# MySQL（先修改 application-mysql.yml 并执行初始化 SQL）
java -jar chatchat-runtime-news/target/chatchat-runtime-news-1.0.0-SNAPSHOT.jar --spring.profiles.active=mysql
```

生产发行包会在 `package` 阶段同时生成：

- `target/chatchat-runtime-news-1.0.0-SNAPSHOT-release.zip`（Windows/通用）
- `target/chatchat-runtime-news-1.0.0-SNAPSHOT-release.tar.gz`（Linux，保留脚本执行权限）

发行包包含 `bin/config/data/doc/lib/logs/run`、MySQL/H2 初始化脚本及外置生产配置。解压后填写
`config/application.yml` 和所选的 `config/application-h2.yml` 或 `config/application-mysql.yml`，再运行 `bin/start.sh` 或 `bin/start.bat`。生产 YAML 使用明确配置值，不使用环境变量占位符；
`config/env.properties` 只保留 JVM 启动参数。

默认端口为 `8091`，未指定 Profile 时使用 H2。生产环境至少配置：

- 在对应数据库 Profile 文件中填写数据库地址、用户名和密码。
- 在 `application.yml` 中填写 OpenSearch、内部账户和可选向量服务配置。
- MCP 与 News Runtime 两边使用相同的内部账户；生产环境应通过 TLS 传输内部凭证。
- MCP 侧 News Runtime 地址指向实际部署地址，例如 `https://news-runtime:8091`。

内部接口统一位于 `/internal/v1/news/**`，只接受内部 Basic 账户。News Runtime 不提供管理页面，也不对 Agent 直接开放底层采集参数。

数据库初始化脚本：`database/init/mysql/chatchat-runtime-news.sql` 或 `database/init/h2/chatchat-runtime-news.sql`。

从原 MCP/API 数据库迁移时，将 `news_source`、`news_source_rule`、`news_collect_record`、
`news_analysis_task` 四张表导入 News Runtime 的独立数据库，再启动两个服务。新版本的 MCP/API 初始化脚本不再包含这些表。

## 采集日志保留

`news_collect_record` 采集日志默认只保留 7 天。应用启动后会清理一次，之后默认每天 `00:30` 分批清理；可通过
`chatchat.runtime.news.collect-log` 下的 `enabled`、`retention-days`、`cleanup-batch-size`、
`max-batches-per-run` 和 `cleanup-cron` 调整。清理不会删除 OpenSearch 中的资讯正文或附件分片；由于该表也保存 URL
去重状态，日志过期后再次发现同一旧链接时允许重新采集。

## 公告附件与向量检索

News Runtime 会在公告详情页及公告列表中识别 `PDF`、`DOC`、`DOCX`、`XLS`、`XLSX`、`CSV` 附件。附件下载和解析使用独立受限队列，
解析后的文本按重叠窗口切片，再进入原有 OpenSearch Bulk 队列。每个分片保留 `parentDocumentId`、原公告地址、附件地址、
文件名和分片序号，方便模型引用原始证据。MCP 的资讯源配置页面可设置附件链接选择器和额外允许域名。

默认附件上限为单文件 25 MiB、每篇公告 20 个附件、每个附件 500 个分片；可通过
`CHATCHAT_NEWS_ATTACHMENT_*` 环境变量调整。附件下载会校验初始地址和重定向后的域名，不允许任意外部地址。
带文本层的 PDF、Word 和 Excel 可直接解析；没有文件扩展名的显式附件链接也会根据响应类型识别。纯扫描 PDF 需要另行部署 OCR 能力后再扩展。

向量化默认关闭。启用 OpenAI-compatible Embedding 服务：

```bash
CHATCHAT_NEWS_EMBEDDING_ENABLED=true
CHATCHAT_NEWS_EMBEDDING_ENDPOINT=https://embedding.example.com/v1/embeddings
CHATCHAT_NEWS_EMBEDDING_API_KEY=replace-me
CHATCHAT_NEWS_EMBEDDING_MODEL=text-embedding-v4
CHATCHAT_NEWS_EMBEDDING_DIMENSION=1024
```

开启后，每日索引会创建 `knn_vector` 字段，资讯正文与附件分片都写入向量。查询同时执行 BM25 与 kNN，使用 RRF
融合两个候选集；Embedding 服务或 kNN 不可用时自动退回 BM25。修改模型维度后必须使用新的索引名称或等待旧每日索引过期，
不能在原有 `knn_vector` 字段上直接改变维度。

## 新闻证据字段

每条入库资讯强制校验非空标题和绝对 HTTP(S) 来源地址，并同时在元数据中固化 `evidenceTitle`、`evidenceUrl`。
`web_search`、`news_search`、`news_latest` 的每条结果都返回统一的 `evidence` 对象，至少包含原新闻标题、原始 URL、
来源名称和发布时间；附件分片还包含附件文件名与附件 URL。工具响应顶层同时返回去重后的 `reference_urls`，模型生成总结时
应保留这些地址作为可核验引用。
