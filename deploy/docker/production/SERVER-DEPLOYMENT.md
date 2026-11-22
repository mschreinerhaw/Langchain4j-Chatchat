# 192.168.195.221 源码部署

## Native 执行器启动

生产 Compose 加载 `compose.native-daemon.yaml`。`native-daemon` 容器同时管理私有 Docker 和 containerd，发布进程身份至现有数据目录。执行器镜像应包含进程身份读取逻辑，服务配置设置 `CLINE_EXECUTOR_PUBLISHED_DAEMON_BINDINGS=1`。运行时容器使用特权模式执行嵌套容器所需的挂载和 cgroup 操作，禁用容器网络，仅通过私有 Unix socket 提供接口。每个任务容器另行执行网络隔离、只读根文件系统和 CPU、内存、PID 限额。

在服务器上执行 `TMPDIR=/mnt/disk01 bash deploy/docker/production/native-daemon/build.sh`。脚本用已安装的标准 Docker 发行包中的二进制构建镜像，并打印源文件校验和与镜像 ID。部署后的容器使用镜像内的二进制。生产默认镜像标签为 `runtime-os-native-daemon:26.1.3-el8`。

迁移前备份启动配置和原 systemd 单元，并确认运行中任务已结束。停止旧专用 Docker 和 containerd，再用新 Compose 启动 `native-daemon`。启动成功后核对原 daemon ID、Worker 镜像、已有任务记录和执行器健康，再停用并移出专用 systemd 单元。两个运行时必须依次交接同一数据目录。

本次发布目录为 `/opt/runtime-os/releases/source-20261118-d736e9fdf`。五个应用在服务器从源码构建，构建代理为 `http://192.168.195.249:18118`。源码快照及逐文件 SHA-256 位于发布目录的 `source/`、`source-manifest.json`；构建日志、镜像 ID 位于 `logs/`、`build-summary.json`。

应用入口：平台 `http://192.168.195.221:18100`，MCP 管理端 `http://192.168.195.221:18101`。平台使用提供的管理员账号；MCP 沿用原来独立的管理员凭据。凭据不写入本文档。

```sh
export RUNTIME_OS_RELEASE_DIR=/opt/runtime-os/production-current
docker compose -f "$RUNTIME_OS_RELEASE_DIR/source/deploy/docker/production/compose.release.yaml" ps
```

生产发布复用 `/opt/runtime-os-docker-stage/config`、原有数据库、应用数据目录和专用执行器守护进程。PostgreSQL、Silo、OpenSearch 使用原来运行的容器；不要执行带 `--remove-orphans` 的命令。备份位于发布目录的 `private/`，其中包含数据库转储和部署前配置。该目录仅供 root 访问。

编码服务复用当前 `/opt/runtime-os/cn2/compose.json` 的治理节点、普通执行器及 Studio 执行器配置。任务目录位于 `/opt/runtime-os/p18-d736e9f`，避免 Unix socket 路径长度超限。新执行镜像保留原科学 Python 环境，使用服务器新编译的官方 Cline runtime、Node 和本仓库服务源码。治理节点不挂载 Docker socket；执行器使用已有专用守护进程。已退役的 legacy coding 服务未重新启动。

若需更换镜像，先通过 `scripts/prepare-production-stage.py` 生成配置，再使用 `scripts/activate-production-stage.py` 检查源码哈希、构建证据、备份、回归测试、镜像固定和 socket 隔离。不要在有任务运行时重新创建编码节点。不要自动重放 UNKNOWN 或待恢复任务。

OpenSearch 就绪探针的专项变更和回滚见[部署方案](../../../docs/runtime-os-readiness-p0/03-rollout.md)。

验收证据位于本地工作区外层 `target/production-server-20261118/` 及服务器发布目录的 `evidence/`。真实模型测试只使用明确标注的合成 CSV。启动检查、浏览器检查、模型报告任务、取消任务分别保存结果；首次失败记录保留，复测使用独立目录。源码的 `enterpriseAcceptance:false` / 控制端 `productionReady:false` 标记仍保留，部署验证不代表源码未实现的企业能力已通过验收。
