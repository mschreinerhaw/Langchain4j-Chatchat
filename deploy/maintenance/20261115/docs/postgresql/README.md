# PostgreSQL

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start postgresql
./manage.sh status postgresql
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

业务元数据及 Temporal 工作流数据。

镜像：`postgres:18.6`。 [上游文档](https://hub.docker.com/_/postgres)。

## 手工创建或 Compose 参考

以下为本机部署参数，使用已有数据和配置。首次创建前先准备挂载文件；已有同名容器时见下方日常维护。

```bash
docker run -d --name postgresql18 --restart unless-stopped \
  -p 5432:5432 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/postgresql18.env \
  --mount type=volume,src=998a12dcec518fdb8a7201c81cbd3d507cae16d6e817851638a9d2cbadd3adf5,dst=/var/lib/postgresql \
  --mount type=bind,src=/data/postgresql/18/docker,dst=/var/lib/postgresql/18/docker \
  postgres:18.6
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /data/postgresql/18/docker | /var/lib/postgresql/18/docker | 读写 |
| 998a12dcec518fdb8a7201c81cbd3d507cae16d6e817851638a9d2cbadd3adf5 | /var/lib/postgresql | 读写 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/postgresql18.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

`POSTGRES_USER`、`POSTGRES_PASSWORD` 和 `PGDATA` 保存在环境文件中。`PGDATA=/var/lib/postgresql/18/docker`；实际数据库文件位于宿主机 `/data/postgresql/18/docker`。外层既有卷也保留。

## 日常维护

```bash
docker stop -t 60 postgresql18
docker start postgresql18
docker logs --tail 100 postgresql18
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull postgres:18.6
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
