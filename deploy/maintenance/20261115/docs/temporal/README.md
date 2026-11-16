# Temporal

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start temporal
./manage.sh status temporal
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

Native 任务编排和持久化工作流。

镜像：`temporalio/server:1.29.7`。 [上游文档](https://hub.docker.com/r/temporalio/server)。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json up -d --no-deps temporal
```
整套系统首次启动使用根目录 `start-all.sh`；这些服务共享网络命名空间，需要按 API → Temporal → 执行节点/Worker 的顺序启动。

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/ca.crt | /run/temporal-tls/ca.crt | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/server.crt | /run/temporal-tls/client.crt | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/tls/server.key | /run/temporal-tls/client.key | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/temporal-entrypoint.sh | /opt/runtime/temporal-entrypoint.sh | 只读 |
| /opt/runtime-os/native-temporal/rc-20261112/private/database-password | /run/secrets/database-password | 只读 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-native-temporal-server.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

沿用已有 PostgreSQL 工作流数据库、visibility 数据库和 TLS 证书。入口脚本只启动服务，不执行 autosetup，不自动建库或升级模式。

## 日常维护

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json stop temporal
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json start temporal
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json logs --tail 100 temporal
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull temporalio/server:1.29.7
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
