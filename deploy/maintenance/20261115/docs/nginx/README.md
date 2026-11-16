# Nginx

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start nginx
./manage.sh status nginx
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

业务入口 18100 和 MCP 入口 18101。

镜像：`nginx:1.28-alpine`。 [上游文档](https://hub.docker.com/_/nginx)。

## 手工创建或 Compose 参考

以下为本机部署参数，使用已有数据和配置。首次创建前先准备挂载文件；已有同名容器时见下方日常维护。

```bash
docker run -d --name runtime-os-app-stage-gateway-1 --restart unless-stopped \
  --network runtime-os-app-stage_control \
  -p 18100:8080 -p 18101:8090 \
  --user 101:101 --read-only --cap-drop ALL \
  --security-opt no-new-privileges:true \
  --tmpfs /tmp:rw,noexec,nosuid,size=64m \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-os-app-stage-gateway-1.env \
  --mount type=bind,src=/opt/runtime-os-docker-stage/config/public-gateway.conf,dst=/etc/nginx/nginx.conf,readonly \
  --mount type=bind,src=/opt/runtime-os/releases/latest-main-20261115/public-ui,dst=/srv/task-lifecycle-ui,readonly \
  --mount type=bind,src=/opt/runtime-os/releases/latest-main-20261115/source/chatchat-api/src/main/resources/coding-monitor,dst=/srv/coding-monitor,readonly \
  nginx:1.28-alpine
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os-docker-stage/config/public-gateway.conf | /etc/nginx/nginx.conf | 只读 |
| /opt/runtime-os/releases/latest-main-20261115/public-ui | /srv/task-lifecycle-ui | 只读 |
| /opt/runtime-os/releases/latest-main-20261115/source/chatchat-api/src/main/resources/coding-monitor | /srv/coding-monitor | 只读 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-os-app-stage-gateway-1.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

`public-gateway.conf` 定义 8080/8090 两个监听及 API/MCP 上游；宿主机对应 18100/18101。配置内的临时文件写入 `/tmp`。

该服务使用现有 `runtime-os-app-stage_control` 网络，需先完成整套部署的网络初始化。

## 日常维护

```bash
docker stop -t 60 runtime-os-app-stage-gateway-1
docker start runtime-os-app-stage-gateway-1
docker logs --tail 100 runtime-os-app-stage-gateway-1
```

修改网关配置后：

```bash
docker exec runtime-os-app-stage-gateway-1 nginx -t
docker exec runtime-os-app-stage-gateway-1 nginx -s reload
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull nginx:1.28-alpine
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
