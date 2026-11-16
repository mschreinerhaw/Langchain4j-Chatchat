# Studio

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start studio
./manage.sh status studio
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

SQL/Python Studio 兼容服务。

镜像：`native-studio:latest-main-20261115`。由本项目源码构建。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json up -d --no-deps studio-gateway
```
整套系统首次启动使用根目录 `start-all.sh`；这些服务共享网络命名空间，需要按 API → Temporal → 执行节点/Worker 的顺序启动。

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /opt/runtime-os/cn2 | /opt/runtime-os/cn2 | 读写 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/runtime-coding-studio-gateway.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json stop studio-gateway
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json start studio-gateway
docker compose -p runtime-native-temporal-candidate -f /opt/runtime-os/native-temporal/rc-20261112/compose.private.json logs --tail 100 studio-gateway
```

## 构建镜像


```bash
cd /opt/runtime-os/releases/latest-main-20261115/source
docker build -f Dockerfile.studio-update -t native-studio:latest-main-20261115 .
```

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
