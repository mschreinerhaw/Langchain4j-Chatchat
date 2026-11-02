# 本地源码 Docker 打包验收（2026-11-02）

入口：`build-docker.ps1` / `scripts/build-docker-release.py`。使用方法见 [构建与部署说明](../deploy/docker/LOCAL-BUILD.zh-CN.md)。

## 实际验证

- Windows PowerShell 包装入口、计划预览及附加参数转发通过。
- 13 项构建/交付单元测试通过：Windows 执行 12 项，Linux 容器执行另 1 项，覆盖双前端资源、嵌套 JAR 秘密文件名、Linux CLI 架构、错误退出、源码目录隔离、部署资产白名单、归档校验、镜像 ID 和现场模板保护。
- 14 项统一部署测试通过，包括已有环境备份保护、新装不要求备份不存在的数据，以及外置驱动布局保留。
- 真实执行 `python scripts/build-docker-release.py --tag local-source-20261102-r4`，两个 Vue 前端及 18 个 Maven 模块 clean/package 全部成功。此轮 Maven 使用默认跳过测试选项，没有把构建成功等同于全量测试或业务验收成功。
- API、MCP、News、Coding Agent、API 干净运行时和 Python 沙箱镜像构建成功；Nginx、PostgreSQL、Redis、OpenSearch、Temporal 一并导出，共 11 个镜像。应用镜像验证只有一层业务 JAR COPY。
- Python 沙箱在 UID/GID 10001、只读根目录、无网络、无额外 capabilities 条件下验证 numpy/pandas 和中文图表 PNG/SVG 渲染成功。
- 真实导出并重新 docker load，全部包内 SHA256SUMS、11 个镜像 ID 及 linux/amd64 平台检查通过。压缩归档流式读取成功，共 111 个文件/目录成员。
- 在无网络、无 Docker socket 的隔离 Linux 容器中执行交付包的 init → prepare → plan，并调用实际 Docker Compose 二进制，成功生成 6 个应用/代理服务、4 个基础设施服务和 2 个持久化 Temporal 服务的定义。驱动目录只读挂载及搜索 CA 配置路径断言通过。此测试未启动业务服务。

## 产物

本地目录：`dist/docker-release/local-source-20261102-r4/`。

- 交付包：`runtime-os-local-source-20261102-r4-linux-amd64.tar.gz`
- 大小：3,418,529,252 字节。
- SHA-256：`41463d9f466373e0c288afdf57e7bf1f1b4d9c3c505fb8c033cd72102b400f7b`
- 发行清单记录 Git revision、源码内容摘要、JAR 摘要、实际镜像 ID 和 `businessAcceptance: NOT_RUN`。
- 构建日志、计划、结果在同目录；客户密钥、License、模型和实际业务数据未参与此构建或验收。

原 Temporal 默认标签 `temporalio/auto-setup:1.31.2` 实际拉取返回 not found，已修正为已成功获取的 `1.29.7`，参见 [Temporal 官方镜像标签](https://hub.docker.com/r/temporalio/auto-setup/tags)。新装使用明确的持久化拓扑；已有 Temporal 环境的升级仍需沿用既有版本及受控迁移流程。

## 试运行中修复的问题

首次试运行曾进入原仓库 target 清理，发现后立即停止。受影响工作树的已提交源码已恢复，主目录 API 前端依赖已重装；Git 中的源码和提交完整。部分 target 中历史构建、验收临时产物需要重新生成。后续流程已改为在独立 `dist/.../work/source` 副本中 clean/package，增加了目录保护测试，完整重跑成功。

本次未连接或更新部署服务器，也未执行模型、采集源等客户现场业务验收。实际部署需先填写现场配置，再运行交付包的 check/up/verify。
