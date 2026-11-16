# 服务器上的启动和发布操作

## 1. 先登录服务器

在你自己的电脑执行：

```bash
ssh -p 5221 root@192.168.195.221
```

输入服务器密码后，在服务器执行：

```bash
cd /opt/runtime-os/standard-maintenance-20261115
```

**后面的命令全部在这个目录执行，不需要你进入不同的 Compose 目录。**

## 2. 如果只是要把系统启动起来

直接执行这两行：

```bash
./manage.sh start all
./manage.sh status
```

工具会先启动数据库、检索和存储，再启动 MCP、业务服务和网关。已运行的容器继续使用。最后在浏览器打开 `http://192.168.195.221:18100/`，用现有管理员账号登录。

正常状态：必要容器显示 `running`；设置了健康检查的容器同时显示 `healthy`。没有健康检查的容器该列为空。浏览器能登录并打开工作台，是基础启动验证；具体业务任务另行验收。

## 3. 每个镜像单独怎么启动

复制右侧命令执行。它会先启动该服务需要的依赖，你不需要自己判断启动顺序。

| 要启动的服务 | 对应镜像 | 在服务器执行的命令 |
| --- | --- | --- |
| OpenSearch 检索 | `opensearchproject/opensearch:2.19.1` | `./manage.sh start opensearch` |
| Silo 文件存储 | `pgsty/silo:latest` | `./manage.sh start silo` |
| PostgreSQL 数据库 | `postgres:18.6` | `./manage.sh start postgresql` |
| Redis 缓存 | `redis:7.4-alpine` | `./manage.sh start redis` |
| News 资讯服务 | `runtime-os-news` | `./manage.sh start news` |
| MCP 工具服务 | `runtime-os-mcp` | `./manage.sh start mcp` |
| API 和两个 Worker | `runtime-api` | `./manage.sh start api` |
| Temporal 工作流 | `temporalio/server:1.29.7` | `./manage.sh start temporal` |
| Node、Executor、Recovery | `runtime-node` | `./manage.sh start native-node` |
| Studio | `native-studio` | `./manage.sh start studio` |
| 浏览器资源和预览 Worker | `runtime-frontend-resource-hub` | `./manage.sh start frontend-resource-hub` |
| Nginx 业务入口 | `nginx:1.28-alpine` | `./manage.sh start nginx` |

一个镜像可以用于多个容器。比如启动 `api` 会处理控制 API 和两个 Worker；启动 `native-node` 会处理两个 Node、两个 Executor、两个 Recovery。

容器还存在时，工具启动原容器。容器已删除时，工具使用现有 Compose 或该镜像的 `run.sh` 创建容器，并沿用原外置目录。这是现有服务器的恢复入口，不会在空服务器生成新的数据库、密钥或业务数据。

## 4. 查看、停止、重启

以 MCP 为例：

```bash
# 看 MCP 及其依赖的状态、镜像版本
./manage.sh status mcp

# 查看 MCP 最近 100 行日志
./manage.sh logs mcp

# 停止 MCP，同时停止依赖它的上层服务
./manage.sh stop mcp

# 重新启动 MCP 及其必需依赖
./manage.sh start mcp

# 恢复整套系统，包括此前停止的上层服务
./manage.sh start all
```

若只需要一次完整重启：

```bash
./manage.sh restart mcp
```

`restart` 会记录原先运行的依赖服务，并按顺序恢复它们。停止、重启、发布前会检查业务任务是否空闲。仅启动 MCP 不会自动开放 Nginx；需要恢复整个网站时使用 `start all`。

在任何操作末尾加 `--dry-run`，可以只看操作范围：

```bash
./manage.sh restart api --dry-run
```

## 5. 修改源码后，怎样构建和发布

这里的“发布”指**把新镜像部署到这台服务器**，不向 Docker Hub 或其他仓库上传。

当前业务源码目录：`/opt/runtime-os/releases/latest-main-20261115/source`。先把需要发布的源码更新到这里，再执行构建。浏览器资源服务源码目录单独为 `/opt/frontend-resource-hub/1.0.0`。工具会进入正确的源码目录构建。

例如更新 API：

```bash
# 第一步：服务器编译源码，生成一个未使用过的新版本镜像
./manage.sh build api --image runtime-api:release-20261115-r2

# 第二步：构建成功后，先看上线会影响哪些服务
./manage.sh deploy api --image runtime-api:release-20261115-r2 --dry-run

# 第三步：结束正在执行的业务任务，然后上线
./manage.sh deploy api --image runtime-api:release-20261115-r2

# 第四步：确认实际运行的新版本
./manage.sh status api
./manage.sh status nginx
```

`build` 只生成镜像；`deploy` 才会修改运行服务。API 发布会同步提取新镜像里的前端资源并交给 Nginx，重建共享网络的相关服务。版本号不可重复使用，避免覆盖旧镜像。

其他自研服务使用相同的两步：

| 服务名 | `--image` 新版本示例 |
| --- | --- |
| `mcp` | `runtime-os-mcp:release-20261115-r2` |
| `news` | `runtime-os-news:release-20261115-r2` |
| `native-node` | `runtime-node:release-20261115-r2` |
| `studio` | `native-studio:release-20261115-r2` |
| `frontend-resource-hub` | `runtime-frontend-resource-hub:release-20261115-r2` |

比如 MCP：

```bash
./manage.sh build mcp --image runtime-os-mcp:release-20261115-r2
./manage.sh deploy mcp --image runtime-os-mcp:release-20261115-r2
./manage.sh status mcp
```

发布会保存旧配置、旧镜像引用和运行范围，保留数据挂载；启动失败时尝试恢复旧配置和服务。记录保存在服务器 `private/releases/`，命令会打印本次记录的完整路径。数据库模式变更需要单独执行迁移，不能靠镜像降级恢复数据库。

OpenSearch、Silo、PostgreSQL、Redis、Temporal、Nginx 是上游镜像，无须本机编译；其版本升级按对应文档处理，不使用自研服务的 `build/deploy` 命令。

## 6. 什么时候需要看每个镜像的 README

正常启动、查看和发布，按本页操作。需要核对外置目录、端口、环境变量或手工创建命令时，再看 `docs/服务名/README.md`。

例如：`docs/silo/README.md` 列出数据、密钥、策略文件挂载；`docs/opensearch/README.md` 列出 data、logs、plugins、config；`docs/postgresql/README.md` 列出实际数据库目录和保留卷。


## API ?????????

API ? Temporal?Worker?Node?Recovery?Studio ?????????API ????????????????? running?NetworkMode ????? API ID??????????????????? Temporal 127.0.0.1:7233 connection refused???? network is unreachable?MCP DNS ?????

????? start ? ready ??? /proc/<pid>/ns/net ??? inode??? `./manage.sh start all` ???????????? Compose ??????????????? docker ps??? ID ??????????????API ???????????????????????
