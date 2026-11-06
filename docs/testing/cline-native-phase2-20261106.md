# Native Skills 第二阶段真实环境验收

日期：2026-11-06。目标分支 `refactor/cline-native-skills-20261106`。根因与修改范围见 [整改矩阵](../audits/cline-native-phase2-20261106.md)。本报告按实际证据区分执行完成、报告交付与业务质量；凭据、原始模型消息、完整 Journal 和部署配置备份只留在受限目录。

## 发布与回滚边界

主机为用户指定的验证服务器；服务入口 `http://192.168.195.221:18100`。发布目录 `/opt/chatchat-releases/cline-native-phase2-20261106`。

| 工件 | 实际发布内容 |
|---|---|
| API | `runtime-os-api:native-remediation-b497bbe4cf44`；JAR SHA-256 `b497bbe4cf443259248c04546d21d7fe4f6c774b123c47a5404cfe54f66c47bd`；源码 `3d53566bc979bcaab8c5daf9c081c1e68d0a5784` |
| Worker | `runtime-os-coding-agent:native-remediation-metadata-b87fbd459`；镜像 ID `sha256:4a72ae673cb09301e66b404fbc2cf4436550b0aeca3828aa031f6c7b571cb8b2`；源码 `b87fbd459811f81e47c6df255b885a7d720089a9` |
| API 回退点 | `api-r4/backup`；旧 API `runtime-os-app-stage-api-1-before-studio-1793935191`；旧共享网络 proxy 同后缀；旧 JAR `053c041c133fd5b00e9b52f7a546e5339dc42168ef642aa8839a65905c151759` |
| Worker 回退点 | `worker-r4/backup`；旧容器 `runtime-os-app-stage-coding-agent-1-before-sdk-reuse-ea3f3b61a80a`；旧镜像 ID `sha256:dec86c1a70c3938330532b3c07a4d22b0577a124799a8976e849d4c788398566` |

部署复用现有脚本：先核对父任务、Coding 会话和 Python 均静止，再备份数据库/配置；API 与 Worker 备份分目录。无数据库迁移、无共享模型/预算/Agent 变更。无关容器 ID、对话内容、消息索引数量保持一致；挂载、回调与容器安全参数按原配置保留。回调实测签名成功、未签名拒绝。既有无关 preview proxy 的 unhealthy 在部署前已存在。

回滚必须在任务静止并核对上述当前/旧容器 ID 后进行：先退 Worker，再退 API 与共享 network namespace proxy；恢复对应备份的 compose/env/config，重连旧 Worker 原网络端点，刷新 worker 网络限制和 proxy，复核健康、签名回调及旧 JAR hash。未发生迁移，不应导入数据库备份覆盖本轮验收后新增数据。此次保留了原容器和镜像，未执行破坏性清理；发布自动失败回退分支有脚本保障，发布成功后人工整组回滚尚未实测，不记 PASS。

## 回归门禁

| 检查 | 结果 |
|---|---|
| 原始 Java 定向复测 | 93 通过 |
| 最终 Java 定向测试 | 130 通过：Asset 6、Exploration 21、CodingAnalysis 55、Callback 19、Visual 29 |
| Node | 本地 241、Linux 候选 241 通过；SDK 0.0.93 不变 |
| 探索前端 | 最终 133 通过 |
| 报告前端 | 62 通过；与探索测试存在重叠，不相加宣称唯一数量 |
| 真实 Docker Sandbox | 10 通过，包括原生恢复、失败、停止/隔离与反馈回归 |
| 真实浏览器 | 无路由 Mock；鉴权正式入口、图表 iframe 渲染、断网重连、刷新；见下述独立证据 |

开发中失败日志保留：首次 Worker 包缺 `integration/sdk-sandbox.mjs`，隔离门禁拒绝且未切流；一次 JAR 组装因复用可变 ZipInfo 损坏候选，校验失败且未部署，改为复制 ZipInfo 后重建。API 重启后旧应用 token 两次返回 401，仅刷新登录，不重放未知执行。浏览器脚本曾未点击实际节点导致旧选择，另一次顶层认证初始化误注入 sandbox iframe 导致 localStorage 异常；均保留失败记录，修复脚本后重验，不放宽 iframe 安全属性。

## P0 图文交付

qwen3.8-flash 为原始组：正式入口最初因指令长度提前失败，修复后单图仍 BUDGET_EXCEEDED，多图 FAILED。单图真实模型调用约 300669 ms 失败，后续修复耗尽原截止时间；多图在已归档三张图后发生模型参数/补丁失败。保留这些失败，未改全局模型或预算。

用户授权改用服务器已配置的 qwen3.7-plus 并单独统计。独立 P0 compact 正向任务明确声明 region 为地区类别、amount 为可相加的演示单位，不提供任何观测数值；固定输入 simple.csv。它与性能组未声明字段语义的问题不同，不作为性能候选数据替换样本。

| 案例 | 权威状态、交付 | 独立核对 |
|---|---|---|
| plus 单图 | COMPLETED / SUCCESS，1 张 Plotly | East 32、West 14、总和 46；owner 下载文件大小/hash/任务绑定均一致；真实浏览器渲染 |
| plus 多图 | COMPLETED / SUCCESS，比较图与占比图共 2 张 | 分组 32/14、占比约 69.57%/30.43%；逐图归档核对、切换 tab、刷新后原回执不变 |
| flash 失败阶段交付 | FAILED / PARTIAL，保留 3 张原图 | iso_week_trend、weighted_unit_value、region_comparison 均通过 owner 下载核验、真实浏览器渲染；不算完整业务成功 |
| flash 单图预算失败 | BUDGET_EXCEEDED，无可交付图 | 预算失败页面可见，未串显同 workspace 的三张历史图 |

plus workspace：`c7bbd9a6-b29f-4721-abb3-82e7b18653d2`。原 flash workspace：`75e914fd-f905-4f4b-a147-d7d6b7e5eab9`。每图 fileId、文件名、hash、size、taskId、归档引用及合成 trace/table 保存在公开证据 JSON，不仅截图。归档完整性与停止证明由平台核验；报告中的计算检查仍是程序声明，不等同于平台认证了业务语义。

正式页面截图：[多图交付](evidence/cline-native-phase2-20261106/browser-completed/running-multi.png)、[单图交付](evidence/cline-native-phase2-20261106/browser-completed/running-single.png)、[失败任务的部分交付](evidence/cline-native-phase2-20261106/browser-flash/flash-multi.png)、[预算失败隔离](evidence/cline-native-phase2-20261106/browser-flash/flash-single.png)、[非法图表拒绝提示](evidence/cline-native-phase2-20261106/browser-historical/historical-partial.png)。截图已人工查看；数字复核依赖归档 trace/table 与独立输入期望值，不从截图推定计算正确。

## P0 断线与状态恢复

运行中的 plus 两个节点先读取现有 `/status`，真实浏览器离线 1700 ms 后重连并刷新，taskId 不变、eventCursor 不回退、没有 POST execute；完成后离线刷新再次逐图渲染、回执 hash 不变。failed/预算节点切换后保持原归属，未借用其他任务图表。HTTP 主动断开使用 2 秒请求窗口：记录唯一 execute 意图，后续只读 `/status`，不重发未知执行。

主动取消节点由 RUNNING 经过既有 `/stop` 到 CANCELLED，10 秒后再次读取仍 CANCELLED，同 taskId，无新增归档报告；不是浏览器本地状态模拟。

另对 Worker 故障后归档收束节点及取消节点做真实浏览器复核：前者显示已完成并渲染原图，后者显示已停止；两者离线刷新后回执不变，零 execute、零页面异常。不同 workspace 的验收通过独立外部深链接载入；同 workspace 的节点使用实际点击切换。脚本最初只改变 hash 跨 workspace 导致旧组件未重新挂载而找不到节点，随后 about:blank 认证注入又失败，均保留记录并修正验收脚本。这不证明产品支持直接修改 hash 的跨 workspace 热切换，也未测试项目下拉框的快速跨 workspace 切换。

Worker 专项保留三次任务：第一次验收脚本误以 SUCCESS 识别实际 SUCCEEDED，未注入；第二次已注入，但最近 checkpoint 没有继续推进；第三次确保 model-3 已开始且未收到 stop proof 后注入。第三次原模型回执随后落库，Java 最终从已验证的 Python 归档收束为 COMPLETED，原 Python 操作及其 intent 集合未变化，SDK sessionId、原预算与截止时间均不变。但 checkpointOrdinal=4/modelOrdinal=2 未前进，monitor recoveryMode 仍 NEW_SDK，**原生 Checkpoint 续跑 FAIL**，不能仅以最终 COMPLETED 宣称通过。详见审计文件的准确代码路径。

首次故障脚本额外要求 fencing 必须递增，属于过严假设：原 Java owner 的有效租约在 Node 进程丢失时可以保留；源码只在租约过期或结束但仍存在的旧 SDK 上推进 fencing。复核改查 fencing 不回退、同一 SDK 身份和真实 CHECKPOINT_RESTORED_NEW_SDK，原始 FAIL 文件保留；复核仍 FAIL，因为实际未发生 SDK Restore，没有通过修改口径掩盖缺口。

`docker kill` 后显式启动同一个容器不改变 restart policy；此次包含两次人工启动原容器，不证明宿主机或外部编排自动恢复。对未决 Python 的真实故障窗口不能用“Python 已停止后重启”代替：该生产窗口仍 NOT_TESTED，Docker 隔离回归单独记 PASS。故障后的旧 Node monitor 可能仍保留 RUNNING，页面使用 Java 权威 status 恢复；Node 观测记录终态修正未关闭。

## P1 配对真实性能

固定六类合成任务：CSV、Parquet、多文件关联、复杂周统计、真实语法错误最小修复、正式探索图文报告；每类两次、并发 2。两组使用相同 immutable asset IDs/hash、相同 qwen3.7-plus、原预算与问题，不强制 Skill 名称或读取，不修改共享配置。固定模型名称不固定 Provider 权重/路由，实际版本 UNKNOWN；先基线后候选存在时间顺序混杂。统计使用 P50/P90 线性插值（type 7），每组 n=12、每类 n=2，仅描述小样本。

基线 API 为 f417（JAR 053c…）、Worker 为 025（镜像 dec86…）；候选为上表 API/Worker。两组成功执行的分析合同不因阶段报告保留改动而改变；主要受测 Worker 变动是 Skills purpose 元数据去重。失败显示与 UI 修复也包含在候选 API 工件，不能把总体差异全部因果归于元数据。

Schema 独立确定性测量：15 项 8835→8061 bytes，138 项 39637→33069，515 项 135395→110731；目录所有 id/完整 description 保留，disabled:true 保留，初始 Skill 正文加载为 0。该数据不是 Provider Token 或成本收益。

独立质量基准：CSV/Parquet East32、West14、总46；关联 East38、West14、总52，保留 001 等前导零；复杂数据 amount1218、quantity140，ISO 周 amount30/168/315/462/243，首末周实际覆盖4/3天；修复演示值6。2026-10-01 为星期四、10-28 为星期三。数值正确但错误宣称完整周或星期错误，必须记叙述缺陷，不能按模型 COMPLETE 自动通过。

严格任务质量口径：请求数值正确且无已发现的实质边界/日期错误；关联需说明基数和前导零；复杂统计需区分边界周；图文需两张有效归档图和匹配表格；修复用例允许无业务数据的 INPUT_REQUIRED，但必须有真实 SyntaxError 后完成同任务修复并输出演示值6。该口径对两组一致，不以删除慢任务或失败任务改善统计。

### 最终测量

12 对任务的原问题、文件 ID、路由元数据 hash 与配置预算上限逐对一致，配对检查 PASS。逐任务记录、缺测标识、调用与停止证明见 [metrics-public.json](evidence/cline-native-phase2-20261106/perf-plus/metrics-public.json)，每类与总体 P50/P90 见 [performance-comparison-public.json](evidence/cline-native-phase2-20261106/perf-plus/performance-comparison-public.json)。两组时钟均全部取得，Node 派发和真实 MCP 回执时钟严格分列；MCP startedAt 仍不是 OS 进程 spawn 探针。

| 指标（秒，P50 / P90；每组 n=12） | baseline | candidate |
|---|---:|---:|
| 首次代码提案 | 44.898 / 81.967 | 43.321 / 87.228 |
| 首个 CODE_READY（含修复任务复用的旧代码） | 45.211 / 82.217 | 43.506 / 87.574 |
| 首次新代码 | 57.869 / 95.687 | 47.418 / 89.363 |
| Node Python 工具派发 | 45.337 / 82.284 | 43.653 / 87.929 |
| 独立归档 MCP 执行 startedAt | 46.626 / 83.240 | 45.432 / 89.125 |
| 首个可用 Python 结果 | 87.543 / 165.095 | 77.092 / 115.350 |
| 端到端 HTTP | 100.504 / 272.833 | 101.040 / 148.335 |

| 汇总 | baseline | candidate |
|---|---:|---:|
| 技术完成 | 12/12 | 12/12 |
| 模型调用 / 已知用量调用 / UNKNOWN 调用 | 56 / 56 / 0 | 44 / 44 / 0 |
| 已知 input tokens | 890052 | 631064 |
| 已知 output tokens | 85813 | 60330 |
| 已知 input+output tokens | 975865 | 691394 |
| 单任务已知 Token P50 / P90 | 72953 / 145750.3 | 59665 / 80682.8 |
| Python 执行次数 / 未确认停止 | 25 / 0 | 21 / 0 |
| 本组模型超时或响应拒绝 | 0 | 0 |
| SDK 正文加载 / reference 加载 | 0 / 0 | 0 / 0 |
| 独立数值复核 | 12/12 | 12/12 |
| 严格请求质量复核 | 9/12 | 10/12 |
| 性能组完整双图交付 | 1/2 | 2/2 |
| Provider 真实计费 / reasoning tokens | UNKNOWN / UNKNOWN | UNKNOWN / UNKNOWN |

Native discovery 实际发现 15 个 Skill。baseline visual-0 曾自主调用一次 SDK read_files，但目标是 sandbox-data 路径，成功 Skill 正文/reference 加载均为 0；其 contextSkillsBytes 最大 1613 包括该工具调用/反馈的归类，不可冒称成功加载了 1613 字节 Skill 正文。候选没有这次尝试，不能把自主决策差异全部归因于元数据去重。本轮没有观测到真实模型成功使用 Skill 正文的样本，不以配置/发现成功代替正文使用验收。

| 每类 HTTP 秒（P50 / P90；每组每类 n=2） | baseline | candidate |
|---|---:|---:|
| CSV | 77.523 / 90.917 | 65.688 / 71.282 |
| Parquet | 106.899 / 147.257 | 60.132 / 72.138 |
| 多文件关联 | 100.504 / 101.517 | 98.417 / 117.344 |
| 复杂统计 | 155.369 / 174.615 | 136.804 / 146.913 |
| 真实修复 | 240.083 / 368.217 | 109.216 / 132.558 |
| 正式图文 | 185.006 / 263.570 | 152.993 / 177.754 |

独立复核全部 24 份合成报告，期望值直接从原合成输入规则和字节 hash 推导，不采用模型自报 PASSED。baseline complex-0 错称 10-28 周二；complex-1 星期和完整周边界均错误；visual-0 未交付两图，因此严格 9/12。candidate complex-1 把实际观测起止标为周起止，未解释首末不完整 ISO 周；join-1 限制中错称客户表不超过两行，与实际三行及本报告正文矛盾，因此严格 10/12。数值正确不能豁免这些缺陷。详见 [独立复核](evidence/cline-native-phase2-20261106/perf-plus/quality-independent.json) 与 [原始合成报告有界导出](evidence/cline-native-phase2-20261106/perf-plus/quality-review-public.json)。

已知 Token 总量减少约 29.2%，模型调用减少约 21.4%，HTTP P90 降低约 45.6%；总体 HTTP P50 增加约 0.5%，首代码/首次执行 P90 反而上升。改善很大一部分来自 baseline repair-1 的 400.251 秒、10 次模型/6 次 Python，相比候选 138.393 秒、5 次模型/3 次 Python；两组 repair-0 则约 80 秒接近。四份复杂统计首个 Python 都遇到 OUTPUT_CONTRACT 并再执行一次，说明结构化输出修复与后续复核仍是可核验瓶颈。样本小、顺序未随机且没有真实账单，**保留可观察改善，但整体成本/稳定性验收 FAIL（未关闭），不宣称已证明整体效率提升**。

原 flash 失败组完全单列：2 任务，0 技术成功，23 次模型调用中 21 次有已知用量、2 次 UNKNOWN，已知 Token 合计 690312；未把 UNKNOWN 当 0，也未从预算中释放其未计量保留项。其 300 秒失败和最终截止时间耗尽，不并入 plus 分母或性能收益。

## 最终验收矩阵

| 项目 | 状态 | 证据与实际限制 |
|---|---|---|
| 根因、最小修复与分批提交 | PASS | 审计矩阵；P0/P1 单独提交；无新增 Loop/Loader/存储或 SDK 升级 |
| 正式单图、多图归档/下载/hash/展示 | PASS | 独立 plus 正向 1/2 图；逐文件 owner 下载及实际浏览器 |
| 非法 Plotly、PARTIAL、跨任务隔离 | PASS | 安全回归、历史拒绝记录正式读回、失败/预算同 workspace 切换；安全校验未放宽 |
| 运行中刷新、完成/失败/预算后重连 | PASS | 真浏览器与 status，原任务、游标不回退、终态不回 RUNNING、零重发 execute |
| HTTP 响应丢失后的只读恢复 | PASS | 每个故障/取消任务唯一 execute 意图，2 秒断开后查原任务 |
| 主动取消 | PASS | 真实 stop 后 CANCELLED，晚到回执未改回运行或新增报告 |
| Worker 丢失后已验证归档收束、成功 Python 不重跑 | PASS | r3 原成功操作/intent 集合、预算、SDK identity 保持，最终 Java COMPLETED |
| Worker 原生 Checkpoint 续跑 | FAIL | 后到已归档模型回执超过 checkpoint 覆盖范围，未重新建立 SDK 会话 |
| 丢失 Worker 的 Node monitor 终态 | FAIL | 旧观测仍可能 RUNNING；Java 权威状态正确，但不能称全监控一致 |
| 未决 Python 时的生产故障注入 | NOT_TESTED | 仅隔离 Docker 回归和 Python 停止后生产注入，不混算 |
| 网络恢复时主动注入重复事件 | NOT_TESTED | 状态幂等/游标与终态回归通过，生产未主动构造重复事件流；实际前端协议为 GET 轮询 |
| Provider 六类配对与 P50/P90 | PASS | 24 次，12 对逐项配对证明，成本未知与失败/质量缺陷如实保留 |
| 整体成本、性能、质量稳定性关闭 | FAIL | 账单 UNKNOWN、总体中位耗时基本持平、质量仍有缺陷，小样本不能证明整体生产收益 |
| SDK 正文真实模型按需成功加载 | NOT_TESTED | 本轮自然选择成功加载为 0；原生 discovery/隔离测试另计 PASS |
| 新版本部署、安全边界、最终静止检查 | PASS | 最终鉴权直连/网关 200；签名通过/未签名拒绝；父任务/Coding/Python 均 0；约 31.2 GiB 可用 |
| 发布成功后人工整组回滚演练 | NOT_TESTED | 回退镜像、原容器、配置/数据库备份完整；未切回旧版本演练 |

最终结论：本轮完成了最小范围修复、实际部署、正向图文交付和完整配对测量；**第二阶段整体仍未验收通过**。下一步应优先处理已证实的 checkpoint/模型回执边界与监控终态，继续使用现有协议，不能放松校验或重放未知调用。未扩张到新的架构重构。

## 复现入口与证据保管

公开证据与 SHA 清单见 [证据目录](evidence/cline-native-phase2-20261106/README.md)。完整私有 Journal、模型回执、下载原件与失败日志留在服务器发布目录的相应 `*-evidence-private` 子目录及本地忽略的 target；没有提交密码、token、容器 env/config 或原始 Provider 消息。最终 fetch 后 main 与 origin/main 均为 `0693c15fd9184f112d0111f3085b16943841a72d`，工作分支没有落后 origin，未合并 main、未推送本轮新增提交。

在新的受限验收目录准备 fixtures 一次，或复制现有 immutable fixtures.json 后复用；使用实际发布工件运行以下命令，绝不能复用旧目录重发未知执行：

```sh
python3 verify-native-report-delivery-stage.py prepare --root NEW_REPORT_DIR --fixtures perf-plus/fixtures.json --auth AUTH_PRIVATE_JSON --model qwen3.7-plus --scenario compact
python3 verify-native-report-delivery-stage.py run --root NEW_REPORT_DIR --fixtures perf-plus/fixtures.json --auth AUTH_PRIVATE_JSON --model qwen3.7-plus --scenario compact
python3 verify-native-report-delivery-stage.py collect --root NEW_REPORT_DIR --fixtures perf-plus/fixtures.json --auth AUTH_PRIVATE_JSON --model qwen3.7-plus --scenario compact

# baseline/candidate 分别在对应镜像下运行；不能用同一工件冒充两组。
python3 benchmark-coding-progress.py run --root NEW_PAIR_DIR --auth AUTH_PRIVATE_JSON --variant baseline --formal-visual --repeats 2 --model qwen3.7-plus
python3 benchmark-coding-progress.py run --root NEW_PAIR_DIR --auth AUTH_PRIVATE_JSON --variant candidate --formal-visual --repeats 2 --model qwen3.7-plus
python3 collect-coding-progress.py --root NEW_PAIR_DIR
python3 collect-cline-native-skills.py --root NEW_PAIR_DIR --variant baseline
python3 collect-cline-native-skills.py --root NEW_PAIR_DIR --variant candidate
python3 collect-coding-execution-clocks.py --root NEW_PAIR_DIR --auth AUTH_PRIVATE_JSON
python3 summarize-native-benchmark.py --metrics NEW_PAIR_DIR/metrics-public.json --output NEW_PAIR_DIR/performance-comparison-public.json
python3 verify-native-benchmark-pairs.py --root NEW_PAIR_DIR
python3 export-native-benchmark-quality.py --root NEW_PAIR_DIR
```

Worker 故障脚本要求显式 `--inject-worker-kill`，会先检查静止状态，只对新建合成任务注入并启动原容器；`--reconcile` 只读原任务，不重新派发。必须在授权验证主机且无其他活动任务时使用，不在性能计时窗口混入故障。其现有 checkpoint 失败应预期重现，不能把完成归档收束误当 SDK Restore 成功。
