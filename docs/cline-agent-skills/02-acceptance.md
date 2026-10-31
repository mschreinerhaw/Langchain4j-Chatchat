# 默认技能集成验收

分支：feature/cline-agent-default-skills；基线：029fe6ee3。实施日期：2026-10-30 至 2026-10-31。

默认技能、Agent 隔离、Python 工程、金融图报表和页面改动已实现并远程部署。指定客户场景在明确业务期间及方法约束的定向验收中通过：实际 Cline 生成、执行、修订并归档的原始报告经独立数值和图像检查一致，未人工改写数值或图像。此结论不代表原问题在默认模型下无提示一次成功，也不代表独立账务认证。

## 部署

入口 http://192.168.195.221:18100。API 为 runtime-os-api:cline-financial-charts-20261031-r10，JAR SHA-256 为 f7cdc901d639de0b8a4975b0d6e1ecc86fa319fe0c06eec3e8814b4557da7ad0。

Worker 为 runtime-os-coding-agent:agent-skills-financial-composition-20261031，镜像 ID 为 sha256:0d09647231c59ac45fdc88a6268120e028b86fb94137112bc8b6ebb010bdb13a。Cline SDK 固定 0.0.93；分析技能 1.3.0、开发技能 1.0.0。生产 UID1000/GID32101、只读根目录和原权限/资源/网络策略保持，环境差异仅为已核对的构建标识。备份保留，会话内容、消息索引及其他容器不变。

证据：[API](deployment-api-final.json)、[Worker](deployment-worker-final.json)、[镜像验证](final-worker-verification.json)、[最终运行状态](final-runtime-state.json)。

## 验证

- 本地及实际 Node 24 镜像：144 项通过，无跳过。实际镜像断网、只读、UID1000/GID32101，覆盖原生发现、按需读取、白名单、冲突、版本冻结和禁用隔离。
- 分析技能参考代码：31 项隔离 Python 边界测试通过。覆盖精确金额、完整组件标签、歧义流水、候选矩阵、观测字段绑定、未知费用拒绝、真实占比总计及显示舍入尾差。
- Java 技能及传递链历史组 97 项通过、12 项既有 SQLGlot 环境依赖测试跳过；金融报告及产物组 29 项通过；分析、指令、持久化及 Cline 回调/模型组 117 项通过。最新提示改动另有报告产物25项、长输入与分析42项通过，技能目录16项通过。各组存在重叠，不作累加。
- 前端50项、Vite及完整 Maven 打包通过；真实浏览器确认说明段落删除、版本1.3.0、图形可读、无错误，历史恢复不重复执行。
- 真实 Cline 读取 CSV/Parquet 合成样本，缺失检查、分组、关联、统计及静态图/Plotly结果独立复算一致。合成数据与客户数据明确分开。
- 独立 Python 工程任务实际读取开发技能，生成函数、校验、异常、日志、类型与测试。工作区 Agent 未获执行权限；另在现有隔离 Python 镜像验证生成测试，29项通过。
- 两个临时 Agent 并行验证三个动态绑定及默认技能禁用，快照互相隔离，临时 Agent 已清理。

详见 [自动化计数](automated-tests.json)、[技能证据](skill-journal-proof.json)、[金融规范](04-financial-reporting.md)、[真实模型验收](live-acceptance.json)。

## 客户场景与边界

最新任务 467bdfe8-3382-372e-b2d6-4149912203e9，使用平台既有 deepseek-v4-flash、统一2026-07-31和明确方法约束；9次模型、4次Python后父任务 SUCCEEDED、Cline COMPLETED。原模型及有效配置已恢复。完整10源115行、142个字段定义送达，全部客户号和日期参数按本轮元数据核对。先前将 UNCONFIRMED 解释为缺少整份字典的判断已纠正。

七个参考函数 AST 与打包版本一致并实际调用。对任务授权的三个不可变文件核验 SHA 后只读隔离复算，资产、当日盈亏、交易方向笔数与金额、持仓市值/占比、显示尾差及12行S6候选矩阵均与原始归档一致。两图经所有者接口下载、SHA及实际图像检查，通过只读导出浏览器验证。

原报告状态 FAILED 来自保留的成交证券身份字段形态检查失败；技术产物与布局均 PASSED，Java业务语义状态仍 INCONCLUSIVE。源字段形态问题、未知标度、部分币种缺失、流水时间并列及未认证勾稽口径保持原样。算术吻合不确认字段的新含义，不能直接认定客户账务正确或错误。

证据：[原始 Runtime 结果](customer-verified-runtime.json)、[独立数值与图像复核](customer-final-quality-review.json)、[来源复核](customer-july31-source-audit.json)、[原始报告只读导出元数据](customer-final-export.json)。实际客户 HTML、记录及图像仅存于忽略的 target/ 与服务器0600私有附件，不进入Git。

历史失败、定向提示和中间复核附件全部保留并标明范围，见 [复测过程](03-customer-retest.md) 和 [历史验收](02-acceptance-historical.md)。其中“人工复核/整理”指本次 Coding Agent 的独立检视或辅助整理，不是外部人工审计；早期辅助附件未替代最终模型原始归档。
