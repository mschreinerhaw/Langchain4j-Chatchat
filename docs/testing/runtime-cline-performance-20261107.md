# Flash / Plus 模型性能对照报告

状态：PARTIAL，当前准备新三轮对照，未声称优化通过。

旧R5各两轮结果及失败保留于[旧验收](runtime-data-exploration-delivery-20261106.md)，不能补一个不同版本任务后混成新三轮。R5仅Flash首轮完整交付，Plus首轮错误判断零记录，其余两轮未交付；Flash首轮有一次Token使用量UNKNOWN，已知值只是下界。失败更快不算优化。

新对照要求：沿用五个冻结原始材料，同实际字节Hash、权限、工具、预算；Flash/Plus各三次，独立taskId/nodeId，按三轮成对执行。`verify-runtime-data-scenarios.py`增加测试用环境变量 `RUNTIME_ACCEPTANCE_REPETITIONS=3`，只改变测试次数，不改变任何任务预算。可比性验证拒绝复制同一任务冒充重复。

上限保持12模型调用、786432Tokens、24工具、12Python、CPU300000ms、512MiB内存、4MiB输出、USD10。使用最新Worker，其他业务测试结束后再执行，避免混入负载干扰。共享模型配置不修改。

记录规划/发现/文件读取/代码生成/Python/报告/总时间、首次有效代码、模型次数、重复读取/无进展次数、已知Token与UNKNOWN次数、完整报告成功率、业务质量。已有非流式API无法拆开的纯规划/生成阶段保留null/UNKNOWN；不得用代码提议回调（包含思考与IO）冒充纯代码生成时间。

请求级参数先审查能力：当前callback中的maxTokens用于Runtime预算预留，不等于供应商输出上限；不能仅改该字段声称限制了模型思考或Token。只有供应商/现有SDK实际支持并可由回执核对时才对照参数，不创建新的模型实现。截止时间和剩余预算通过既有budgetGuidance与Runtime准入执行。
