# 应用部署平台实施约束

2026-11-16 用户确认：deployment-ops 是 langchain4j-chatchat 的 Maven 子模块，后端 Spring Boot，前端 Vue 3 + Vue CLI。参考 Ambari 的服务、组件、主机、安装向导和任务组织方式，目标为快速部署与自动化运维监控。

## 基础启动约束（优先级高于旧版部署说明）

用户追加要求：Deployment Manager 自身禁止容器化，必须以宿主机本地 Java 进程运行，使用嵌入式 H2 文件数据库。平台是整个系统的启动基础，由平台启动 Runtime OS 应用。平台可执行程序、静态页面、配置、数据库和启动工具均须在业务应用未运行时可用。

首发安装包采用本地发行目录，提供 JAR、Vue 构建产物、本地节点执行器、配置模板、启动/停止/状态脚本与 systemd 单元。平台默认不以 root 身份运行；拥有宿主机 Docker 权限的节点执行器与 Web 进程隔离。平台启动不连接业务数据库、Temporal、业务认证、镜像仓库或 Docker 才能完成自身初始化，运行时不可用应体现为目标状态未知/不可部署。

所选平台目录用于数据库、发布库、任务状态和日志，应用数据目录可单独选择。支持系统盘或独立数据盘，`/mnt/disk01` 仅为旧服务器配置。独立挂载检查由管理员选择；systemd 保留实际选择的路径，防止配置的文件系统丢失后静默切换。平台只管理自己的子目录，不能接管整个盘。

平台服务与业务应用不建立 systemd 的 PartOf/BindsTo 关系；停止或升级管理平台不能级联停止应用。应用开机自启动策略由平台显式配置，平台故障恢复不能自动重放未确认的部署/破坏性任务。

此前 `compose.yaml`、Dockerfile 和 Docker 离线管理平台发行脚本仅属于历史 P0，不作为新发行版安装入口，也不能用其验收报告证明新版本通过。

## 部署边界

- 第一阶段完整实现 Runtime OS，Docker 为首个执行适配器，保留其他运行方式的接口；不声明已支持 systemd。
- 管理平台独立于业务认证、业务数据库和业务任务系统。控制面停止不应停止、重建或恢复业务工作负载。
- 现有容器、镜像、卷、网络先只读发现。关联现有应用不等于取得生命周期管理授权。
- 新验收目录位于 `/mnt/disk01`。已只读确认该目录为独立 `/dev/sdc` XFS 挂载，实际容量以安装时检查为准。后续安装必须再次检查挂载、容量和内存，禁止挂载丢失后静默写入根盘。
- 当前运行的 P0 管理平台和业务环境不随源码迁移自动替换。

## 核心对象和流程

应用定义 → 不可变发布版本 → 部署实例 → 服务/组件 → 运维任务。主机和运行时资源用于定位故障，不作为业务主导航的中心。

用户主流程：上传标准发布包 → 校验签名与清单 → 选择版本和目标主机 → 填写配置 → 预检 → 确认执行计划 → 查看任务步骤 → 查看实时应用健康。

发布包必须包含版本化清单、组件依赖、配置定义、不可变制品标识、校验和、签名、资源要求、健康规则和兼容性约束。平台安装包与 Runtime OS 应用发布包是不同产品。在线上传不得直接触发镜像导入或应用安装。

执行器负责解释已批准计划。配置文件按声明生成，敏感配置单独保管；权限在制品构建及声明式初始化中完成，禁止通过 chmod 777、吞掉异常或无限重试掩盖错误。

## 健康与自动化

部署任务成功与当前健康分开存储。应用、组件、主机分别采集状态和采样时间；失联为 UNKNOWN，历史成功不能表示实时健康。第一阶段需要覆盖入口、API、Temporal、worker、coding node、恢复节点和依赖存储的真实检查。

自动化第一阶段包含定时采样、异常事件、诊断与受控运维任务。自动重启、数据迁移和版本回滚必须有明确策略、所有权与兼容性校验，不能默认作用于发现的业务资源。

## Native implementation and acceptance

The native Spring Boot application now hosts the persisted authentication, audit,
resource plans and signed-package APIs. The Vue console uses lazy domain routes,
shared state/API clients and independent components for deployment, applications,
containers, monitoring, tasks and system management. Legacy verified source remains
available without starting a second legacy Web server.

The signed Native/Temporal Runtime OS adapter uses the reviewed source-built
product installer, immutable images, dedicated project/data scope and explicit
compatible upgrades. Real deployment of all 24 services, OS/MCP administrator
login, Native Core/Python checks, upgrade, failed-release rollback, platform
replacement and H2 restore have execution evidence. The target host compiles Java
from source using only the bundled dependency archive.

Current release status and exact remaining gates are maintained in
[V1 acceptance ledger](V1-REMAINING-WORK.md); installation and maintenance are in
[native operations](NATIVE-OPERATIONS.md). Mandatory real execution evidence is
archived under `docs/evidence/native-product-20261117`. A formal package requires
all mandatory scenarios, the exact source commit/JAR and checked evidence hashes.
Historical Compose results do not stand in for native-platform acceptance.
