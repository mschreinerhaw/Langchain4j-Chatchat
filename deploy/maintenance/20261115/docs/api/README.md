# API

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start api
./manage.sh status api
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

业务 API、控制服务和任务 Worker。

镜像：`runtime-api:latest-main-20261115`。由本项目源码构建。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json up -d --no-deps temporal-control-api worker1 worker2
```
整套系统首次启动使用根目录 `start-all.sh`；这些服务共享网络命名空间，需要按 API → Temporal → 执行节点/Worker 的顺序启动。

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os-docker-stage/config/api/internal-credential.key | /srv/app/config/internal-credential.key | 只读 |
| /opt/runtime-os-docker-stage/api/logs | /srv/app/logs | 读写 |
| /opt/runtime-os-docker-stage/config/secrets/coding-agent-shared-secret | /run/secrets/coding_agent_shared_secret | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/api.key | /run/temporal-tls/client.key | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/ca.crt | /run/temporal-tls/ca.crt | 只读 |
| /opt/runtime-os-docker-stage/mcp/data/python-user-data | /srv/shared-coding-input | 读写 |
| /opt/runtime-os-docker-stage/config/secrets/asset-access-key | /run/secrets/asset-access-key | 只读 |
| /opt/runtime-os-docker-stage/config/api/application.yml | /srv/app/config/application.yml | 只读 |
| /opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers | /srv/app/lib/drivers | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/connection.json | /run/secrets/native-coding-connection.json | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/api.crt | /run/temporal-tls/client.crt | 只读 |
| /opt/runtime-os-docker-stage/config/secrets/asset-secret-key | /run/secrets/asset-secret-key | 只读 |
| /opt/chatchat-1.0.0-SNAPSHOT/data | /srv/app/data | 读写 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-native-temporal-api.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json stop temporal-control-api worker1 worker2
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json start temporal-control-api worker1 worker2
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json logs --tail 100 temporal-control-api worker1 worker2
```

## 构建镜像


```bash
cd /opt/runtime-os/releases/latest-main-20261115/source
docker build -t runtime-api:latest-main-20261115 \
  -f deploy/docker/source-stack/Dockerfile.java-skills --target api \
  --build-arg NODE_BUILD_IMAGE=runtime-source-base:node-build-image-280af32071729473 \
  --build-arg JAVA_BUILD_IMAGE=runtime-source-base:java-build-image-fee8cb4328cdfa32 \
  --build-arg API_RUNTIME_IMAGE=runtime-api:studio-lease-20261114 \
  .
```

构建上下文需包含源码及 `tools/maven`。Node/JDK 基础镜像和 API 运行基础已保留在构建机；镜像 ID 见 `images.lock.json` 及归档清单。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
