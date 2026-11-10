# Native Cline 客户账户闭环整改（2026-11-10）

本轮按用户要求优先验证客户账户闭环，未提前扩展其余五类验收。审查基线 `d64ff5566cc37ebd735c3dface0f82ab47281045`；最新部署源码 `406b27342e138a2a5c3e809c5d06aa9709c7a0c3`。下述测试与业务验收分别计数。

| 级别 | 根因及证据 | 本轮处理 / 剩余条件 |
|---|---|---|
| P0 | 历史首次 Python 用 `row.iloc[1]` 读取 Parquet；真实物理顺序为 `p0,c0,p1,c1`，第二列是数据集引用，不是 JSON 记录。解析异常被静默跳过，资产集合为空，第 60 行 `max()` 抛 ValueError | 不替 Cline 编写修复程序。明确已有字段声明和授权样本位置；继续要求模型从真实 schema 自主编程 |
| P0 | 唯一不变行锚点算法不能拆开大规模连续新增、删除和替换，导致整体补丁超限 | `code-lineage.mjs` 将现有工作区变更分成中间源码上唯一的精确编辑；仍限制每段 16000 UTF-16、每次 32 段；保留 parent/hash 和最终逐字节重建检查。不可表达时明确拒绝，不创建成功版本 |
| P0 | 首次本轮请求连续打印探针，但原始 Sandbox stdout/stderr 只归档、不回传模型，最终三次缺少报告触发 OUTPUT_CONTRACT STOP | 工具说明和 Native 输入索引说明真实回传边界，指向 `context.inputVersions`、`context.analysisPreview`；不扩权、不增加模型预算、不回传原始打印数据 |
| P0 | `observeModelResponse` 遇到不带有效许可的新错误仍保留旧截断许可 | 新错误撤销旧许可，保留实际已用修复次数；成功才重置连续序列 |
| P0 | `projectResponse` 把缺失模型 Token 用量写成 0 | 未知计数不输出，只有真实已知计数才转发；不替代 Java Runtime 结算 |
| P0 | 通用 schema 验证只能证明结构，不能证明金融结论 | 新增独立账户验收脚本，从 Runtime 冻结原始文件重新校验 Hash、schema、客户、日期和行数，以 Decimal 复算；完整报告仍须逐项比对，不将输入核验自动升为业务 PASSED |
| P0 | 新账户 verifier 注册后遗漏 `rowSchema` 交付白名单，第四、五条探针没有完整机器输出 Schema | `3ac447b54` 修复白名单；持久化计划测试核对完整 Schema 可读；Native 对已授权分析缺少 Schema 在模型推理前明确拒绝。第七条实测已交付 `verifiedAccountSummary` 完整 Schema |
| P0 | 第七条真实 TypeError 后，未改源码的重执行在 Runtime 派发前被拒绝，但 Native 已创建脚本版本并标记 active operation，后续修复被阻塞 | `82705e3a5` 根据绑定源码 Hash 的真实停止回执，在 prepare/dispatch 前拒绝 unchanged repair；测试确认不创建版本、不增加 active operation，修改后的源码仍可执行。历史 UNKNOWN 不伪造清零 |
| P0 / 配置 | 当前业务 Agent 固定模型覆盖请求级 modelName；`editor` 的 6000 字符上限反复阻挡完整程序写入 | 请求 DeepSeek 的探针实际仍为 Qwen，未计作模型对照。验收 Agent 副本创建遭 API 403，未创建或绕过。专项 Gateway 改用同一官方 Core 已支持的 apply_patch 编辑配置；未修改 Core 或提高限制 |
| P1 | 一条历史 FAILED 记录的 workerStopped=false，但 Docker 容器已不存在 | 部署前留存实际 Docker 不存在证据及空 activeOperations；未手工修改历史状态，没有重派该任务 |

独立账户数值验证通过现有验证计划显式启用：`responseContract.customerAccountVerification={profile:customer-account-v1,customerNumber:<字符串>}`。Java 重新读取冻结快照，用 BigDecimal 重算资产、持仓和交易数量/金额，区分交割 CJJE 与委托 CJSL*CJJG。它只证明声明范围内的来源指标；费用、收益率、长期偏好和跨口径对账不被自动认证。原请求不启用该 profile 时保持原验证路径。

单元/协议回归：Native 62 项、Java 关联 126 项、旧 Coding Agent 源码修复及 completion context 10 项通过；20 模块完整打包成功。未修改官方 Core、Java Agent Loop、共享模型配置、数据授权、预算和默认节点。完整回归未重新执行，历史失败债务仍在。

客户业务闭环尚待真实成功报告；真实 Python 异常后完整修复交付、最终逐项业务验算、历史 UNKNOWN/未完整计量及正式 30 次验收仍有未完成项。这里列的是本轮已复核范围，不将其伪装成全分支 P0/P1 全量盘点或生产可用结论。

部署与业务进度见 [业务记录](../testing/native-cline-business-stability-20261110.md)、[数据核验](../testing/native-cline-runtime-data-delivery-20261110.md)及[修复/预算记录](../testing/native-cline-code-repair-budget-20261110.md)。以上为整改过程证据，尚不是生产可用结论。

## 最终评审结论

本轮复核范围仍有 **4 组 P0 验收阻塞、2 组 P1 债务**，按验收主题归组，不是全分支缺陷普查。P0：完整客户报告及真实异常修复闭环未通过；模型工具使用与模板选择稳定性不足；预算结算和历史 UNKNOWN 未闭合；其余类别、固定 30 次及全量回归未完成。P1：历史 workerStopped 与容器状态不一致；隔离验收 Agent 创建权限不足。源码修复不等于这些主题已关闭。

| 任务书最终问题 | 本轮答案 |
|---|---|
| P0/P1 数量 | 上述范围内 4 组 / 2 组；未完成全分支普查 |
| 已修复根因 | 见上方矩阵及最终输入状态/hash 修复说明 |
| Cline 实测改善 | 完整 Schema、官方 apply_patch、真实执行已取得证据；没有异常修复后成功交付的证据，不能证明端到端改善 |
| Runtime 数据可复核 | 58、48、59 行输入字节/hash/schema/身份/日期可复核；第八条只有选择失败技术状态 |
| 账户分析完整交付 | 否，8 次探针 0 次业务成功，无可验算的最终报告 |
| 真实行情分析 | 本轮未执行 |
| 多文件及图文报告 | 本轮未交付 |
| 正式固定版本 30 次 | 成功 0，失败 0，未执行 30；不混入跨版本排障探针 |
| 全量回归及新增回归 | 关联 Java 126、Native 62、旧节点 10 通过；20 模块打包成功。全量回归未重跑，既有 82 项失败债务未清偿，不能证明不存在其他新增回归 |
| 替换旧节点条件 | 不满足；未切默认、未合并 main、未下线旧节点 |

最终部署 `406b27342`：显式账户 profile 仅有 DATA_SUPPLY_STATUS 或零业务行时返回 INPUT_REQUIRED，不派发 Coding/Python；保留供给失败，不推导零资产。提示区分 manifest 字节 SHA 与 logicalContentSha256。关联测试、构建、部署及健康/登录检查通过；部署后未再运行真实模型，实测有效性待验证。

具体评审项：提供可创建隔离、非默认、同数据范围验收 Agent 的账号/权限。admin 创建接口返回 403，未绕过；当前 Agent 固定 Qwen 覆盖请求模型。任务书第九节要求“遇到共享配置变更……数据权限扩大……必须停止并提交评审”，因此没有修改共享模型/授权。本轮停在评审点。
