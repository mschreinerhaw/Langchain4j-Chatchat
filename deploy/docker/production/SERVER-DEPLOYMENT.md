# 192.168.195.221 源码部署

本次发布目录为 `/opt/runtime-os/releases/source-20261118-d736e9fdf`。五个应用在服务器从源码构建，构建代理为 `http://192.168.195.249:18118`。源码快照及逐文件 SHA-256 位于发布目录的 `source/`、`source-manifest.json`；构建日志、镜像 ID 位于 `logs/`、`build-summary.json`。

应用入口：平台 `http://192.168.195.221:18100`，MCP 管理端 `http://192.168.195.221:18101`。平台使用提供的管理员账号；MCP 沿用原来独立的管理员凭据。凭据不写入本文档。

```sh
RELEASE=/opt/runtime-os/releases/source-20261118-d736e9fdf
docker compose --env-file "$RELEASE/private/release.env" -f "$RELEASE/compose.production.json" ps
docker compose --env-file "$RELEASE/private/release.env" -f "$RELEASE/compose.production.json" up -d --pull never
```

生产发布复用 `/opt/runtime-os-docker-stage/config`、原有数据库、应用数据目录和专用执行器守护进程。PostgreSQL、Silo、OpenSearch 使用原来运行的容器；不要执行带 `--remove-orphans` 的命令。备份位于发布目录的 `private/`，其中包含数据库转储和部署前配置。该目录仅供 root 访问。

编码服务复用当前 `/opt/runtime-os/cn2/compose.json` 的治理节点、普通执行器及 Studio 执行器配置。任务目录位于 `/opt/runtime-os/p18-d736e9f`，避免 Unix socket 路径长度超限。新执行镜像保留原科学 Python 环境，使用服务器新编译的官方 Cline runtime、Node 和本仓库服务源码。治理节点不挂载 Docker socket；执行器使用已有专用守护进程。已退役的 legacy coding 服务未重新启动。

若需更换镜像，先通过 `scripts/prepare-production-stage.py` 生成配置，再使用 `scripts/activate-production-stage.py` 检查源码哈希、构建证据、备份、回归测试、镜像固定和 socket 隔离。不要在有任务运行时重新创建编码节点。不要自动重放 UNKNOWN 或待恢复任务。

验收证据位于本地工作区外层 `target/production-server-20261118/` 及服务器发布目录的 `evidence/`。真实模型测试只使用明确标注的合成 CSV。启动检查、浏览器检查、模型报告任务、取消任务分别保存结果；首次失败记录保留，复测使用独立目录。源码的 `enterpriseAcceptance:false` / 控制端 `productionReady:false` 标记仍保留，部署验证不代表源码未实现的企业能力已通过验收。
