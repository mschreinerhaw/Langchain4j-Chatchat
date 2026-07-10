# LangChain4j ChatChat

LangChain4j ChatChat 是一个基于 Spring Boot、LangChain4j、Vue 3 和 MCP 的企业级 AI Chat/RAG/Agent 应用。项目同时提供主应用 `chatchat-api` 和独立 MCP 工具服务 `chatchat-mcp-server`，用于支持大模型对话、知识库检索、Agent 编排、工具调用、MCP 服务管理、企业组织权限和生产环境独立部署。

## 核心能力

- 对话服务：普通 LLM 对话、流式对话、会话管理、消息明细持久化。
- RAG 检索：文档上传、内容抽取、分词检索、向量检索接口、知识库问答。
- Agent 编排：工具规划、工具调用、ReAct 风格执行循环、异步 Agent 任务。
- MCP 集成：主应用可管理和调用 MCP 服务；独立 MCP Server 可发布工具给外部 MCP Client。
- 工具体系：内置计算器、Web 搜索、文档搜索、数据库查询、文件系统工具，以及 API 服务转 MCP 工具。
- 企业治理：租户、组织、角色、权限、用户、工具权限、数据源和审计日志等管理域。
- 前后端一体化：`chatchat-api/web-app` 使用 Vue 3 + Vite 构建，构建产物打入 Spring Boot jar。
- 生产发布包：主应用和 MCP Server 都支持独立 zip 发行包，包含 `bin`、`config`、`data`、`logs`、`run`、`lib` 等目录。

## 技术栈

- Java 17+
- Spring Boot 3.5.x
- Maven 多模块工程
- LangChain4j 1.14.x
- Vue 3 + Vite
- H2 / MySQL / PostgreSQL
- JPA / Hibernate
- RocksDB
- Model Context Protocol SDK
- Jackson、WebFlux、WebSocket、Springdoc OpenAPI

## 总体架构

```text
Browser / Web App
        |
        v
chatchat-api  :8080
  - REST API / WebSocket / Static Web UI
  - Chat / Interaction / RAG / Agent / Enterprise / MCP Proxy
        |
        +--> LangChain4j ChatModel / EmbeddingModel
        |
        +--> H2 or MySQL / JPA
        |
        +--> RocksDB local stores
        |
        +--> chatchat-mcp-server :8090
                - MCP endpoint /mcp
                - Admin UI /admin
                - API tools / DB query tools / LiveData tools
                - Invocation audit / cache / service registry
```

主应用和 MCP Server 可以同时运行，也可以按需单独部署。主应用默认通过 `chatchat.mcp.center.base-url` 连接 MCP Server，MCP Server 默认监听 `8090`，主应用默认监听 `8080`。

## 模块说明

| 模块 | 说明 |
| --- | --- |
| `chatchat-common` | 公共配置、通用 DTO、工具元数据、响应模型和基础常量。 |
| `chatchat-api` | 主应用入口，提供 REST API、WebSocket、静态前端、OpenAPI、生产发行包。 |
| `chatchat-chat` | 会话、交互编排、技能目录、异步 Agent 任务、聊天明细存储。 |
| `chatchat-agents` | Agent 编排器、工具注册表、LangChain4j 工具执行适配。 |
| `chatchat-tools` | 内置工具实现，包括 Web 搜索、数据库查询、文档搜索等。 |
| `chatchat-knowledge-base` | 文档加载、内容抽取、分块、检索、RAG 服务和 RocksDB 搜索存储。 |
| `chatchat-integration` | 外部服务、MCP、模型供应商和第三方连接集成。 |
| `chatchat-enterprise` | 企业治理域：租户、组织、角色、权限、用户、数据源、审计等。 |
| `chatchat-mcp-server` | 独立 MCP Server，暴露工具、管理 MCP 服务、API 服务和数据库查询工具。 |
| `packaging` | 主应用生产包的外置配置、启动脚本和发布目录模板。 |
| `scripts` | 辅助打包脚本。 |

## 主应用请求链路

```text
Controller
  -> Application Service
  -> Chat / KnowledgeBase / Agent / Enterprise / Integration modules
  -> Database, RocksDB, Model Provider, MCP Server, External Services
```

主要 API 域：

- `/api/v1/chat`：聊天、流式聊天、聊天健康检查。
- `/api/v1/interactions`：统一交互入口，支持 LLM、知识库、Agent、工具直连等模式。
- `/api/v1/conversations`：会话创建、查询、删除。
- `/api/v1/search`：文档检索、知识库文件上传、文档下载。
- `/api/v1/agents/workshop`：Agent 工作台配置和发布。
- `/api/v1/agent/tasks`：异步 Agent 任务提交、事件和结果查询。
- `/api/v1/mcp`：MCP 服务管理、工具发现、工具调用、中心同步。
- `/api/v1/mcp/proxy`：MCP RPC 代理。
- `/api/v1/enterprise`：企业组织、角色、权限、数据源、审计等管理。
- `/api/v1/health`、`/api/v1/status`：健康检查。

OpenAPI 文档接口默认地址：

```text
http://localhost:8080/api-docs
```

Swagger UI 页面默认禁用，`http://localhost:8080/swagger-ui.html` 不对外开放。

## MCP Server

`chatchat-mcp-server` 是一个独立 Spring Boot 应用，默认端口 `8090`，默认 MCP endpoint 为 `/mcp`。

主要能力：

- 管理 MCP 服务注册信息。
- 将内置工具注册为 MCP tools。
- 将 HTTP API 配置转换为 MCP tool。
- 将数据库查询配置转换为 MCP tool。
- 支持 LiveData API 自动注册。
- 提供调用审计和 RocksDB 缓存。
- 提供独立管理页面。

常用入口：

```text
http://localhost:8090/admin
http://localhost:8090/mcp
http://localhost:8090/api/v1/admin/auth/login
http://localhost:8090/api/v1/mcp-services
http://localhost:8090/api/v1/api-services
http://localhost:8090/api/v1/database-query
http://localhost:8090/api/v1/audit-logs
```

## 配置说明

主应用生产配置位于：

```text
packaging/config/application.yml
packaging/config/application-mysql.yml
```

MCP Server 配置位于：

```text
chatchat-mcp-server/src/main/resources/application.yml
chatchat-mcp-server/src/main/distribution/config/application.yml
```

当前生产模板使用直写配置，便于阅读和维护。常见配置项：

- `spring.datasource.*`：应用数据库，默认 H2 文件库。
- `spring.jpa.*`：JPA 自动建表和数据库方言。
- `server.port`：服务端口，主应用默认 `8080`，MCP Server 默认 `8090`。
- `chatchat.models.*`：模型供应商、默认聊天模型、默认 embedding 模型。
- `chatchat.models.openai.*`：OpenAI-compatible API Key、Base URL、超时、重试和代理。
- `chatchat.mcp.center.*`：主应用连接独立 MCP Server 的配置。
- `chatchat.search.*`：知识库检索和文件存储目录。
- `chatchat.chat.detail-store.*`：聊天明细存储方式和 RocksDB 路径。
- `chatchat.agent.task.*`：异步 Agent 任务线程池和事件存储。
- `chatchat.tools.database-query.driver-lib-path`：外部 JDBC 驱动目录。

生产部署前至少需要修改：

```yaml
chatchat:
  models:
    openai:
      apiKey: replace-with-your-key
      baseUrl: https://dashscope.aliyuncs.com/compatible-mode/v1
```

## 本地开发

### 环境要求

- JDK 17 或更高
- Maven 3.8+
- Node.js / npm

### 构建全部模块

```bash
mvn clean package -DskipTests
```

### 仅构建主应用

```bash
mvn -pl chatchat-api -am package -DskipTests
```

`chatchat-api` 构建时会自动执行：

```bash
cd chatchat-api/web-app
npm install
npm run build
```

前端构建产物会复制到 `chatchat-api/target/classes/static`，最终打入 Spring Boot jar。

### 运行主应用

```bash
mvn -pl chatchat-api -am spring-boot:run
```

访问：

```text
http://localhost:8080
```

### 运行 MCP Server

```bash
mvn -pl chatchat-mcp-server -am spring-boot:run
```

访问：

```text
http://localhost:8090/admin
```

## 生产发行包

### 主应用发行包

推荐使用 Maven 直接生成：

```bash
mvn -pl chatchat-api -am package -DskipTests
```

生成文件：

```text
chatchat-api/target/chatchat-api-1.0.0-SNAPSHOT-release.zip
```

包结构：

```text
chatchat-1.0.0-SNAPSHOT/
  bin/
    start.bat
    start.ps1
    start.sh
    stop.*
    status.*
    restart.*
  config/
    application.yml
    application-mysql.yml
  data/
  logs/
  run/
  lib/
    app/chatchat.jar
    drivers/
```

也可以使用脚本生成 `dist` 目录下的 zip 和 tar.gz：

```powershell
.\scripts\package-deploy.ps1
```

复用已有 jar：

```powershell
.\scripts\package-deploy.ps1 -SkipBuild
```

复用已有前端构建产物：

```powershell
.\scripts\package-deploy.ps1 -SkipWebBuild
```

### MCP Server 发行包

```bash
mvn -pl chatchat-mcp-server -am package -DskipTests
```

生成文件：

```text
chatchat-mcp-server/target/chatchat-mcp-server-1.0.0-SNAPSHOT-release.zip
```

包结构：

```text
chatchat-mcp-server-1.0.0-SNAPSHOT/
  bin/
  config/application.yml
  logs/
  lib/app/chatchat-mcp-server.jar
  lib/drivers/
```

## 启停脚本

解压生产包后，在包根目录执行。

Linux:

```bash
chmod +x bin/*.sh
./bin/start.sh
./bin/status.sh
./bin/stop.sh
./bin/restart.sh
```

Windows:

```powershell
.\bin\start.bat
.\bin\status.bat
.\bin\stop.bat
.\bin\restart.bat
```

运行日志默认写入：

```text
logs/
```

PID 文件默认写入：

```text
run/
```

主应用脚本默认启动：

```text
lib/app/chatchat.jar
```

MCP Server 脚本默认启动：

```text
lib/app/chatchat-mcp-server.jar
```

## 使用 MySQL

主应用发行包内置 `application-mysql.yml`。修改数据库地址、用户名和密码后，启动时激活 `mysql` profile。

Windows:

```powershell
$env:APP_ARGS = "--spring.profiles.active=mysql"
.\bin\start.bat
```

Linux:

```bash
APP_ARGS="--spring.profiles.active=mysql" ./bin/start.sh
```

主应用 jar 已包含 H2、MySQL、PostgreSQL 驱动。数据库查询工具需要额外 JDBC 驱动时，可放入：

```text
lib/drivers/
```

## 数据目录

默认运行时数据：

```text
data/h2/                  H2 文件数据库
data/search-rocksdb/      知识库检索 RocksDB
data/search-files/        上传文档文件
data/chat-rocksdb/        聊天明细 RocksDB
data/agent-event-rocksdb/ Agent 事件 RocksDB
```

MCP Server 默认数据：

```text
data/h2/chatchat-mcp-server.mv.db
data/mcp-cache-rocksdb/
```

## 开发注意事项

- `chatchat-api` 是主应用入口，扫描 `com.chatchat` 下的业务组件。
- `chatchat-mcp-server` 是独立应用，只扫描 MCP Server、工具和工具注册相关组件，避免引入主应用的 `ChatModel` 依赖。
- 生产配置模板尽量保持直写，便于运维直接修改。
- 前端代码位于 `chatchat-api/web-app`，由 Maven 构建阶段自动打包。
- 大模型配置默认使用 OpenAI-compatible 接口，可对接 DashScope、DeepSeek 兼容接口或其他兼容服务。
- H2 适合本地和轻量部署；生产建议使用 MySQL 或 PostgreSQL，并做好 `data`、`logs` 目录备份。
- RocksDB 目录不要在服务运行时手动删除或移动。

## 常用命令速查

```bash
# 编译全部模块
mvn clean package -DskipTests

# 打包主应用生产 zip
mvn -pl chatchat-api -am package -DskipTests

# 打包 MCP Server 生产 zip
mvn -pl chatchat-mcp-server -am package -DskipTests

# 本地运行主应用
mvn -pl chatchat-api -am spring-boot:run

# 本地运行 MCP Server
mvn -pl chatchat-mcp-server -am spring-boot:run
```

## 当前默认端口

| 服务 | 端口 | 入口 |
| --- | --- | --- |
| ChatChat 主应用 | `8080` | `http://localhost:8080` |
| MCP Server | `8090` | `http://localhost:8090/admin` |
| MCP Endpoint | `8090` | `http://localhost:8090/mcp` |

## 测试脚本

已在 Maven 项目根目录加好：

- [run-chat-api-mcp.ps1](D:/IdeaProjects/LangChain4j-AIChat/Langchain4j-Chatchat/run-chat-api-mcp.ps1)
- [run-chat-api-mcp.bat](D:/IdeaProjects/LangChain4j-AIChat/Langchain4j-Chatchat/run-chat-api-mcp.bat)

默认执行：

```
.\run-chat-api-mcp.bat
```

会先停止脚本托管的旧进程，然后构建 `chatchat-api,chatchat-mcp-server`，再按顺序启动：

1. MCP Server: `http://localhost:8090/admin`
2. Chat API: `http://localhost:8080`

常用命令：

```
.\run-chat-api-mcp.bat -Action status
.\run-chat-api-mcp.bat -Action stop
.\run-chat-api-mcp.bat -SkipBuild
.\run-chat-api-mcp.bat -Clean
.\run-chat-api-mcp.bat -WithTests
run-chat-api-mcp.bat -Action restart
```

日志在 `logs/local-dev/`，PID 在 `run/local-dev/`。我已验证 `status` 和 `.bat` 包装器能正常运行；没有跑完整构建/启动，避免直接拉起服务占端口。

## git自动提交

已加好自动提交脚本：

- [git-auto-commit.ps1](D:/IdeaProjects/LangChain4j-AIChat/Langchain4j-Chatchat/git-auto-commit.ps1)
- [git-auto-commit.bat](D:/IdeaProjects/LangChain4j-AIChat/Langchain4j-Chatchat/git-auto-commit.bat)

用法：

```
.\git-auto-commit.bat -DryRun
.\git-auto-commit.bat -Message "chore: update local scripts"
.\git-auto-commit.bat -Path .gitignore run-chat-api-mcp.ps1 -Message "chore: update gitignore and scripts"
.\git-auto-commit.bat -Message "chore: update project" -Push
```

默认行为是：进入 Git 仓库根目录，`git add -A` 指定路径，生成提交；不传 `-Push` 就只本地提交。`-DryRun` 我已经验证过了，会列出将被考虑提交的文件，不会 stage 或 commit。

## 日志查看

Windows 有很多种方式，取决于你是在 **CMD、PowerShell 还是 Git Bash**。

1. PowerShell（推荐，最接近 `tail -f`）

实时查看日志：

```powershell
Get-Content .\app.log -Wait
```

相当于：

```bash
tail -f app.log
```

查看最后 100 行并持续跟踪：

```powershell
Get-Content .\app.log -Tail 100 -Wait
```

相当于：

```bash
tail -n 100 -f app.log
```

------

2. CMD（原生命令没有 tail）

CMD 没有类似 `tail` 的命令。

可以：

```cmd
type app.log
```

或者

```cmd
more app.log
```

但都不能实时刷新。

------

3. Git Bash（如果安装了 Git for Windows）

直接支持：

```bash
tail -f app.log
```

查看最后 200 行：

```bash
tail -n 200 -f app.log
```

这是 Linux 的原生命令。

------

4. WSL（Windows Subsystem for Linux）

如果安装了 WSL：

```bash
tail -f app.log
```

与 Linux 完全一致。

------

5. 实时过滤日志（PowerShell）

例如只看 ERROR：

```powershell
Get-Content .\app.log -Wait | Select-String "ERROR"
```

或者多个关键字：

```powershell
Get-Content .\app.log -Wait |
    Select-String "ERROR|WARN|Exception"
```

------

6. 如果是 Java/Spring Boot 日志（推荐）

例如：

```powershell
Get-Content .\logs\application.log -Tail 200 -Wait
```

过滤异常：

```powershell
Get-Content .\logs\application.log -Tail 200 -Wait |
    Select-String "Exception|ERROR"
```

过滤某个类：

```powershell
Get-Content .\logs\application.log -Wait |
    Select-String "AgentPlanner"
```

如果你做 Java 开发（尤其是像你现在在做 MCP、Agent Runtime），最方便的是：

- **PowerShell**：`Get-Content -Tail 200 -Wait`
- **Git Bash**：直接 `tail -f`
- **WSL**：直接 `tail -f`

其中 **PowerShell 的 `Get-Content -Tail 200 -Wait`** 就是 Windows 原生环境下最接近 Linux `tail -n 200 -f` 的方案。
