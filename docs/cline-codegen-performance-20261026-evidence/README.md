# 验证证据索引

- [真实模型对照](real-comparison.json)：用户授权的 deepseek-v4-pro；同任务、同上限、每模式一个最终样本。包含每次模型调用与工具跨度，不含密钥、Prompt 或生成源码正文。
- [生成产物行为检查](sandbox-verification.json)：四组候选程序在独立无网络容器中校验；这是生成后的单独验证，不是给工作区任务授予执行权限。
- [Docker 全链路门禁](docker-gate.json)：14/14 通过，模型及外部数据源为夹具；Sandbox、签名回调、Java 预算/状态/Evidence 均为真实组件。保留首次失败的原因和最终日志 Hash。
- [Java 回归](java-regression.json)：48 类，执行 409 项通过、跳过 9 项，无失败；可选门禁另列，避免重复计数。
- [Node 回归](node-regression.json)：基线 78 项；最终 90 项通过，包含 SDK 编辑、恢复、取消和预载超时。
- [SDK 对照夹具](sdk-fixture-comparison.json)：实际安装 SDK，确定性模型/验证器；不能作为真实供应商延迟。
- [文件级变更与校验](changes.json)：相对初始提交的文件清单及 Git 规范化内容 SHA-256；清单自身不参与自校验。

原始最终测试报告及门禁日志保存于受控测试目录 `/tmp/runtime-os-codegen-perf-20261026/`，不属于生产部署入口。模型配置临时副本已销毁。历史 67.767 秒样本与本次工作负载/模型不同，没有混入同任务比较。
