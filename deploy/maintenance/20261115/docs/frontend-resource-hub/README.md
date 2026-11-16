# 浏览器资源服务

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start frontend-resource-hub
./manage.sh status frontend-resource-hub
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

浏览器资源和预览 Worker。

镜像：`runtime-frontend-resource-hub:1.0.0`。由本项目源码构建。

## 手工创建或 Compose 参考

当前安装通过 Compose 管理，保留项目网络、密钥和运行参数。

```bash
docker compose -p frontend-resource-hub -f /opt/frontend-resource-hub/1.0.0/compose.yaml up -d --no-deps frontend-resource-hub frontend-preview-worker
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/frontend-resource-hub-frontend-resource-hub-1.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

## 日常维护

```bash
docker compose -p frontend-resource-hub -f /opt/frontend-resource-hub/1.0.0/compose.yaml stop frontend-resource-hub frontend-preview-worker
docker compose -p frontend-resource-hub -f /opt/frontend-resource-hub/1.0.0/compose.yaml start frontend-resource-hub frontend-preview-worker
docker compose -p frontend-resource-hub -f /opt/frontend-resource-hub/1.0.0/compose.yaml logs --tail 100 frontend-resource-hub frontend-preview-worker
```

## 构建镜像


```bash
cd /opt/frontend-resource-hub/1.0.0
docker build -t runtime-frontend-resource-hub:1.0.0 .
```

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
