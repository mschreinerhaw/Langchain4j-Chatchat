# Runtime OS 1.3.3

本版将原生 Cline 从 **4.1.26 升级为 4.1.27**，固定上游提交 `8075d2e35cf5cad67c24f180c80a05d311b74a3e`。[源码锁定清单](../../../cline-agent-server/upstream-pin.json)

- 数据分析任务使用 Java 签发的任务策略与 Cline 原生工具 Hook。Hook 在工具结果进入模型请求前生成带证据引用的回执，Python 仍可读取完整数据并执行分析。普通编码任务沿用原生工具行为。
- 发行版提供 [单容器启停与镜像升级手册](../../SINGLE-CONTAINER-OPERATIONS.zh-CN.md)，含 Coding 监控恢复、单服务重建和回滚。
- API 源码镜像构建保留 Python 日志分析示例所需的样例日志文件。

## 发行配置和启动

GitHub Release 提供源码、Compose 配置包及校验文件。应用镜像沿用本地构建和交付流程。镜像应由 `v1.3.3` 对应源码构建并核对来源和镜像 ID。版本标签由 [images.compose.yml](images.compose.yml) 定义，包含 API、MCP、News、Cline 与资源库镜像。单独交付的安装镜像使用 [安装与构建指南](../../standalone/README.md)。

在与构建环境匹配的 Linux 主机上，使用 [生产源码构建流程](../../docker/production/README.md)。使用仓库现有的 TLS 代理构建脚本时，可在源码根目录执行以下命令。

```sh
python3 scripts/build-production-images.py --tag 1.3.3 --proxy https://your-build-proxy:8443
```

全新部署时，将 [release.env.example](release.env.example) 和其他私有配置安装到 `/opt/runtime-os/releases/1.3.3/private`。填写 `HUB_TOKEN`，并按 [生产 Compose 配置清单](../../docker/production/README.md#私有配置) 准备应用配置、Cline 模型配置、密钥和证书。准备 Compose 所需镜像后，从源码或已校验的 Compose 包根目录操作。

```sh
release_compose() {
  docker compose -p runtime-os-release \
    --env-file /opt/runtime-os/releases/1.3.3/private/release.env \
    -f deploy/releases/1.3.3/docker-compose.yml "$@"
}
release_compose config --quiet
release_compose config --images
release_compose up -d --no-build --pull never --wait --wait-timeout 360
release_compose ps -a
curl -fsS http://127.0.0.1:18100/version.json
```

Coding 监控默认监听 `http://127.0.0.1:18100/monitor/`，MCP 管理入口默认监听 18101。停止服务用 `release_compose stop -t 90`，它会保留数据卷。已有实例升级沿用原 Compose 项目、数据和私有配置，按运维手册操作。切换前保存旧镜像 ID、原配置和数据备份。

## 验收范围

原生 Cline 的隔离测试捕获了实际模型 HTTP 请求，验证了受保护工具结果回执及普通编码任务读取行为。现有验收发现，复制到非保护目录的数据仍可能进入模型请求。LiveData 的真实模型全流程和与 851,021 输入 Token 基线的可比复测仍待完成。详情见 [第三阶段验收报告](../../../docs/cline-data-analysis-phase3-acceptance.md)。该问题和真实业务复测需在生产准入时独立评估。
