# FAIL：多工具任务未完成

仓库分布查询成功，但 reasoning 节点未实际产出下游所需字段。Runtime 的 STEP_OUTPUT_CONTRACT_FAILED / EDGE_CONTRACT_FAILED 拦截库存调用，最终仅提供已有数据并报告缺失，不伪造库存。原始失败保留；修复后必须重新执行相同问题。该记录同时证明当前环境的输出契约拒绝路径，不能作为多工具成功证据。
