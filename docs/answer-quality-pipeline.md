# 通用回答质量流水线

## 执行顺序

最终答案采用以下单向质量链路：

1. `AnswerContractCompiler` 从用户请求、系统指令和运行时元数据编译 `answer_contract_v1`。
2. `EvidenceSufficiencyGate` 根据证据协议状态判定 `SUFFICIENT`、`PARTIAL`、`INSUFFICIENT` 或 `NOT_REQUIRED`。
3. 摘要模型严格按照 Contract 生成答案；证据不足时禁止强事实结论。
4. 原有 Reviewer、候选评分与 Java 硬过滤完成候选选择。
5. `AnswerCriticRepairer` 定位遗漏、冲突、引用、格式和不确定性问题，只修订缺陷段落。
6. Java 使用 Claim Ledger 比较修订前后结果。修订后的状态、未知引用数或关键未绑定声明只要发生退化，就拒绝修订稿。

每一步的结果都会写入运行元数据：

- `answerContract`
- `evidenceSufficiencyGate`
- `answerCritic`
- `answerTargetedRepairApplied`
- `answerTargetedRepairRejectedReason`

## 禁止业务硬编码原则

回答质量内核不得：

- 根据行业、租户、Agent 名称、工具名称或数据库类型选择质量规则；
- 在 Java 中维护金融、运维、文档等领域关键词分支；
- 为单一客户写固定结论、固定章节或固定判断阈值；
- 让 LLM 绕过证据门禁或 Java 的修订安全校验。

业务差异只能通过运行时 Contract 和证据属性进入：

- `requiredDeliverables` / `answerDeliverables`
- `answerConstraints` / `responseConstraints`
- `answerOutputFormat` / `outputFormat`
- `answerLanguage` / `responseLanguage`
- `answerEvidenceRequired` / `evidenceRequired`
- `answerEvidencePolicy` / `evidencePolicy`
- `responseSchema` / `outputSchema`

新增领域只应提供上述元数据和标准证据协议，不应修改质量流水线 Java 分支。

## 故障与降级

- Critic 超时、异常或返回不可解析内容：保留已经通过原有决策链的答案。
- 修订稿包含内部协议或不可读内容：拒绝修订。
- 修订稿证据质量退化：拒绝修订并保留拒绝原因。
- 没有模型：仍生成 Contract 与门禁结果，现有确定性答案路径继续工作。
