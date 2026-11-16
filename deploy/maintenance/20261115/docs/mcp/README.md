# MCP

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start mcp
./manage.sh status mcp
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

工具服务及授权能力目录。

镜像：`runtime-os-mcp:latest-main-20261115`。由本项目源码构建。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml up -d --no-deps mcp
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os-docker-stage/mcp/data/python-assets | /opt/runtime-os-docker-stage/mcp/data/python-assets | 读写 |
| /sys/class/net/ens192/address | /run/license/host-mac | 只读 |
| /opt/runtime-os-docker-stage/mcp/logs | /srv/app/logs | 读写 |
| /opt/chatchat-mcp-server-1.0.0-SNAPSHOT/lib/drivers | /srv/app/lib/drivers | 只读 |
| /opt/runtime-os-docker-stage/config/native-search-tls | /srv/app/config/native-search-tls | 只读 |
| /var/run/docker.sock | /var/run/docker.sock | 读写 |
| /opt/runtime-os-docker-stage/mcp/data/python-user-data | /opt/runtime-os-docker-stage/mcp/data/python-user-data | 读写 |
| /opt/runtime-os-docker-stage/config/mcp/internal-credential.key | /srv/app/config/internal-credential.key | 只读 |
| /opt/chatchat-mcp-server-1.0.0-SNAPSHOT/data | /srv/app/data | 读写 |
| /opt/runtime-os-docker-stage/config/mcp/application.yml | /srv/app/config/application.yml | 只读 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-os-app-stage-mcp-1.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml stop mcp
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml start mcp
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml logs --tail 100 mcp
```

## 构建镜像


```bash
cd /opt/runtime-os/releases/latest-main-20261115/source
docker build -t runtime-os-mcp:latest-main-20261115 \
  -f deploy/docker/source-stack/Dockerfile.extra --target mcp \
  --build-arg NODE_BUILD_IMAGE=runtime-source-base:node-build-image-280af32071729473 \
  --build-arg JAVA_BUILD_IMAGE=runtime-source-base:java-build-image-fee8cb4328cdfa32 \
  --build-arg JAVA_RUNTIME_IMAGE=runtime-source-base:java-build-image-fee8cb4328cdfa32 \
  .
```

构建上下文需包含源码及 `tools/maven`。Node/JDK 基础镜像和 API 运行基础已保留在构建机；镜像 ID 见 `images.lock.json` 及归档清单。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
