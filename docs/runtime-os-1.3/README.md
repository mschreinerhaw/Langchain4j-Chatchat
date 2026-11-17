# Runtime OS 1.3：分支合并、镜像与现有环境部署

目标服务器：`192.168.195.221:5221`。本次发行采用现有 Deployment Ops 的原生 Spring Boot / H2 服务，平台未容器化。管理页面为 `http://192.168.195.221:19240`，Runtime OS 为 `http://192.168.195.221:18100`，MCP 入口为 `http://192.168.195.221:18101`。

## 代码与二进制来源

先从远程主分支 `78ef0092159755833fb48f99a1cbf50c003e071d` 建立隔离发行工作树，将 `feature/deployment-ops-offline-20261115` 的有效提交合入，再快进合并、推送主分支。原工作树和未提交成果均保留。未使用 reset 或 clean。

主分支的发行入口为 [release.json](../../deploy/releases/1.3/release.json)，镜像以 [images.lock.json](../../deploy/releases/1.3/images.lock.json) 中的 SHA-256 ID 为准，部署清单不以可变标签判断镜像身份。验收目录中另保留锁文件的原始字节副本。

* Coding Node / Studio：源码 `2da926ecf1e6c62d8de00b4aa4118ae99ea61637`，镜像 `sha256:59110e7ce5bda8f67427c5f7e854f10f734f938d3ec97954217f715f7a7a60d9`。包含已合并的工作区权限初始化和 UTF-8 修正。
* 现有环境安装器：源码 `4fdae34cb3bd1267599dbba834e7f08a731fa620`，镜像 `sha256:5c01965be45af357918226003fba1eb0909db78123842b67ba797f52876879c6`。
* API、MCP、News、Temporal Java 业务源码及资源与 `5892f5b46` 比较无差异，因此复用现有不可变镜像；没有宣称重新编译这些业务镜像。发行标签为 1.3，具体组件身份以锁文件为准。
* 管理平台支持补丁 JAR 为 1.1.3，源码 `4fdae34cb3bd1267599dbba834e7f08a731fa620`，在服务器运行 `PATH=/nonexistent /opt/jdk-17.0.5/bin/java scripts/BuildNativeOffline.java ...` 编译。Python 安装器运行在业务安装容器内，平台宿主运行仍只依赖 JDK。
* 服务器计划生成器源码为 `58bb570d7`；它是独立发布工具，负责固定原环境配置哈希、公开挂载摘要、独立凭据参数和原 seccomp 配置。

## 现有数据复用范围

本次采用已有环境接管，不进行新库初始化、密码重置、业务目录复制或递归属主变更。`/opt/runtime-os-docker-stage` 是既有配置及 Silo 数据来源；其他历史绝对挂载也照原位置保留：

| 对象 | 保留的数据或配置 |
| --- | --- |
| Runtime API | 既有 `runtime_os_acceptance_api` 数据库；`/opt/chatchat-1.0.0-SNAPSHOT/data`；stage/config/api 配置 |
| MCP | 既有 `runtime_os_acceptance_mcp` 数据库；`/opt/chatchat-mcp-server-1.0.0-SNAPSHOT/data`；stage/config/mcp 配置 |
| News | `/opt/chatchat-runtime-news-1.0.0-SNAPSHOT/data` 中已有 H2 数据；stage/config/news 配置 |
| Native / Studio | 原 native-temporal 与 cn2 配置、状态和工作区；仅在新的管理目录生成覆盖配置以固定新版执行镜像 |
| PostgreSQL / Redis / Silo / OpenSearch | 保留原容器及挂载，不交给新项目重建 |

本次项目名为 `ops-env-runtime-stage-v13`，环境名为 `runtime-stage-v13`，共管理 17 个业务组件。管理日志及生成配置位于 `/mnt/disk01/ops/r/6462-stage-v13`，不会把业务历史数据迁到此目录。

数据库备份位于 `/mnt/disk01/deployment-ops-jdk/stage-pre-1.3-backup`：`api.dump`、`mcp.dump`、`temporal_native_04547595.dump`、`temporal_native_vis_04547595.dump`。四个备份均通过 `pg_restore --list` 检查，目录中的 SHA256SUMS 只包含这四个有效文件。此前猜测错误数据库名产生的空文件明确保留为失败证据，不属于有效备份。

## 离线介质与复现

最终签名应用包：

```text
/mnt/disk01/ops-publisher-20261116/runtime-os-1.3-stage-reviewed.ops.zip
SHA256 9ea423d962359935ac5569f8dcdf91e3410993690ff05ee9d10b954272550075
```

它包含本次所需的完整镜像归档、安装协议及签名。已有环境的私有计划单独保存在服务器的受限目录，不含在公开发行包或 Git 中：

```text
/mnt/disk01/deployment-ops-jdk/stage-plans/runtime-stage-1-3-reviewed-final.json
SHA256 fd439634e6a53524749764036a65f999c3c1ef821c6ecd37eec65f365549dfe0
```

该包面向这台服务器的既有配置和数据，不能直接拿到另一台空白服务器上接管不存在的目录。首次安装平台请按 [JDK 运维说明](../../deployment-ops/docs/JDK-OPERATIONS.md)；新业务环境使用常规安装协议，不能冒用本次私有接管计划。

在目标平台的镜像管理中导入发行包并校验 SHA-256，然后进入部署发布，选择 `runtime-stage-v13` 和版本 `1.3`，审阅实际目录、只读/读写方式及启动依赖。页面分别填写原 Runtime 管理员和原 MCP 管理员密码；它们可以不同。确认环境名后提交，查看任务、组件和日志。重复提交前必须检查现有任务状态，不得绕过执行中或失败待恢复的任务。

平台管理数据位于 `/mnt/disk01/deployment-ops-jdk/data`。平台服务为 `deployment-ops-jdk`。停止或重启该服务只操作 JAR，不操作业务 Compose 项目。业务生命周期操作必须在应用页面明确选择相应环境。

## 失败处理与边界

两次候选版本暴露的真实问题均保留在任务历史中：News 的不存在健康路由、前端 seccomp 相对路径、非 root 网关的配置读权限，以及 API/MCP 管理员密码不同。最终协议采用实际受保护的 News 健康路由、归档中的原 seccomp 内容、可读且哈希固定的网关配置，以及独立秘密参数。没有使用 `seccomp=unconfined` 或重置密码。

候选任务结束前，仅对确认属于 `ops-env-runtime-stage-v13` 的 17 个组件进行停止，保留其数据、日志和容器；通过页面显式结束已静止的初次失败任务。最终包不依赖候选阶段临时复制的 `seccomp_profile.json`。

为满足离线包导入的磁盘预留检查，早期 1.0.0 平台离线归档经过逐字节 SHA-256 校验后保留到 `/opt/runtime-os/releases/retained-platform-1.0.0`。当前 1.1.0 正式平台介质仍在原 `/mnt/disk01/deployment-ops-releases/1.1.0` 位置。没有删除业务镜像、卷或持久化数据。

## 实际验收结果

最终页面部署任务 `2476e3cdc8f94b1bbe64712bb9ac5bd8` 已成功，版本为 `1.3`，17 个组件均运行。三个真实 Unix Socket 执行器返回 HEALTHY 且 `resourceEnforced=true`；安装器实际执行 Native Core、原生模块与 Python 运行环境检查，退出码为 0。平台重启后，H2 中的活动版本和任务状态仍正确，页面仍显示真实监控。

数据复核保留 API 原有 125 张表、MCP 原有 63 张表；关键数量与部署前一致：12 个用户、12 个模型配置、5 个工作区注册记录、33 个用户工作区、1 个 MCP 管理员。原 PostgreSQL、Redis、Silo、OpenSearch 的 ID、StartedAt 和挂载均未变化，原配置文件哈希保持一致。最终实际 bind mount 与审阅计划逐项匹配，前端 worker 的实际 seccomp JSON 与原策略一致。

平台停止及重新启动期间，46 个运行中的业务容器 ID 和 StartedAt 均未改变，Runtime 的 `/version.json` 持续返回 1.3。平台 1.1.2 → 1.1.3 也通过真实 Vue 升级页面完成，原业务容器身份、状态及 H2 配置保持不变。

| 验证 | 执行结果 | 证据 |
| --- | --- | --- |
| Java 自动化测试 | 21 通过，0 失败，0 跳过 | [报告](evidence/java-tests.json) |
| 新 Node 镜像完整测试 | Linux 实际容器中 129 通过，0 失败，0 跳过 | [报告](evidence/node-tests.json) |
| 工作区权限与 locale 专项 | Linux root 实际容器中 10 通过，0 失败，0 跳过 | [日志](evidence/runtime-os-node-1-3-root-tests-summary.txt) |
| 完整介质校验与签名/实际镜像验证 | 通过 | [导入日志](evidence/final-package-import.txt) |
| 真实页面提交最终 1.3 | 通过 | [部署结果](evidence/final-deployment/result.json) |
| 实际数据、挂载、原容器、seccomp | 通过 | [核验结果](evidence/final-data-verification.json) |
| 平台停止/重启隔离 | 通过，46 个业务容器未重启 | [核验结果](evidence/final-platform-restart-isolation.json) |
| 重启后真实资源监控和活动版本 | 通过，3 个执行器均健康 | [页面结果](evidence/final-monitor-after-restart/result.json) |
| 平台页面升级 | 通过，1.1.2 → 1.1.3 | [升级结果](evidence/platform-credentials-update/good-result.json) |

汇总见 [acceptance-report.json](acceptance-report.json)，其中每份证据均固定字节哈希。图像为真实服务器页面截图。测试脚本为 [数据核验](../../deployment-ops/tests/runtime-stage-data-verification.py)、[页面部署](../../deployment-ops/tests/native-runtime-browser.cjs)、[页面监控](../../deployment-ops/tests/runtime-stage-monitor-browser.cjs)。平台 JAR、应用包精确字节数和校验和见 [artifacts-final.json](evidence/artifacts-final.json)。

本次验收范围为版本发布、既有数据接管、组件及执行器运行、独立平台运行和隔离。没有将外部 LLM 可用性、缺少接入信息的 DolphinScheduler 集群、或整套历史业务流程回归声称为已验证。未在这套现有数据上再次注入应用升级失败；此前平台通用升级/回滚验收见 [JDK 发行证据](../../deployment-ops/docs/evidence/final-jdk-20261117)。
