# 金融图形场景、配色与 Native Skill 验收

已部署到当前服务器 18100。源码提交：`b7c6e935c`、`c0373972d`、`a4a5bd016`。本报告覆盖展示场景传递、金融配色 Skill、真实 Native 编程及图表交付，不代表原专项全部业务或正式 1.0.0 发布验收。

## 使用方式与边界

聊天开启“图文报告”后，在“报告排版”选择“图形场景与配色”；数据探索输入框提供“图形场景”。设置作用于新任务，原始 PNG 不会被前端重新着色。

| 配置 | 行为 |
| --- | --- |
| `financial-auto`（默认） | 金融分析场景，依据真实市场元数据选择惯例；未知或混合市场不猜测方向颜色 |
| `financial-cn` | 显式选择红涨绿跌，零变动为灰色 |
| `financial-international` | 显式选择绿涨红跌，零变动为灰色 |
| `auto` | 通用分析场景 |

金额、存量、资产构成和类别比较使用稳定中性色系，不能因为值为正就标成上涨。缺失值不能当作零变动；缺少单位不能推断币种或尺度。不同量纲分开展示，保留真实来源、日期和必要图例。

这是按市场惯例制定的项目展示规范，不声称存在全球统一的金融 RGB 标准。港股与内地差异可参见 [HKEX FAQ](https://www.hkex.com.hk/Global/Exchange/FAQ/Getting-Started/Overview-of-the-listed-market?sc_lang=en) 和 [上交所风险揭示书第 30 条](https://www.sse.com.cn/lawandrules/sselawsrules2025/global/hkexsc/c/c_20261106_10836951.shtml)。具体调色板和方法位于 [金融可视化 Skill](../../coding-agent-node/skills/financial-visualization/SKILL.md)，不是 Java 业务规则。

## 实现

* 前端发送通用 `reportPresentation.scenario` 与 `styleProfile`。数据探索将偏好保存到节点，转入既有 Agent task；附件分析入口也能传递。后续恢复沿用任务快照。
* Java 只校验安全标识、冻结和传递偏好；没有金融字段判断、配色计算、源码生成或图表重绘。通用标识允许后续系统提供自己的场景与 Skill。
* Native 提示明确所选场景及规范是交付要求：应解析授权 Skill 中的定义，不猜测正负配色；规范不可用时使用中性色并说明缺口。
* 新增 `builtin:financial-visualization` 1.0.0：市场约定、语义配色、单位、中文字体、可读性及无 I/O 的 Python 辅助函数。保留原有 Skills 和原生工具。
* 真实测试发现“文件已分发”不等于“模型知道可用能力”。Native Host 现在提供名称、描述、版本和任务内路径索引，完整正文仍按需由原生 Read/Skills 读取。索引不是执行成功证明，不扩权、不覆盖原始 frontmatter，也不预设分析流程。
* 官方 Cline Agent Loop、工具算法、模型配置、模型调用次数策略和 Silo 权限规则没有修改。没有另建搜索、Skills 或分析 Agent。

## 真实验证及失败保留

所有输入均明确标记 SYNTHETIC，不能作为真实证券或客户业务结论。分别提供 SSE、HKEX 市场标记以及 `change=[2,-1,0]`、`amount=[120,80,100]`，图表和实际分析程序由 Cline 生成，未给模型人工最终脚本。

| 尝试 | 结果 |
| --- | --- |
| 初次金融问题 CN | 在 Native 执行前遭遇既有 `DAY_SNAPSHOT_ROW_LIMIT` 供数失败 |
| 初次 HK | 为限定附件测试范围主动取消；保留取消记录 |
| r1 CN | 交付可用，但图中绿正红负违背所选 CN 约定，颜色验收失败 |
| r1 HK | `Task metadata binding required`，交付失败 |
| r2 CN | 方向语义正确，但没有确认读取规范，也未采用所选项目调色板，未通过最终样式验收 |
| 最终 CN | 原生读取金融 Skill 与引用，实际红正/绿负/灰零，中性色存量，交付及显示通过 |
| 最终 HK | 默认自动配置依据 HKEX 选择绿正/红负/灰零，交付及显示通过 |

共七次业务尝试：最终候选两项均通过；之前四项失败、一次主动取消均保留，未混作成功样本。首轮“仅传不透明 ID”与“只看接口成功”的不足已修复。最终使用不可变图表字节的像素检查和人工看图核对方向、调色板及中性比较；检查器仅适用于上述明确夹具，不是生产通用金融校验器。

最终两项均验证：真实模型请求、原生 Read/Command、Python 执行、Silo 上传、授权下载及 SHA-256、正式页面展示、刷新恢复；Native Read 回执确认读取了金融 Skill 的完整引用定义。HK 案例还从 Java 授权接口下载并核对了 Cline 实际生成的 `analyze.py`。不以特定源码文件名作为成功条件。清理测试容器后，两项 Silo 图表仍能通过 Java 授权接口读取并核对哈希。

两项各记录 17 个不同 Native provider request ID；原始用量回执单独保存，没有将模型文本长度估算为 Token，也没有把缓存 Token 重复汇总。此计数仅指 Native provider 请求，不能与 Java 前置模型调用混称全链路总数。

回归：Java 初轮 API 64 项、最终 API 26 项及相关 common 47 项通过；前端 133 项、原有 SDK Skills 18 项、Native 分发与安全 14 项通过。镜像内 Python **3.11.18** 验证辅助函数的 CN/HK、零、缺失、非有限值和未知市场分支。早期两个 Node 断言因旧目录数量和旧 Sandbox 文案失效，已更新并保留初始失败日志。

证据：

* [全部尝试矩阵](evidence/financial-visualization-20261112/attempt-matrix.json)
* [CN 图表检查](evidence/financial-visualization-20261112/attempts/final-cn/color-verification.json)、[实际图](evidence/financial-visualization-20261112/attempts/final-cn/chart-2.png)、[页面截图](evidence/financial-visualization-20261112/attempts/final-cn/exploration.png)
* [HK 图表检查](evidence/financial-visualization-20261112/attempts/final-hk/color-verification.json)、[实际图](evidence/financial-visualization-20261112/attempts/final-hk/chart-1.png)、[页面截图](evidence/financial-visualization-20261112/attempts/final-hk/exploration.png)
* [CN Skill 读取](evidence/financial-visualization-20261112/cn-native-reading.json)、[HK Skill 读取](evidence/financial-visualization-20261112/hk-native-reading.json)
* [回归摘要](evidence/financial-visualization-20261112/regression-summary.txt)、[清理后持久化](evidence/financial-visualization-20261112/persistence-after-cleanup.json)

## 部署、回滚与清理

| 组件 | 镜像 | 本地 image ID |
| --- | --- | --- |
| API | `runtime-os-api:financial-visualization-20261112-r2` | `sha256:0646027a8e359c071cc642160bbe6e5c0a16cbab9ac5aec46ae6cbd708bd03fb` |
| Node / Executor / 私有 Worker | `runtime-os-coding-node:financial-visualization-20261112-r3` | `sha256:f77ba87c83cfbc0ecdb4397bdc7f17cb3f3b58579d06a052ad4ff8c9f53fdc14` |

Image ID 不冒充镜像仓库 RepoDigest。镜像从提交源码构建，记录源码归档 SHA-256，未手改运行容器。Cline v4.1.26 固定源码 `a459f1d04dea030833b558be645011ab739c463b`、原运行文件完整性检查和 Python 依赖保留。

服务器记录：`/opt/runtime-os/releases/financial-visualization-20261112-r1/`、`r2/`、`r3/`（后两者指相同前缀目录）。保留原稳定 API `sha256:ce6e5a8856c49442d9d1cd7052f66bcf589685e3221cd48a845c5d7a1fa6b9b5`、Node `sha256:8d9238cd519d478fae77b448d514584a2ceec10d073ebf6dc47980c7fab502ce`。完整回滚脚本位于 r1 目录 `rollback.py`，只恢复镜像及 Worker image 配置，保留后续其他设置。

回滚前排空任务、归档新任务证据并检查后续配置；刷新加载旧前端。旧 API 不保证恢复含新展示字段的任务快照，不能把降级当作新任务恢复验证。中间候选镜像 ID 仅保留在历史构建/部署记录中，不作为首选回滚指针。

已归档并移除本次六个已停止验证容器，以及不再使用的 r1 API/Node 中间镜像；保留最终与原稳定回滚镜像，没有全局 prune、卷删除或 Silo 对象清理。18100 页面及 18353 监控返回 200，匿名监控任务查询返回 401。管理服务未额外公开端口，Node 通过运行进程和真实端到端任务核对。

## 剩余问题与范围

* 既有行情供数 `DAY_SNAPSHOT_ROW_LIMIT` 和一次交付 `Task metadata binding required` 在本次留有失败证据，未在配色改造中放宽授权、伪造输入或改成成功。完整行情业务仍需单独排查。
* 最终为两个明确夹具各一次通过，不是所有金融市场、复杂图形、五轮重复或全系统正式发布验收。
* 收尾脚本最初错误探测了未公开的管理地址，已改用既有用户入口及真实执行验证。首次清理前磁盘快照未落盘，不能可靠宣称回收字节数；仅记录 [清理后检查](evidence/financial-visualization-20261112/final-check.json) 的容器、镜像和可用空间事实。
