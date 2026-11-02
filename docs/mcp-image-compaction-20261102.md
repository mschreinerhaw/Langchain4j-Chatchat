# MCP 镜像精简与部署（2026-11-02）

## 原因与结果

服务器 `192.168.195.221` 上的
`runtime-os-mcp:unified-query-management-20261101-r2` 为 1,144,442,140 字节。
镜像历史包含三次约 255 MB 的 JAR 复制；覆盖文件不会删除旧层。
Docker CLI 在复制后单独执行 chmod，又产生约 32 MB 的文件层。

最终镜像为
`runtime-os-mcp:unified-query-management-20261101-r2-compact-20261102`，
ID `5ef9d61370e656c5ae0f750b0bbbc2dc39a9ffd2ff8844d711c6a6aebf7f53cf`。
体积 601,600,020 字节，减少 542,842,120 字节（约 47.4%）。

运行时底座使用本机已有的 `runtime-os-api-runtime:17-sqlglot-20261024`。
重建前已核对其全部 RootFS 层与旧 MCP 镜像的底层前缀完全一致。
最终镜像只复制一次现用 JAR 和一次 CLI，使用 COPY --chmod 设置权限。
没有重新编译应用或变更数据库。

## 验证与服务状态

- 新旧 JAR SHA-256 一致：`64b8f27fcdc313065ea87b8a76f6f710b06da1a59117234756be6bcaa3602f87`。
- 新旧 Docker CLI SHA-256 一致；Java 17、Docker CLI 和 sqlglot 30.23.0 检查通过。
- MCP 直连 18190 和网关 18101 的管理登录、管理页面检查通过。
- 网关已认证用户接口通过；查询目录返回 8 项，Studio 发布目录返回 7 项。
- MCP 内访问宿主 Docker daemon 通过。
- `test_monitor_gateway.py` 在服务器真实 Docker 中运行：1 项通过，覆盖监控认证、Cookie、Origin 和执行接口隔离。
- MCP 已运行；API、CODING 维持停止，其余原有容器 ID 与运行状态未变。

## 部署与后续构建

MCP 容器重建时沿用原配置、挂载、端口、网络别名和重启策略。
该网络不接受静态指定容器 IP；同时，停止的 API 会导致原静态 upstream
在 Nginx reload 时解析失败。因此网关 API/MCP upstream 改用变量，
通过现有 Docker DNS resolver 在请求时解析，并已验证配置与实际访问。

现网 compose.yaml 的 MCP image 已更新；旧大镜像、替换前的停止容器和
未使用构建缓存已清理。服务器部署报告、私有配置备份、构建记录和独立重建
上下文位于 `/opt/runtime-os-docker-stage/maintenance/mcp-compact-20261102/`。
独立的 `rebuild-context/` 含 Dockerfile、JAR 和 CLI，不依赖已删除的旧应用镜像。

仓库的 Dockerfile.mcp 改用 COPY --chmod；build-images.ps1 将 MCP 上限收紧为
700 MiB，并保留“只能有一次 JAR COPY”的检查。发布时最终 FROM 必须使用
干净运行时底座，不能以已包含应用 JAR 的历史应用镜像反复覆盖构建。

本次未调用模型或执行完整 Python 分析业务任务；API、CODING 停止期间，
跨服务业务链路仍不可用。
