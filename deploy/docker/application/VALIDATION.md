# 完整依赖编排验证记录

日期：2026-10-26。范围是本目录的部署配置、初始化脚本与维护命令，不代表一次完整业务发布。

| 检查 | 结果 |
| --- | --- |
| Compose 展开、持久化路径、DNS 回调、必需依赖、无环启动图及健康条件 | 通过 |
| Nginx 配置、重定向及 Worker 更换 IP 后代理恢复 | 通过；与上项合计 9 项测试 |
| PostgreSQL 全新空卷导入 API/MCP schema | 通过；包含完成标记及 TCP 就绪 |
| 两套数据库密码认证、跨库拒绝、重启后数据保留 | 通过 |
| Redis 认证健康检查 | 通过 |
| OpenSearch 自建临时 CA/TLS、安全索引初始化及认证健康 | 通过 |
| 重复初始化、错误密码阻断、恢复正确密码后验证 | 通过，不覆盖已初始化用户/权限 |
| API/MCP/News 的新 HTTP/gRPC 探针 | 在当前服务器容器中只读执行，通过 |
| 默认固定版本镜像的完整冷启动 | 未完成，Docker Hub 连接超时，不能解释为通过 |
| 采用新栈部署 Java/Node、迁移现有数据库、完整业务验收 | 未执行；现有服务未被本次配置改造替换 |

真实基础设施测试在一个随机 `runtime-infra-test-*` Compose 项目中完成，使用新卷、新临时证书、随机密码和随机宿主端口；结束后只清理该项目资源。没有连接线上数据库、修改宿主信任库或修改 `vm.max_map_count`。测试专用覆盖关闭 mmap 以适应宿主当前设置；正式部署仍需 `vm.max_map_count >= 262144`。

由于默认镜像不可下载，实际验收使用服务器已安装的 `postgres:18`、`redis:latest`（实测 Redis **6.2.6**）及 `opensearchproject/opensearch:2.19.1`。PostgreSQL 测试覆盖显式设置旧布局 PGDATA，避免 18 版本路径变化；不据此声称默认 PostgreSQL 16.9、Redis 7.4、OpenSearch 2.12 已完成冷启动验收。默认版本沿用仓库兼容线，正式发布应导入已审核镜像后原样重跑。MySQL 替代栈复用同一 OpenSearch 初始化脚本，未执行 MySQL 真实初始化。

服务器私有日志：`/opt/chatchat-releases/fullstack-compose-20261026/compose-tests.log`、`infrastructure-installed-images-tests.log`；失败尝试用于定位网络冲突、文件权限及初始化时序，不计入成功结果。

在已安装默认镜像、可用 Docker 的 Linux root 维护主机上复现：

```bash
python3 -m unittest discover -s deploy/docker/application/tests -p test_application_deployment.py
RUNTIME_INFRA_DOCKER_TESTS=1 python3 -m unittest discover \
  -s deploy/docker/application/tests -p test_infrastructure_bootstrap.py
```

只有明确选择兼容性验证时才设置 `RUNTIME_TEST_POSTGRESQL_IMAGE`、`RUNTIME_TEST_REDIS_IMAGE`、`RUNTIME_TEST_OPENSEARCH_IMAGE`。不设置时测试使用 Compose 默认镜像。默认测试发现会跳过真实基础设施测试，防止普通单元测试隐式启动数据库；启用后不会跳过其中业务断言。
