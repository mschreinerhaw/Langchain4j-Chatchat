系统容器维护交接
本目录为 2026-11-15 标准镜像维护入口。docs 下每种镜像均有启动与构建说明。
执行 ./start-all.sh 按依赖顺序启动；执行 ./status.sh 检查状态。
只保留原 OpenSearch、Silo、Redis；其余必要服务通过 Compose 启动。旧验收环境不会恢复。
标准依赖：PostgreSQL 18.6、Temporal Server 1.29.7、Nginx 1.28-alpine，Compose 固定实际 digest。
自研应用：API、Native Node、Studio、MCP、News，以及浏览器资源服务，均保留源码构建入口。
Compose 路径、具体角色、数据挂载和升级方法见各镜像文档。private 下是私密配置与原始配置备份，不纳入源码仓库。
原三个保留服务由原容器继续运行；没有把已有数据切换到空的新卷。
系统存在专用受限执行 Docker daemon，其进程身份和策略文件属于运行时依赖，不随主 Docker 的镜像清理重置。

OpenSearch、Silo、Redis 常用启动维护命令汇总：OpenSearch-Silo-Redis-启动维护.zh-CN.txt。

包含外置目录的完整重建命令：Silo-OpenSearch-PostgreSQL-外置目录启动.zh-CN.txt；可执行脚本位于 rebuild/。
