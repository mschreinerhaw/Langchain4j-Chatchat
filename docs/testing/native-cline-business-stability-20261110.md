# 客户账户真实闭环验收（2026-11-10）

优先客户账户场景；其余五类在客户闭环成功后推进。未用历史成功、Mock、单测或独立脚本替代原生 Cline 交付。

| 请求 | 代码版本 | 实际状态 | 是否业务成功 |
|---|---|---|---|
| `83a92970-a02a-3706-93c9-321dfaa080b9` | 原部署版本，新增补丁未装入 | FAILED；三次 Python 退出 0，但均为打印探针、缺少 rows.jsonl；最终 OUTPUT_CONTRACT STOP | 否 |
| `a05dba1a-d66e-3663-9551-f575c86c16a1` | `52319cd6d` | FAILED；直接用逻辑字段索引物理 Parquet，程序捕获错误后退出，无可修复堆栈，Runtime STOP | 否 |
| `37a19e48-8fb7-349d-a99e-93170532c8d3` | `3fbdb709e` | FAILED；editor 超限、错误原生路径、不完整源码及预算耗尽 | 否 |
| `ee589648-fa65-3925-8436-42cb6b57babd` | `b40718e0c` | 两个所选 SQL 数据源均为真实空结果，主动停止；Runtime/Native 最终 CANCELLED | 否 |
| `b38052ae-c392-3388-8257-051aefcde4b2` | `b40718e0c` | FAILED；模型 12 次调用未执行 Python，且新 profile 的输出 Schema 交付缺失 | 否 |
| `272c7093-f8b2-3411-876c-e7007eee20b7` | `b40718e0c` | 发现 Schema 接线遗漏后，在 Native 派发前停止；KILLED | 否 |
| `501f1e1b-e558-3858-a739-bd07b0540e86` | `3ac447b54` | Runtime FAILED / Native UNKNOWN；真实 TypeError 后，unchanged repair 派发前拒绝残留 Native operation，worker 已停止 | 否 |
| `3a009cc4-208b-362b-860d-065878f76f21` | `82705e3a5` + 官方 apply_patch 配置 | 验收进行中 | 尚未确认 |

首条请求因部署检查失败后仍被提交，是本轮执行顺序错误；明确保留为旧部署的失败样本，不计入新版本成功。检查失败时未写入运行源码；后续部署脚本只有部署成功才允许提交新请求。

所有失败、取消和主动停止均保留，不从排障分母剔除。各探针代码、编辑配置及部分请求范围不同，不能拼成固定版本成功率。原始请求、任务、回执、模型用量及独立复算仅保存在授权测试服务器 `/opt/cline-customer-closure-20261110/`。

固定版本六类各五次的正式 30 次批次尚未开始，30 项均未执行；不能将以上排障探针补作正式 30 次分母。当前不能宣布 30/30、不能替换默认 Coding Agent Node。

Native 62/62、Java 关联 124/124、旧节点相关 10/10 是测试结果，不是业务成功数。最新 Java 改动完整 20 模块构建：`mvn -Dmaven.test.skip=true -Dfrontend.skip=false -Dfrontend.install.skip=false package`，含前端重新安装及构建；不计为完整回归通过。跳过前端构建的尝试因既有静态资源入口不一致失败，完整重建后通过，没有跳过资源校验。

首次补丁部署只更新 Gateway 的五个业务适配模块，未更新 API 类。对应回滚目录 `/opt/cline-native-business-20261108/backups/customer-closure-20261110T025352Z/`；先检查活动任务，再停止专项 Gateway，恢复五个同名模块并启动。保留 state、历史回执和 Python 数据，不重放旧任务。

后续必要部署包含 API 的 CodingAnalysisService、CodingCustomerAccountVerifier、CodingValidationPlanService 家族，以及 Native delivery 和 runtime-tools。最新回滚点 `/opt/cline-native-business-20261108/backups/customer-native-patch-20261110T035217Z/` 包含 runtime-tools 和专项 Gateway 原配置；此前依次为 `customer-schema-20261110T033821Z`、`customer-metrics-20261110T032019Z`、`customer-reader-20261110T030231Z`、`customer-closure-20261110T025352Z`。全部撤回须逆序恢复，并按 manifest 移除新增的独立账户 verifier 类；不删除任何任务或数据。MCP、官方 Worker 镜像和生产默认节点始终未切换。

请求中的 DeepSeek 模型名被原业务 Agent 的固定 Qwen 绑定覆盖，实际模型以回执为准。未将这些任务当作 DeepSeek 成功/失败对照。未发布验收 Agent 副本创建遭专项 API HTTP 403；没有修改原 Agent、共享模型或权限来绕过限制。
