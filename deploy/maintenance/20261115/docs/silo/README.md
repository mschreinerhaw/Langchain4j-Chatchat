# Silo

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start silo
./manage.sh status silo
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

S3 对象存储，保存文件和任务产物。

镜像：`pgsty/silo:latest`。 [上游文档](https://hub.docker.com/r/pgsty/silo)。

## 手工创建或 Compose 参考

以下为本机部署参数，使用已有数据和配置。首次创建前先准备挂载文件；已有同名容器时见下方日常维护。

```bash
docker run -d --name silo --restart unless-stopped \
  --network runtime-os-app-stage_control \
  -p 127.0.0.1:9000:9000 -p 127.0.0.1:9001:9001 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/silo.env \
  --memory 1g --cpus 2 --security-opt no-new-privileges:true \
  --mount type=bind,src=/opt/runtime-os-docker-stage/silo/data,dst=/data \
  --mount type=bind,src=/opt/runtime-os-docker-stage/silo/secrets,dst=/run/secrets,readonly \
  --mount type=bind,src=/opt/runtime-os-docker-stage/config/silo/app-policy.json,dst=/bootstrap/app-policy.json,readonly \
  pgsty/silo:latest server /data --console-address :9001
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os-docker-stage/config/silo/app-policy.json | /bootstrap/app-policy.json | 只读 |
| /opt/runtime-os-docker-stage/silo/data | /data | 读写 |
| /opt/runtime-os-docker-stage/silo/secrets | /run/secrets | 只读 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/silo.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

| 变量 | 容器内文件路径 |
| --- | --- |
| MINIO_ROOT_USER_FILE | /run/secrets/root-user |
| MINIO_ROOT_PASSWORD_FILE | /run/secrets/root-password |
| MINIO_KMS_SECRET_KEY_FILE | kms_master_key |
| MINIO_CONFIG_ENV_FILE | config.env |

`/data` 存对象数据；`/run/secrets` 存根账号及加密密钥；策略文件单独只读挂载。

该服务使用现有 `runtime-os-app-stage_control` 网络，需先完成整套部署的网络初始化。

## 日常维护

```bash
docker stop -t 60 silo
docker start silo
docker logs --tail 100 silo
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull pgsty/silo:latest
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
