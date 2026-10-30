# 第一阶段实现与真实验收

分支：`feat/ai-data-exploration-workspace`。本阶段增加轻量数据探索页面、业务节点、展示配置及持久化，未新建 Agent、模型通道或 Python 执行引擎。

## 自动化验证

[自动化证据](automated-tests.json)：后端113项不同用例通过，前端7文件56项通过；Vue 结构检查、Vite 构建、Spring Boot 打包通过。后端先运行112项广域 Coding/归档回归，随后运行包含新增 HTTP 路由测试的13项定向回归；重叠用例不重复累计。

- JDBC/H2 实际存储：重建 Store 后数据恢复、跨 tenant/user 拒绝、CAS 冲突、稳定节点请求、未完成不能保存、父节点文件和归档 CONTEXT 继承、配置不能覆盖回执。
- 实际 Spring MVC 路由绑定：GET、节点准备/执行、PUT 展示配置，无需编译器保留方法参数名。
- 既有 CodingAnalysisService、DurableCodingTaskService 持久化、Python 桥和 VisualReportArtifacts 回归。
- Vue 页面 API 编排、异常恢复、bar/line 转换的数值和单位保持、数据目录过滤和分页、App 壳层保留当前工作空间/节点参数。

这些测试中对 AI 边界使用替身的用例，只算自动化回归；下列现场验证使用真实部署、真实 Cline 和既有 Sandbox。

## 数据来源与边界

使用现有用户有权限访问的历史客户账户分析输入：

- 原 Runtime run：`att-1-c592a949-99a0-4fc2-a1a1-b345b5299706`。
- 原归档 Coding task：`analysis-coding-72bccf9b620fa05806f73eb4c63b2ec4383649e7939cc2928b192bb0485f7eff`。
- 原资产文件：`86eecb3c9998a92569e3aa91f95a2f4f6bec14ab9f4cfaf3433445a8725475d4`；SHA-256：`fc6c0a616641f3a0236fefeb88f8667685d29881ce22924298eda1d168b36f26`。

历史任务请求文字出现 `070200046604`，但实际资产负债记录的 `cust_num` 为 **1500007073**。没有将两者强行对应，也没有将请求文字当作数据范围的证明。验收明确使用实际客户号。

从既有归档的584条混合记录中，按原 inputVersions 的资产负债 datasetReference 提取5条原始记录，逐条保留原值及生产者字段定义，通过现有用户空间上传接口保存为独立 JSON 数据文件，再在新工作空间选择它。未创建模拟业务值，未让前端返回静态图表。Parquet 离线解码只是验收数据提取，实际分析与修正均由既有 Cline/Python 控制面执行。

所选数据文件：`15d7bb4f8264f90c3a599fb70e47c34a6f32e5302b930b5148bcb1d62456cb9c`；SHA-256：`e59a46f4b0c6a989ba1b21c9d06a9d585d8ace8ec5735e1cb6ad76b5a88dfa0a`。日期为2026-08-03至2026-08-07，仅5个交易日；这是已有归档快照，不是实时重查或最近12个月数据，也没有独立证明其为真实生产客户明细。

## 真实执行记录

空间 ID：`0537ce20-0435-4a84-b3cb-efc950c84ab7`。

| 业务节点 | 实际 Coding task | 结果 |
|---|---|---|
| 输入“分析资产结构” | `analysis-coding-06afcde2b9241ab94ee2ff66b4064aaa20c5ea8ee37ec7c0ae486ceeb4b0099a` | Python 结果、三张 Plotly 图及表格已归档；保存节点 |
| 从当前节点追问最大占比及数据限制 | `analysis-coding-c4b392d2f3091216cab618deffc7baac32575749e22b4e1055c7c68c53adb670` | 继承原文件和父节点 CONTEXT，返回实际 Python 结果；保存节点 |
| 从第二节点修正未声明单位的表达 | `analysis-coding-f7ed7f6f914d09724003d0cc2e16006b7c8bf56e318eb180b3ea33a1c8da059c` | 原值不变；图表轴、表头与摘要明确“原始单位（未声明）”；保存第三节点 |

首轮实际 operation：`cline-4397b5161d9db16a7de15c6fbc10cebc87ac3af14acdb6bd471fa76c30865c7a`。修正轮 operation：`cline-52c82ffa29842ffd80daa62c0c8da6911b77eef6687b07654ea49c1a962a4934`。结果拥有输入、代码、执行回执、结果数据集、验证、证据束六类引用，并保留 executorStopped=true。现场浏览器读取实际授权代码文件并核对其 SHA-256，不以模型声明代替执行证明。

独立基线在北京时间 **2026-10-30 14:58:38**、工作空间模型结果返回前生成。初轮[27项数值/比例核对](numeric-verification.json)通过，但发现报告使用了生产者未声明的货币单位，明确保留问题记录；未将数值正确冒充单位正确。随后通过正常节点追问修正，[修正轮30项数值核对](corrected-numeric-verification.json)通过，三条图表数列与对应明细表保持原值。

最终报告仍为 **PARTIAL**：源单位未知、窗口只有5个交易日。这个状态表达业务证据限制，不代表 Python 或持久化失败；没有篡改状态以取得全成功。

## 浏览器与重启复查

现场脚本 `chatchat-api/web-app/scripts/test-data-exploration-live.mjs` 不拦截或模拟任何接口。实际动作包括创建空间、选择原文件、执行问题、等待真实 Coding 回执、渲染和缩放 Plotly、柱状转折线、检查转换期间0次 execute 请求、保存业务节点、刷新重读并比较原回执、提交父节点追问。最终复查使用原已完成任务，明确标记 `RESTORE_EXISTING_REAL_EXECUTIONS`，不将恢复阅读冒充新执行。

联调问题与修正记录：

1. 首次 HTTP 请求暴露 Java 参数名未保留导致400：已显式声明 PathVariable，并增加真实 MVC 路由测试；未触发的执行未计成功。
2. 首次浏览器脚本把登录初始化注入所有 iframe，导致 opaque sandbox 不能读取 localStorage：[原失败记录](initial-browser-harness-failure.json)。修正为仅顶层页面注入登录，不放宽图表隔离。
3. 已完成多节点的重启复查发现壳层清掉 URL 选择参数：[原失败记录](route-restoration-failure.json)。仅对数据探索页面的同路由规范化保留参数，加入路由回归；恢复必须回到指定历史节点。
4. 真实截图发现内部图表/传输文件混入数据选择：过滤内部文件，保留业务数据，增加搜索并将所选数据置顶。

最终浏览器状态与检查项见 `live-acceptance.json`；完整原始回执、独立基线及截图保存在本机 `target/data-exploration-live/` 等私密验收目录，未提交客户明细或登录凭据。

## 部署与回滚

部署地址：`http://192.168.195.221:18100/index.html#/w/data-science/dataExploration`。最终镜像为 `runtime-os-api:data-exploration-route-20261030`；JAR SHA-256 为 `d9d05acc23e51856122bfd535f485b3510dd08533f08f1b17ae77cccdeb1b640`。

[部署证据](deployment.json)保留实际镜像、哈希、旧容器和回滚目录。仅更新 API/前端及配套代理容器；Coding Agent、MCP、Python 镜像及其他服务保持不变。部署前检查无活动 Coding/Sandbox，保存数据库/容器/配置；部署后核对原会话内容指纹和消息数量，未改变已有业务授权。
