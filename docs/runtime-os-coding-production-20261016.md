# Runtime OS Coding 生产部署验收（2026-10-16）

## 结果与范围

192.168.195.221 的 API 8080、MCP 8090 已部署生产 Coding v1。主分析模型通过 `RUN_CODING` 委托 Coding 子任务，使用已授权、已交付数据的不可变快照执行 Python，结果与证据回到原任务。

Runtime OS 不包含 MySQL 版本、记录计数、中位数或其他业务答案的专用分支。生产默认校验器仅验证结果结构；业务语义校验使用版本化扩展接口。验收中的预期值与独立核验脚本不进入生产运行逻辑。

## 真实生产任务

通过认证后的普通 `POST /api/v1/agent/tasks` 提交“读取221分析应用配置库的MySQL版本信息，并使用Coding/Python统计实际返回的记录数，给出执行结果与验证范围”。模型按现有资产、模板发现、SQL 执行能力读取数据，再自主调用 Coding。

| 项目 | 验收值 |
| --- | --- |
| 主任务 | `69c831bb-732d-3ecb-9b32-2f1b7fe88e1b` |
| 执行 attempt | `att-1-622a804d-bc50-49d6-a0b4-da3dd84ff2f0` |
| Coding 子任务 | `analysis-coding-59f599ccec39319153626b2394b8e9c056e2dcf0160a1bc551c5cd4ce3b22e0e` |
| 主任务状态 | `SUCCESS`，持久事件序号 `75` |
| Coding 状态 | `COMPLETED`，`reconciliationRequired=false` |
| 实际结果 | MySQL `8.4.7`；输入 10 条，Python 输出 `{"record_count":10}` |
| 子任务执行量 | 2 次模型调用，1 次 Python 执行 |
| Runtime 校验 | `INCONCLUSIVE`，业务正确性 `NOT_EVALUATED` |

已保存 input、code、receipt、result、validation、bundle 六类证据。结果证据前缀为 `runtime-evidence:coding:a040358783b6e939730e6d61aeee56919da487702235738420f41e1b514fba28:`。

独立验收直接读取 MCP 归档，校验全部 4 个归档文件的长度与 SHA-256，并从不可变输入 JSONL 重新计数，与结果 JSONL 比较。确认该作用域只有一次 Python operation，结果为 10。此检查属于部署验收，不会将 Runtime 的业务校验状态改写为 PASSED。

最终 API 安装包部署并重启后，重新认证并提交原始请求，返回原任务 `SUCCESS`；随后读取任务与持久 observations，确认答案未变、事件序号仍为 75、Coding 仍为 COMPLETED、六项证据完整、代码执行次数仍为 1。Node 在前一轮重启后也已验证健康与结果持久化。重复提交验收文件为 `coding-production-restart-idempotency.json`。

验收脚本读取旧的 Windows 本地编码请求文件时，曾因默认 UTF-8 解码改变中文内容而触发幂等冲突；统一读取原文件编码并以 UTF-8 发送后通过。未修改原任务、原问题或生产幂等校验规则。

| 归档 | SHA-256 |
| --- | --- |
| input.jsonl | `78066fc0222698d1d76f69ddd85f29bb3b8f00fc910225ca6078c531f06412c8` |
| rows.jsonl | `a10d87fea365a83b8193a9080254af5cf87fa7d0a40fdf05cc6791e7fb9a320f` |
| manifest.json | `6fdedf6592f978662b4cbdc7f5942afa9d9f5748e9942d8d23b0edeee1047792` |

还使用真实模型、同一 MCP/Python 执行通道，独立验收了数值中位数与文本计数两类输入，分别得到 6.5 和 3；这些固定预期仅存在于测试与验收代码。

## 修复内容

- 将 Coding 接入主分析 Harness，继承可信作用域、父任务模型计量与预算，保存契约、输入快照和六类证据，结果经注册后回到主任务。
- 将固定业务计算校验移至测试扩展；生产使用通用 schema 校验，准确区分执行完成、结构有效与业务正确性。
- 修复模板发现把资产元数据展开成额外搜索批次的问题。元数据参与排序和上下文，查询批次数量按调用方明确声明的查询计算，真实超限仍拒绝。
- 修正现有 Agent 配置中的旧工具绑定，补齐 API 服务发现后的模板发现步骤；保留原发布状态。这些是应用配置迁移。
- 明确每次 Python 调用使用新沙箱，避免模型依赖上一次调用的临时文件；收紧 Node 会话容量与完成会话清理。
- 修复 HTTP 入口在幂等检查前随机创建会话导致重试失败的问题；先校验任务归属，再复用已有会话，新请求使用稳定会话标识，保留租户与用户隔离。

## 部署与边界

发布证据与备份目录：`/opt/runtime-coding-production-20261015`。API、MCP 均以完整 JAR 部署。生产配置位于 `/opt/chatchat-1.0.0-SNAPSHOT/config/runtime-coding.properties`，由外部 application.yml 导入，重启后保留。

Node 容器 `coding-agent-production` 使用非 root 用户、只读文件系统、受限 tmpfs、无额外 capabilities，并限制 CPU、内存与进程数。共享密钥通过独立文件挂载，未写入源码或本报告。生产与 staging 使用不同密钥。

网络网关只开放明确的 Coding 回调路径；生产来源转发至 API 8080。容器直连 API、MCP、数据库、OpenSearch 和公网均被阻断，DNS 与 IPv6 关闭。未签名回调返回 401，普通业务路径返回 404。系统服务 `runtime-coding-boundary` 持久化网络规则。

| 最终构件 | SHA-256 |
| --- | --- |
| API JAR | `8b7da1738cf4ef11beb0948f8f382b7185227ec117f4c39d8a93dc899a7aaecb` |
| MCP JAR | `c49d240ffda0e726dab82f939e076338ead85f07a3127faca1339c604691b7ed` |
| Node 镜像 | `8d8534ee8a9e26f76b8648c1a20fdef7be78d681f717f90f2ad519e084f071d6` |

## 测试记录

本次相关 Java 测试去重共 196 项通过，覆盖主分析委托、预算、持久化、签名回调、模板发现、幂等与归属隔离；Node 测试 14 项通过。最终 Maven 完整 API 打包成功，`git diff --check` 通过。

构建日志保存在本地 `target/coding-production-workflow-final.log`、`target/coding-production-discovery-context.log`、`target/coding-production-idempotency-final.log`。部署目录保存独立验收 JSON、网络验收日志、构件清单、源码快照与旧版本备份。

## 已知限制

当前启用固定输入快照的 Coding v1。增量供给 v2、活跃 SDK 上下文的跨进程恢复尚未实现，也未作为已上线能力发布。父任务等待有时限，并发默认限制为 2。

执行量已记录模型调用、token 与代码次数，但 CPU、费用等计量仍不完整，`meteringComplete=false`，保留未核销预算，不宣称精确结算。未知执行结果保持待协调状态，不自动重放。历史隔离 D1 验收中的未知 provider 超时不伪造闭环，也未强制改为成功。

旧版 `ModelNativeAnalysisHarnessTest` 仍存在历史协议不匹配，本次不以降低生产协议要求的方式修复，也不宣称全量历史测试通过。
