# 加载镜像，启动系统

`20261121-r3` 交付介质请使用[可移植安装与授权指南](PORTABLE-INSTALL.md)。下文适用于 `20261115-docker-only` 镜像。

部署机准备 Linux x86_64 Docker Engine 26 或更新版本，以及交付的镜像文件。Java、Python、Node、Compose、建表程序、证书工具和运行依赖均在镜像内。Docker 至少分配 12 GiB 内存，首次加载前至少预留 40 GiB 磁盘空间，业务数据另行预留。

## 安装

在部署机执行：

```bash
docker load -i runtime-os-20261115-docker-only.tar.gz

docker run --rm --pull=never \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /data/runtime-os:/install \
  runtime-os-installer:20261115-docker-only install
```

第一次使用空的 `/data/runtime-os`。Docker 会创建目录；安装镜像会生成配置、独立密码、证书，初始化数据库、检索和文件存储，按顺序启动全部服务，并检查管理员登录与执行器。

安装完成后，终端显示管理员 `admin` 的初始密码。应用入口是 `http://部署服务器IP:18100/`，MCP 管理入口是 `http://部署服务器IP:18101/`，两个入口的初始管理员凭据相同。按[授权安装步骤](PORTABLE-INSTALL.md#2-安装产品授权)将授权文件及公钥放入 MCP 的持久化数据目录，再在管理端刷新授权状态。旧版示例使用 `/data/runtime-os` 作为安装目录，请以 MCP 实际挂载路径为准。未安装授权时可以登录，业务空间和受授权保护的目录保持关闭。

需要其他端口或指定初始密码时，在 `docker run` 中增加可选参数：

```bash
-e HTTP_PORT=18100 -e MCP_PORT=18101 -e ADMIN_PASSWORD='ChangeMe-StrongPassword-2026'
```

端口和初始密码在首次安装时保存，重启沿用已有配置。另装一套环境时，使用不同的空目录，并设置不同的 `PROJECT`、`HTTP_PORT` 和 `MCP_PORT`。

## 查看、停止和恢复

使用同一个镜像、同一个数据目录，将命令最后的 `install` 换成需要的操作：

```bash
# 查看每个服务状态
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v /data/runtime-os:/install runtime-os-installer:20261115-docker-only status

# 停止整套系统，保留数据
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v /data/runtime-os:/install runtime-os-installer:20261115-docker-only stop

# 恢复启动，继续使用原配置和数据
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v /data/runtime-os:/install runtime-os-installer:20261115-docker-only start

# 查看 API 日志
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v /data/runtime-os:/install runtime-os-installer:20261115-docker-only logs api
```

服务会随 Docker 自动重启；镜像内的管理服务负责重新核对专用执行器进程身份。

## 数据保存在哪里

| 宿主机目录 | 内容 |
| --- | --- |
| `/data/runtime-os/data/postgresql` | API、MCP、News 数据库 |
| `/data/runtime-os/data/temporal` | 工作流数据库和历史 |
| `/data/runtime-os/data/opensearch` | 检索索引 |
| `/data/runtime-os/data/silo` | 文件及任务产物 |
| `/data/runtime-os/data/redis` | Redis 持久化数据 |
| `/data/runtime-os/data/api`、`data/mcp`、`data/news` | 应用持久化数据 |
| `/data/runtime-os/n` | Native 会话、执行状态和审计记录 |
| `/data/runtime-os/config`、`certs` | 本次安装自动生成的配置和证书 |
| `/data/runtime-os/installation.json` | 本次安装信息及私密初始凭据，权限 0600 |

上述目录都在镜像外。更新或删除容器不会自动删除这些数据。备份时需要按数据库及索引的备份方式保存一致性数据，不直接复制正在写入的数据库文件。

开发者的镜像生产过程见 [BUILD.md](BUILD.md)。部署用户无需运行其中的构建脚本。

各镜像的用途、外置目录和单服务启动维护命令见 [IMAGES.md](IMAGES.md)。
