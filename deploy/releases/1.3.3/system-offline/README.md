# Runtime OS 系统离线升级

本包沿用 `runtime-os-1.3.2` 的 `offline-images/` 和 `update-config/` 结构。支持 Linux amd64、Python 3.7.9、Docker 和标准 PostgreSQL 安装。升级输入为已安装的 `compose.json`、使用两个节点和独立 Docker 守护进程的 Runtime OS，以及独立部署的模型 Gateway。已存在的 LiteLLM 服务同步升级。只启用 DIRECT 的现场可继续使用原模型路由。

## 内容

- API 和 Java Worker、MCP、Coding Node / Recovery / Executor / 临时任务镜像、前端报告渲染器、LLM Compatibility Gateway、LiteLLM 1.110.3。
- PostgreSQL 增量 SQL、Coding 监控静态资源、Linux amd64 Compose v2 插件。
- 已有网关配置、权限、模型别名、密钥、数据卷和后台服务继续使用现场配置。模型框架模板在 `update-config/litellm/`。

## 执行

先停止外部提交和定时调度，确认 Coding 监控无在途任务。预留解压、本机镜像导入、私有 Docker daemon 镜像导入和数据库备份的空间。镜像归档、标签和 ID 见 `offline-images/BUILD.json`。

```sh
sha256sum -c runtime-os-1.3.3-system-offline-20261127-302855ffb.tar.sha256
tar -xf runtime-os-1.3.3-system-offline-20261127-302855ffb.tar
cd runtime-os-1.3.3-system-offline-20261127-302855ffb
sha256sum -c SHA256SUMS
sudo bash update-config/update.sh check --root /opt/ros21 --gateway-root /opt/llm-compatibility-gateway
sudo bash update-config/update.sh apply --root /opt/ros21 --gateway-root /opt/llm-compatibility-gateway
```

外置 PostgreSQL 容器使用 `--postgres-container 实际容器名`，预检需找到 `live_runtime_api` 和 `live_runtime_mcp`。若同目录可识别多个 Gateway 项目，增加 `--gateway-project 实际项目名`。预检会在拓扑、私有 daemon 身份或运行镜像与 Compose 不一致时停止。升级脚本验证镜像身份后才切换，数据库变更是可重复执行的新增字段。

模型 Gateway 使用 `compose.runtime-os-offline.json` 作为最后一个 Compose 覆盖文件，日常手工 `docker compose up` 也要按原顺序传入已有 `-f` 文件并把此文件放在最后。容器自身重启不需要重跑 Compose。新部署 LiteLLM 时，使用包内模板和现场批准的模型配置及私有认证文件，并使用镜像清单中的 liteLLM 标签。若现有 Gateway 未启用 FRAMEWORK，启用时需要在管理页按上游别名配置一条测试模型，核验后再调整生产默认模型。

## 验收

1. 登录生产入口和 `/monitor/`，检查 API、MCP、报告渲染器及 Coding 节点状态。
2. 用可访问的 Agent 下载 Markdown 对接说明。使用 Agent 专用令牌提交一份合成 CSV，检查 HTML 及含独立图片的 ZIP。再传入 `reportMode=off`，确认只交付文本。网页报告导出继续使用角色授权和带水印的 PDF。
3. 用明确授权的测试角色验证授权到期、PDF 导出记录与 Token 额度。完成一次内网模型 Native 工具调用，核对结果和网关日志，已启用 FRAMEWORK 的现场同时验证 FRAMEWORK 路由。

## 回滚

```sh
sudo bash update-config/update.sh rollback --root /opt/ros21 --gateway-root /opt/llm-compatibility-gateway --backup /opt/ros21/.runtime-updates/<实际目录>
```

使用 apply 输出的备份目录。回滚恢复原 Compose、镜像、Coding 配置及静态资源。保留数据库中的兼容新增列与业务数据。两个 PostgreSQL 转储存放在同一个受限回滚目录，供 DBA 在需要时恢复。恢复镜像前保留现场原镜像及标签，暂缓清理。
