# 开发者构建交付镜像

此文档供构建人员使用；客户部署仅使用交付的镜像包与 Docker。

1. 在构建机由当前源码编译 API、MCP、News、Native 镜像，完成各自验证。
2. 准备匹配 OpenSearch 2.19.1 的 ICU、IK、pinyin 插件工件和需交付的 JDBC 驱动工件。
3. 执行 `prepare-build.py`，选择上述应用镜像及源码目录。它从镜像中的实体元数据导出对应数据库建表 SQL，将驱动、插件和初始化程序封装入镜像，并导出完整镜像包。

```bash
python3 deploy/standalone/prepare-build.py \
  --root /build/runtime-os-release \
  --source /build/Langchain4j-Chatchat \
  --api-image runtime-api:SOURCE_VERSION \
  --mcp-image runtime-os-mcp:SOURCE_VERSION \
  --news-image runtime-os-news:SOURCE_VERSION \
  --node-image runtime-node:SOURCE_VERSION \
  --plugins /build/vendor/opensearch-2.19.1/plugins \
  --drivers /build/vendor/jdbc-drivers \
  --frontend-source /build/Langchain4j-Chatchat/frontend-resource-hub \
  --version 20261115 --export
```

构建机需要 Docker、Python3 和已安装的 Docker Compose 插件；这些工具会随安装镜像交付，不成为部署机前置条件。OpenSearch 插件只收集已审核的 JAR、字典及许可等工件，配置使用源码中的模板，禁止拷贝运行环境的配置、索引、用户或密码。

输出包括 `runtime-os-20261115-docker-only.tar.gz`、`SHA256SUMS`、`release-images.json` 和驱动/插件哈希记录。镜像包包含安装器、业务服务、数据库、检索、存储、工作流、浏览器预览和专用执行 daemon。

发布前必须使用空安装目录完成 Docker-only 冷启动、管理员登录、存储版本读写、执行器策略校验及停止/恢复验证。既有生产环境的脚本运行成功不替代这项验证。

```bash
python3 deploy/standalone/tests/cold-docker-host.py \
  --archive /build/runtime-os-release/runtime-os-20261115-docker-only.tar.gz \
  --output /build/runtime-os-release/acceptance
```

验收器在构建机创建独立的空 Docker daemon，禁止其联网下载，仅用 Docker 加载并安装镜像，随后检查登录、受限执行、停止/恢复及执行引擎重启。验收日志中的 `*.private.log` 含测试环境初始凭据，禁止随公开交付包分发。
