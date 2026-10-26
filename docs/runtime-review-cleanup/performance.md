# Runtime OS Reviewer 清理：部署与对比验收

记录日期：2026-10-26，Asia/Shanghai。完成影响审计、分阶段重构和指定环境部署；Coding 主路径独立模型工具结果审查为 0。真实样本未证明整体提速，知识问答发现事实错误，因此本次不能标记为全面性能和正确率验收通过。

## 版本与部署

已同步 `origin/main`（86d496089）和最新性能分支（2a2ddf283，包含 MCP 权限分支 1dcf93600），独立分支为 `refactor/runtime-os-review-cleanup-20261026`。同步提交 378a43eeb 之前的本地修复和上游变更也包含在分支内；审阅本次专项改造可使用 `git diff 378a43eeb..HEAD`。分阶段提交见 [影响审计](audit.md)，职责与恢复链路见 [调用链图](call-chains.md)。

部署在用户指定的 `192.168.195.221:5221`，沿用 `/opt/runtime-os-docker-stage` 的 Java Runtime OS、Node/Cline、Python Sandbox 和现有配置。未重建架构。部署源码 cbeb4c6e8，Worker 源码 e4e851aac；API JAR SHA-256 为 `c47ccf8219347adc5999c13027bd4a83085d2bd17abde7698cc5efe3051baf37`。发布包由 Maven 完整构建；后续传输使用精确 ZIP 数据复用，远端重建后的整包 Hash 与本地构建一致。

已保存 API/MCP 数据库备份、Compose/config 备份和原镜像。暂停入口并确认空闲后，仅更新 API、Worker 及其代理；刷新 Worker 网络限制，并验证签名回调。最终应用、Monitor、MCP 管理入口均 HTTP 200。6 张授权表的行数和内容指纹未变；MCP、news、silo、PostgreSQL、OpenSearch、ZooKeeper、Redis 共 7 个非目标运行容器的 ID、镜像和启动时间均未变。部署与健康证据：[deployment.json](evidence/deployment.json)、[health.json](evidence/health.json)。

首次发布遇到历史 Compose 同项目/服务标签重复和容器名冲突，已回滚恢复服务后整理本次目标服务并重新发布。部分历史停止的回滚容器对象被 Compose 删除；对应旧镜像、卷和发布前 inspect 仍保留，不声称这些停止对象未变。没有回滚业务数据库。

## 测量方法

使用仓库 [benchmark-runtime-analysis.py](../../scripts/benchmark-runtime-analysis.py) 调用真实认证入口，冻结请求和原始文件，采集 Runtime trace、Cline provider receipts、Python 不可变执行回执。每个版本每种请求 1 次，串行运行；模型、重试、超时未为性能结果改动。CSV 为 qwen3.7-plus，最终 Docker 和知识问答为 deepseek-v4-pro，均继承已授权父任务配置。

`endToEndMs` 为服务端整体耗时；Coding tokens 与 Java tokens 分列；Python roundtrip 包含启动、轮询和收集，不等于 CPU 时间。缓存与中间重试会影响结果，不推算 P95，不以失败请求作为成功请求的提速分母。失败父任务缺少持久化结果时，Java 调用和审查指标记为未知，不把 trace 中缺失的字段填成 0。

私有原始证据位于目标机 `/opt/runtime-os-archives/review-cleanup-20261026`（受限目录）。仓库仅提交脱敏指标、测试汇总和 Hash，不包含密码、授权凭据、完整业务输出或模型请求。全部 11 次尝试见 [real-model-comparison.json](evidence/real-model-comparison.json)。

## 真实模型结果

| 样本 / 版本 | 结果 | 整体耗时 ms | Cline / Java 模型调用 | Coding 输入 / 输出 Token | Python 执行 |
|---|---|---:|---:|---:|---:|
| CSV，发布前实际镜像 | 成功 | 73,480 | 2 / 0 | 34,534 / 2,282 | 1 |
| CSV，50a1ce606 | 成功 | 95,119 | 2 / 0 | 25,013 / 3,805 | 1 |
| CSV，最终 cbeb4c6e8 | 成功 | 105,843 | 3 / 0 | 43,959 / 3,373 | 2 |
| Docker 原问题，发布前 | 规划失败 | 309,398 | 0 / 3 | 0 / 0 | 0 |
| Docker 原问题，最终 cbeb4c6e8 | 成功 | 768,054 | 3 / 3 | 126,544 / 37,974 | 2 |
| 知识问答，50a1ce606 | 执行成功 | 116,497 | 3 / 0 | 47,951 / 6,950 | 2 |
| 知识问答，最终 cbeb4c6e8 | 执行成功 | 121,653 | 2 / 0 | 27,369 / 8,386 | 1 |

成功样本的独立 `tool_result_review` 均为 0。发布前 CSV 本来就未触发独立审查，因此不能声称这个真实样本省掉一次模型调用。相同供数 fixture 的 [路由对照](evidence/reviewer-routing-comparison.json) 才直接验证旧兼容分支 1 次、Coding 分支 0 次；其中 1,362 为输入 Token 估算，耗时来自 stub provider，只说明路由差异，不代表真实模型提速。

最终 CSV 首次模型输入由 14,976 降到 9,497 Token（-36.6%），与按需 Skills 披露及同步后的上下文优化一致；但多了一轮结果修复，累计输入增加 27.3%，整体耗时增加 44.0%。50a 中间版本累计输入下降 27.6%，耗时仍增加 29.4%。这些变化不能仅归因于 Reviewer 改造，也不能用中间较好样本替换最终结果。

最终 Docker 的三个 Java 模型调用为初始需求规划一次和检索匹配两次，总输入 5,594、输出 17,039 Token；耗时 250,809 ms。Cline provider 累计耗时 449,640 ms，负责代码、数据解释和报告反馈。模板批次 8/8 成功，18,028 ms。该路径已经恢复可交付结果，但相对于失败基线没有有效的加速比例。

最终三个任务均收到执行反馈；CSV/Docker 都产生两次不同代码提交与两次成功 Python 执行，反馈后的修复链仍存在。全部 5 个 Python 容器均有停止/删除回执，cleanup pending 为 0。冻结 Skills 仅含能力卡片，记录 `SKILLS_AVAILABLE`；这三次未主动调用 `skill_read`，没有 `SKILLS_LOADED`，也没有把未加载技能记为已应用。按需加载、撤权和版本漂移由专项回归覆盖。

中间失败完整保留：

| 样本 | 耗时 ms | 原因与处理 |
|---|---:|---|
| a0 CSV | 39,075 | dispatch 工具白名单遗漏 skill_read；改成 dispatch/model 共用工具定义 |
| a0 Docker | 149,655 | 无关 unavailable 资产导致精确目标整体被拒；只放行完整、无冲突的授权精确目标 |
| a0 早期 QA | 178,710 | 初始规划失败；问题与后续固定 QA 不同，不作同题性能比较 |
| 50a Docker | 225,580 | discovery 缺显式身份过滤，返回截断页；保留拒绝，修正需求匹配 Prompt 后最终成功 |

这些失败的缺失模型计量均保持未知。未降低用户授权、数据范围或结果完整性要求来换取成功率。

## 业务正确率

正确率独立于状态 SUCCEEDED 和结构校验。当前执行结果的 validation 为 INCONCLUSIVE，未被改成自动业务通过。

CSV 原始 56 字节文件经认证下载并校验 SHA-256 `52e0631636f9a04de66706e11c9dd5b1a00494636212278959e20c99ff027e83`，独立 `csv.DictReader + Decimal` 得出 2 行、CNY 153.17。发布前、中间版本和最终版本报告均符合这三项检查。

Docker 从原授权 Parquet 文件独立校验 Hash、重建 stdout，用单独的正则解析和 Decimal 计算，核对最终报告 9 项：镜像标签 19、唯一镜像 18、容器 11、运行中 4、stats 记录 4、网络 5、卷 74、内存 2.49 GiB、标签口径镜像大小 17.96 GB，全部一致。这只证明声明的数量及汇总口径，不是全领域正确率。

知识问答符合“不查询当前实例”的范围要求，公式主体存在，但错误声称在线调整 Buffer Pool 从 MySQL 8.0.13 才支持。[MySQL 5.7 官方手册](https://docs.oracle.com/cd/E17952_01/mysql-5.7-en/innodb-buffer-pool-resize.html) 已说明可动态调整。报告关于预读计入 read_requests 的描述也未获验证。因此完整事实检查不通过；没有通过增加 Java 二次业务推理来掩盖这一问题。

最终 3/3 执行成功；两项数据业务场景通过所定义检查（2/2）；若包含完整知识事实检查，严格通过为 2/3。小样本不能推断线上整体正确率。发布前 Docker 没有有效业务输出，缺少同题 QA 基线，因此不能宣称整体正确率提升或 QA 事实质量未退化。详见 [business-correctness.json](evidence/business-correctness.json)。

## 回归与验收状态

| 项目 | 结果 / 证据 |
|---|---|
| 完整 Maven 包含前端构建 | 通过；后续后端重新打包保留相同已构建静态资源 |
| 本地专项 Java 回归 | 273 项：272 通过、1 跳过；35 个 suite 去重，不是全仓覆盖，[明细](evidence/java-regression.json) |
| Linux Java/Node/H2/真实 Docker 门禁 | 73/73 通过，无跳过，[明细](evidence/linux-gates.json) |
| Linux 最终 Worker Node 回归 | 100/100 通过，network none、串行测试，[明细](evidence/linux-gates.json) |
| 独立 Coding 工具结果模型调用 | 参数化全部 ToolWorkflowRole、失败/歧义及 durable continuation 均为 0；真实成功样本也为 0 |
| 普通 QA / 固定模板审核 | 兼容消费者与配置保留；旧分支模型调用测试、published baseline 与真实固定模板供数通过 |
| 授权/多租户/取消/预算/恢复 | AgentAuthorizationIsolationTest、持久化/恢复、callback、Python cancellation、budget 和 G2/G3 通过 |
| Schema/Hash/数值/Evidence | snapshot、金融 Decimal、schema、EvidenceCollector 和真实 Docker 门禁通过 |
| Skills 按需加载 | 绑定不解析正文；读取权限、租户、版本/内容漂移、资源路径、重放及 durable 快照回归通过 |
| 废弃源与配置 | 删除唯一无消费者分析 Skill 源及测试；包内无该类；仍有消费者的 Reviewer/依赖/配置保留 |
| 实际业务性能/正确率 | 已完成对照，但未证明提速，QA 完整事实检查失败；不标记全面验收通过 |

本地 SQLGlot 用例因 Python 依赖跳过，Linux 对应真实数据供给门禁已执行。多租户集成测试最初因上游新增 bootstrap admin password 要求失败，补测试专用配置后通过，未放宽生产要求。曾出现本地负载下 Node 恢复子进程超时、Linux 测试包缺脚本及挂载路径问题；失败日志保留，独立重跑与最终完整门禁通过，未隐藏这些尝试。

后续性能验收应使用同一版本、同一模型及缓存口径的多次配对样本，并分别统计无修复与有修复任务。知识问答需扩大有依据的事实评估集；在此之前保持 PR 为 Draft，不合并，不把此次架构清理等同于全面性能达标。
