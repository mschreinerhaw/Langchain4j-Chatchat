# OpenSearch

<!-- operator-entry -->
## 在当前服务器启动

登录服务器后，直接执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
./manage.sh start opensearch
./manage.sh status opensearch
```

工具会先启动依赖，再启动此镜像对应的容器。[整套启动与发布步骤](../../README.md)。下文是挂载和手工操作参考。
<!-- /operator-entry -->

全文检索和对话正文索引。

镜像：`opensearchproject/opensearch:2.19.1`。 [上游文档](https://hub.docker.com/r/opensearchproject/opensearch)。

## 手工创建或 Compose 参考

以下为本机部署参数，使用已有数据和配置。首次创建前先准备挂载文件；已有同名容器时见下方日常维护。

```bash
docker run -d --name opensearch-node --restart unless-stopped \
  -p 9200:9200 -p 9600:9600 \
  --env-file /opt/runtime-os/standard-maintenance-20261115/private/image-env/opensearch-node.env \
  --mount type=bind,src=/data/opensearch/data,dst=/usr/share/opensearch/data \
  --mount type=bind,src=/data/opensearch/logs,dst=/usr/share/opensearch/logs \
  --mount type=bind,src=/data/opensearch/plugins,dst=/usr/share/opensearch/plugins \
  --mount type=bind,src=/data/opensearch/config,dst=/usr/share/opensearch/config \
  opensearchproject/opensearch:2.19.1
```

## 外置目录

| 宿主机目录或现有卷 | 容器目录 | 用途/权限 |
| --- | --- | --- |
| /data/opensearch/plugins | /usr/share/opensearch/plugins | 读写 |
| /data/opensearch/data | /usr/share/opensearch/data | 读写 |
| /data/opensearch/logs | /usr/share/opensearch/logs | 读写 |
| /data/opensearch/config | /usr/share/opensearch/config | 读写；重建使用 |

## 配置

环境变量文件：`/opt/runtime-os/standard-maintenance-20261115/private/image-env/opensearch-node.env`，权限 `0600`，仅保存在服务器，文档包不含密码。

| 变量 | 当前设置 |
| --- | --- |
| discovery.type | single-node |
| OPENSEARCH_JAVA_OPTS | -Xms2g -Xmx2g |

`OPENSEARCH_INITIAL_ADMIN_PASSWORD` 保存在环境文件中。当前运行容器的 config 原先在容器内，已导出到 `/data/opensearch/config`；上面的完整创建示例将其外置，保留现用配置和 TLS 证书。

## 日常维护

```bash
docker stop -t 60 opensearch-node
docker start opensearch-node
docker logs --tail 100 opensearch-node
```

## 构建镜像

直接使用上游发布的镜像：

```bash
docker pull opensearchproject/opensearch:2.19.1
```

生产版本的实际 digest 见根目录 `images.lock.json`。拉取标签不会自动更新现有容器。

更新服务前备份对应数据；挂载目录保持不变。重建容器须先停止原实例，避免两个实例同时写同一数据目录。
