# Redis

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start redis
./manage.sh status redis
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

缓存服务。

镜像：`redis:7.4-alpine`。 [上游文档](https://hub.docker.com/_/redis)。

## 手工创建或 Compose 参考

以下为本机部署参数，使用已有数据和配置。首次创建前先准备挂载文件；已有同名容器时见下方日常维护。

```bash
docker run -d --name redis --restart unless-stopped \
  -p 6379:6379 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.env \
  --mount type=volume,src=387a5d69f2b1a0f25fc2587933aad767e395ad034031df9bd0e53e4add49fc5a,dst=/data \
  --mount type=bind,src=/opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.conf,dst=/usr/local/etc/redis/redis.conf,readonly \
  redis:7.4-alpine redis-server /usr/local/etc/redis/redis.conf
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| 387a5d69f2b1a0f25fc2587933aad767e395ad034031df9bd0e53e4add49fc5a | /data | 读写 |
| /opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.conf | /usr/local/etc/redis/redis.conf | 只读；含现有认证配置 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/redis.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker stop -t 60 redis
docker start redis
docker logs --tail 100 redis
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull redis:7.4-alpine
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
