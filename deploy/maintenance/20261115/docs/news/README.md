# News

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start news
./manage.sh status news
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

资讯采集和资讯能力服务。

镜像：`runtime-os-news:latest-main-20261115`。由本项目源码构建。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml up -d --no-deps news
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os-docker-stage/news/logs | /srv/app/logs | 读写 |
| /opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data | /srv/app/data | 读写 |
| /opt/runtime-os-docker-stage/config/news | /srv/app/config | 只读 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-os-app-stage-news-1.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml stop news
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml start news
docker compose --env-file /opt/runtime-os-docker-stage/config/deployment.env -f /opt/runtime-os-docker-stage/compose.yaml logs --tail 100 news
```

## 构建镜像


```bash
cd /opt/runtime-os/releases/latest-main-20261115/source
docker build -t runtime-os-news:latest-main-20261115 \
  -f deploy/docker/source-stack/Dockerfile.extra --target news \
  --build-arg NODE_BUILD_IMAGE=runtime-source-base:node-build-image-280af32071729473 \
  --build-arg JAVA_BUILD_IMAGE=runtime-source-base:java-build-image-fee8cb4328cdfa32 \
  --build-arg JAVA_RUNTIME_IMAGE=runtime-source-base:java-build-image-fee8cb4328cdfa32 \
  .
```

构建上下文需包含源码及 `tools/maven`。Node/JDK 基础镜像和 API 运行基础已保留在构建机；镜像 ID 见 `images.lock.json` 及归档清单。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
