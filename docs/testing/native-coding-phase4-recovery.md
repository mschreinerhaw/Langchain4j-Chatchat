# Phase 4 P0 恢复与安全验证

本报告区分本轮真实恢复、组件验证及尚未执行的 P1 故障矩阵，不能由一项 Worker 成功推定所有服务恢复通过。

## 验证通过后的真实 Worker 崩溃

生产代码 `e107dffff`，验收控制器 `808ce8266`。使用未修改的固定 C 样本、`qwen3.8-flash`、8192 输出、原 8 模型/262144 Token/16 工具/4 CODE/300 秒合同及原 Sandbox 镜像。隔离观察入口在当前版本真实声明验证 PASSED 后、下一次 Java 模型准入前暂停传输；控制器杀死该 Worker，确认 ExitCode=137、Running=false，再启动同镜像无故障标志的 Worker，通过原 API 恢复原任务。没有伪造模型响应或工具结果。

执行：`python3 phase4-verified-restore.py`。控制器只用于隔离验收，不被产品服务器导入；其原始 SHA 为 `9410bff5cc807df182e85d664b0b1e2c1d926a8529bc1a1878b9bccb44f74109`。实验入口 SHA `4d7ab02f42e7e6caafe845ce92bac5eed913a88c10df799d13019ff33a124cf5`，Worker 镜像 `sha256:1b8e2925cb99d75f50b9494f6ee029b7fdfb2ccec50aa2b881f44eba6bd89d86`；该镜像只增加实验入口，产品 dist 与 P0 构建一致。

| 核对项 | 实际结果 |
| --- | --- |
| 原 taskId | `native-6833b25f89de6d528de6efe56815b12266790184bf02c960`，未新建任务冒充恢复 |
| SDK session | 前后均为 `8fa5c988-168c-4ce3-ab2e-426cb426725e` |
| 当前 manifest | `cdcbaed8e17eefc96b42fd27aafd5001823bffea14f087ec4baab989b2005530` |
| 声明验证操作 | `cline-0956b9d0ccf218906800aa1b12b5aa8b369657394c9cc0980c4421be043c0703`，恢复前后相同 |
| 停止证明 | `mcp-python-receipt:2a670891b807135032e28f5b7debf8bdec7ae3d2a14548647f58218a870d2631` |
| 首次恢复请求 | 同为 `model-5`，实际看到当前 PASSED；预算余额均为 4 次模型、2 次 CODE；时间继续减少 |
| 合同和预算 | Hash 相同，原 deadline 不变；模型累计 4→5，CODE 2→2，没有重复执行原测试 |
| 最终交付 | COMPLETED；独立测试 exit=0、只读测试/180 个无关函数不变；归档重读相同 |
| 所有已启动操作 | MODEL、TOOL、CODE 均有独立 stopProofRef，无 reconciliationRequired |

完整前后事实、原始操作证明、源码/资产 Hash 及历史版本重读见 [恢复结果](evidence/native-coding-phase4/recovery/result-public.json)、[模型请求事实](evidence/native-coding-phase4/recovery/model-facts-public.json)、[执行投影](evidence/native-coding-phase4/recovery/receipts-projection-public.json) 和 [历史回放](evidence/native-coding-phase4/recovery/history-probe-public.json)。原任务恢复后的独立复核没有改变其 usage 或终态。

## 组件覆盖及范围限制

本轮完整 Node 318 通过、0 失败、1 跳过，包含：压缩后保留验证、无关检查不覆盖声明、编辑后旧验证失效、后续声明失败/缺输出使旧通过失效、无 stop proof 拒绝、SDK restore 后预算/验证一致、lost-ACK 提交后先重载再进入下一轮模型请求，以及既有路径/链接/任务隔离和授权边界。组件模型和 Host 可控，不能当真实模型性能测试。

真实恢复样本 compactionCount 前后为 0，不能声称这一次同时证明真实“压缩后崩溃恢复”。本轮其他真实任务和组件分别覆盖压缩；组合故障仍是 P1 未完成项。Phase 3 的执行中断恢复证据保留，但不改写为本轮新执行。

API 重启、MCP 重启、多服务同时重启、真实 Skills/MCP 中途撤权及跨租户整链路尚未在本轮执行。产品 API/MCP 代码和隔离策略未修改，不代表这些专项已验收。本轮没有开放宿主 Shell、Docker Socket、Sandbox 网络或其他任务工作区。

## 本轮真实安全与最终停止普查

使用原 `generic-isolation-fixed.json`，真实模型任务 `native-cbc5c8c0bdc03fbbfc9794f9ff03cecba57c8b537c5a20cd` 完整交付；原六项只读测试和独立复核均 exit=0：非 root、无 Docker Socket、项目只读、系统目录只读、未授权网络连接拒绝、宿主 canary 不可读取。CODE 2 次、模型 6 次，原预算不变；历史版本 Hash 及归档重读均核对。此安全样本不计入八项代码修复的性能分母。见 [真实安全结果](evidence/native-coding-phase4/final/security/result-public.json)。

执行 `python3 phase4-stop-census.py` 后，隔离数据库的 **70 个已准入任务全部终态且独立停止**，无未核对启动操作；Worker activeSessions=0，固定 Sandbox 镜像运行容器数为 0。API/MCP 镜像保持原值，原三个失败任务的状态、usage、executionControl 与先行审计一致。完整明细见 [最终普查](evidence/native-coding-phase4/final/phase4-final-stop-census-public.json)。

第一次普查把 71 个证据分区均当成已准入任务，因一个无 journal 的分区失败，保留 [首次普查](evidence/native-coding-phase4/final/phase4-first-stop-census-public.json)。核对后，该身份对应 Phase 3 已记录的 SDK 参考 Worker 启动前 HTTP 500：仅输入和合同两个记录、无任务 journal 或模型/工具/CODE 回执。最终普查将这一**已知且逐条核对的启动前拒绝**单列，不称其“已停止任务”，也不忽略任意缺 journal 的分区；未修改任何原任务状态或数据。

回滚仅限隔离 Worker：先停新准入，核实所有 MODEL/TOOL/CODE 独立停止及 activeSessions=0，再恢复保留的旧 Worker 镜像。不得删除历史 Silo 版本或把杀死/删除容器代替原执行停止证明。
