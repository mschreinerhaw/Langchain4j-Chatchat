# 部署入口

- [当前服务器逐容器维护命令](CONTAINER-OPERATIONS.zh-CN.md)：以 `/opt/runtime-os-docker-stage` 为标准目录，列出 Nginx、API、MCP、Redis、Coding Agent 及存储依赖的独立启动、停止、重启、验证和代理重建命令。

- [完整应用 Compose](docker/application/compose.yaml)：当前 PostgreSQL 架构；包含 PostgreSQL、Redis、启用 TLS/认证的 OpenSearch、安全初始化任务，以及 API、MCP、News、Coding Agent 和代理。必需依赖默认启动，无需另外寻找外部容器。
- [部署配置示例](docker/application/deployment.env.example)与[镜像构建、数据和授权说明](docker/application/README.md)。
- [实际验证范围和未完成项](docker/application/VALIDATION.md)。
- [Docker 手工维护命令](DOCKER-MAINTENANCE.zh-CN.md)：依赖清单、启动顺序、逐服务 `docker run`、备份恢复与可选依赖。
- [MySQL 兼容基础设施](docker/docker-compose.yaml)：MySQL 部署使用的替代数据库路径，不与 PostgreSQL 完整栈重复启动同名依赖。
- [外部 Temporal 开发环境](temporal/README.md)与 [Python Sandbox 镜像](docker/python-runtime/README.md)。

完整 Compose 针对新建部署。已有 PostgreSQL/OpenSearch 安装必须先规划数据恢复和连接切换；新建空卷不会自动接管旧数据。修改部署文件不等于已经升级现有服务器。
