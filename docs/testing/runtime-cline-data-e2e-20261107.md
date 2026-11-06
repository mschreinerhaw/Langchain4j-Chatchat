# 数据供给与行情探索端到端验证报告

本轮先行[完成度审查](../audits/runtime-cline-completion-review-20261107.md)。测试使用真实金融环境，不合成行情、不删记录、不修改共享模型或权限。当前为执行中记录，不能作为整体通过证明。

## 真实样本与证据要求

华是科技，301218，SZSE，source 38，market_quote_daily，2026-11-02。已有真实日快照2943行，Parquet SHA `32e2b1184c69f460d67a99bbb1f34f4fed3d994116552cb45426f2c8a0ba2bc4`，1494236字节。独立复算：涨幅19.99%、有效样本2943、竞赛排名12、并列3个（12–14）。行情时点与货币未声明则未知；历史存储完整不代表交易所采集完整。

每个场景必须串联：请求原文及Hash→Cline实际objective→工具调用/返回→源证据→Parquet/schema/manifest→Hash与挂载→Python执行→报告归档→API查看。只有模型请求不证明工具执行；count=0不能自行伪造。外部搜索摘要不得当报价。

| 场景 | 输入/扰动 | 当前证据 | 状态 |
|---|---|---|---|
| A 精确证券匹配 | 原生resolve精确301218及真实日文件 | R7原任务的目录匹配、2943行、Python与归档已证明；沿用历史报告原件 | PARTIAL（业务文字质量） |
| B 精确失败后回退 | 原始业务标识SZSE:301218，行情代码字段精确比较，未改生产记录 | B2实际count=0，名称匹配count=1，标准代码与名称各命中1，自主取得2943行；Python已运行，报告待复核 | 执行中 |
| C 本地不足/搜索可用 | 原冻结五文件，补充本地没有的官方上市公告信息；已有联网Omni | 新原生任务待执行，不能用R6搜索成功但预算耗尽冒充最终交付 | UNKNOWN |
| D 本地不足/搜索不可用 | 同五文件，既有联网关闭Plus；不切共享开关 | 授权关闭与服务故障分别报告；本地计算与受限外部说明待验证 | UNKNOWN |

B的最初测试脚本通过Windows标准输入传送中文时转成问号，保留在服务器 `/var/tmp/runtime-cline-acceptance-20261107-B`，不计场景验证或产品失败。修正后由UTF-8文件传输，原需求SHA `07320f206717834fad17509a9d2804ae7e39e97275b86b5ed70890d5e38d8ab1`，服务端objective实际包含同一需求。B2私有证据目录 `/var/tmp/runtime-cline-acceptance-20261107-B2`。

B2已经观察到的真实有序过程：

1. `market_get_history` filters.quote_code=`SZSE:301218`，2026-11-02，返回count=0。
2. 同轮目录精确解析该前缀标识为空；名称“华是科技”返回1个候选，含市场和类型。
3. Cline自主以标准代码和名称核对同日观测，均返回1行，发现source 38。
4. 自主选择 `market_get_day_snapshot`，source 38 / 2026-11-02，count=2943、complete=true，模型只收到文件描述符。
5. 原生Python挂载和输出待最终独立校验。精确查询曾被模型重复一次，不忽略这一无进展行为。

脚本 `prepare-runtime-business-acceptance.py` 只准备测试请求；`verify-runtime-autonomous-resources.py` 提交一次原生任务并只读收集有序工具证据，不在Java/Python编写搜索或分析循环。每个请求有独立intent；未知响应只轮询，不重放。
