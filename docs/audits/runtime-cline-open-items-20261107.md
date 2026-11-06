# 未完成问题及后续整改清单

本清单与[完成度矩阵](runtime-cline-completion-review-20261107.md)一起更新。核心场景未通过前不宣布正式验收。

| 编号 | 状态 | 问题 | 本轮动作与通过条件 |
|---|---|---|---|
| B-E2E | PARTIAL | 原证据未证明真实精确未命中后完成全日分析 | B2已出现真实count=0→自主名称解析→同日全文件2943行，须继续Python、报告和独立校验 |
| SEARCH-C | PARTIAL | 旧自主搜索最终预算耗尽 | 新C用既有联网模型，取得官方原文并完成有范围限制的报告 |
| SEARCH-D | UNKNOWN | 真实不可用时最终本地分析缺证据 | 新D保持原关闭联网模型，保留不可用原因并完成本地报告；授权关闭不冒充供应商故障 |
| REPORT-QUALITY | PARTIAL | 单位未知却写元、百分位措辞不准确 | 原件保留，新报告人工核对数值/单位/范围；不能通过验证器改写生成报告 |
| PERF-3 | PARTIAL | 旧版本各两轮，不足三轮且稳定交付失败 | 新最终Worker六个独立任务；同快照、权限、预算证明及UNKNOWN完整记录 |
| PERF-PHASE | UNKNOWN | 非流式回执不能拆纯规划/代码/报告生成 | 只报告可观测阶段和延迟；没有证据不填0，不虚构提速 |
| UI-LIFECYCLE | PARTIAL | 四事实状态外缺当前八阶段展示 | 最小修复fb56f38cc，146测试和构建通过，待空闲发布及浏览器验证 |
| HARNESS-UTF8 | PASS（已修正） | Windows管道中文变问号导致首个B目标失真 | 保留无效批；UTF-8文件传输、SHA和实际objective核对后才接受结果 |
| DATA-08 | FAIL（真实D已定位） | 上传的原生Parquet与外层传输表都使用c0等物理名；执行布局的logicalColumns未限制为外层parts，原文件包的manifest未与原生Parquet按Hash建立显式schema关联 | D任务`analysis-coding-cd7e3bc6af127f95f46d22cc559d53c18bcc7779e61ec5846baecd3f598e6eec`读到正确原生文件并通过Hash，却用外层manifest.columns重命名导致trade_date KeyError；后续原生编辑参数错误耗尽12轮。须补原生文件schema绑定及实际重叠列名离线回归，再测D；不能归因于行情不存在或Search故障 |
| SEARCH-STATUS | PARTIAL（D2实际报告确认） | Search关闭/未授权/未配置时snapshot统一返回空Map，工具被安全移除，但原因没有交给Cline；D2虽已归档本地分析，却只说明公告缺失，未说明Search不可用原因 | 需交付不授予权限的结构化可用性状态，区分配置/授权与实际服务探测；不得为探测把未授权web工具放回会话，也不得由Java执行搜索循环 |

不新增Editor、搜索Agent、上下文压缩器或业务编排引擎。不改权限/共享模型/预算，不删历史失败。测试批处理只提交现有原生入口，不决定业务工具和计算方法。
