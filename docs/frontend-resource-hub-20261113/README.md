# Frontend Resource Hub P0 部署与验证记录

本期范围为资源中心、`frontend-design` Cline Skill、浏览器预览与修复、Runtime/Silo 交付。P1 可视化资源管理界面未纳入本期。

## 部署位置

- 服务器：`192.168.195.221`，SSH 端口 `5221`。
- 资源服务目录：`/opt/frontend-resource-hub/1.0.0`。
- 资源服务入口：服务器本机 `http://127.0.0.1:18220/api/v1/health`；静态资源通过现有内部 Coding 网络共享。
- 容器：`frontend-resource-hub-frontend-resource-hub-1`、`frontend-resource-hub-frontend-preview-worker-1`。
- 既有两个 Native Cline 节点通过任务代理接入。原生执行沙箱仍无外网接口，共享 Hub 密钥不下发给模型或页面。
- 使用提供的应用账号完成登录验证；账号密码及 Hub 密钥未写入项目源码或验收附件。

预览执行接口仅在内部网络开放，并要求认证。需要从本机查看资源 API 时，可通过 SSH 转发服务器的 `127.0.0.1:18220`。正常页面生成任务通过既有 Runtime 入口使用 Skill，无需为最终用户新增登录流程。

## 交付内容

资源清单包含固定版本的 Bootstrap、Tabler、ECharts 6、Lucide SVG、Alpine、DataTables、jQuery、Mermaid；镜像中保留实际资源和许可证。三套主题、五类可运行模板、十类组件均有机器可读元数据。DataTables 同时提供核心与样式集成脚本，Mermaid 提供可离线使用的完整浏览器构建。

两个服务采用非特权用户、只读根文件系统、资源限制和内部网络。预览 Worker 保持 Chromium 用户命名空间沙箱开启；seccomp 配置只补充其启动所需的系统调用。浏览器进程不会继承 Hub 密钥环境变量。任务仅能通过既有代理读取自己创建的预览任务。

实现与接口说明见 [服务文档](../../frontend-resource-hub/README.md)，Skill 入口见 [frontend-design](../../coding-agent-node/skills/frontend-design/SKILL.md)。

## 验证结果与边界

执行了 21 项原生 Skill、任务边界、代理隔离及 Silo 传输回归测试，全部通过；Skill 格式检查通过。最终 npm audit 为零漏洞。

容器内真实 Chromium 验证五类模板在 1440×900 和 390×844 下的渲染、截图、资源加载、图表和布局；故意注入 JS 异常、外部元数据请求及横向溢出，确认失败报告后再验证修复版。另验证 DataTables 实际筛选、Bootstrap、Alpine、SVG 图标与 Mermaid 渲染。

五类业务场景均经真实模型、原生 Cline、既有 Temporal 调度、预览 Worker 和 Runtime Silo 归档。最终选定版本共 70 个产物，通过应用认证接口下载后，大小和 SHA-256 全部匹配；下载的独立页面在断网条件下完成数据映射与交互复验。

| 场景 | 最终验证 |
|---|---|
| 证券资产与风险分析 | 保留测试账户与资产值；图表映射、修复历史、双端截图及 Silo 下载通过 |
| Excel 台账 | 实际生成并读取测试 Excel；两条发票记录、120/150 金额、筛选和恢复列表通过 |
| 报表下发与确认 | 严格保留两条报表记录，消除重复行；图表和等待业务 API 的确认状态通过 |
| OA 数据提取审批 | 申请编号、申请人、数据集及本地确认状态通过 |
| 监控驾驶舱 | 深色页面、120/150 队列指标、图表映射和离线渲染通过 |

测试数据均明确标记为合成数据，未访问客户业务 API，也未执行真实审批或报表下发。这里验证的是场景生成能力、已知测试数据映射及交付完整性，不替代生产数据和真实业务接口验收。Runtime 原有的 `INCONCLUSIVE` / 未评估业务语义状态予以保留，不修改为业务成功。

## 已发现并修复的问题

真实业务复验发现，生成页同时加载通用 `bind.js` 与自定义渲染器，会使台账查询清空表格、使两条报表重复显示为四条。原始失败产物和回执保留在 Silo；将原始下载文件重新作为受控任务输入，交由 Cline 修复并生成新的不可变交付版本。最终复验使用可见 DOM、精确行数和实际操作结果，未以脚本源码中出现测试值作为通过依据。Skill 已补充单一绑定策略要求。

首次容器验证还修复了 tmpfs 配置、镜像用户 UID 和 Chromium 沙箱系统调用配置问题。最终保留沙箱，未使用 `--no-sandbox` 或特权容器。

## 发布、回滚与证据

服务镜像为 `frontend-resource-hub:1.0.0`，两个服务使用独立容器与不同启动命令。Native 节点镜像为 `runtime-os-coding-node:frontend-hub-1.0.0`。更新前检查无活动或不确定任务，保留原配置及镜像；只重建本次相关服务和 Native 节点，未修改应用、Temporal、数据库或 Silo 容器。

服务器保留 `frontend-resource-hub:rollback-20261113`、`runtime-os-coding-node:frontend-hub-rollback-20261113` 以及 `/opt/frontend-resource-hub/native-backup`。回滚时应先确认无活动任务，再恢复对应镜像/配置并重建相关服务。不得删除 Silo 数据、共享镜像或其他任务的持久化目录。

隔离验收临时容器及网络已清理；本地验证服务已停止。正式及回滚镜像保留。`evidence` 中包含最终部署元数据、浏览器报告、原生修复历史、Silo 下载哈希、离线复验及截图。服务器的原始任务与审计记录保留用于追溯。

离线发布包位于服务器 `/var/tmp/frontend-resource-hub-releases/frontend-resource-hub-1.0.0.tar.gz`，大小与 SHA-256 见 [镜像清单](evidence/image-distribution.json)。可使用 `docker load -i` 导入。该文件放在空间充足的另一分区，未挤占 `/opt` 所在分区。另已清理本次构建产生、无标签且未被任何容器引用的三个中间镜像，未使用强制删除或全局 prune。
