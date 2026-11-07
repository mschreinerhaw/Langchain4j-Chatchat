# 通用原生 Coding Agent 组件回归阶段记录

状态：**组件验证通过，整体产品验收未通过**。代码 `6e82a93df`，SDK 0.0.93。源码对应关系及限制见 [源码审计](../audits/cline-native-source-baseline-20261107.md)、[根因矩阵](../audits/native-coding-root-cause-matrix-20261107.md)，架构及回滚见 [设计说明](../design/native-coding-agent-20261107.md)。

| 验证 | 条件 | 实际结果 |
| --- | --- | --- |
| Node 全量 r5 | Windows Node 22.14，兼容 strip-types | 294 项：293 通过，1 项 Windows 链接权限相关跳过；后续新增测试由下列 native 集覆盖 |
| Node native 当前代码 r6 | Windows，同一已安装 SDK | 53 项：52 通过，1 跳过 |
| Linux native r2 | 512 MiB，默认多测试进程并发 | 失败，容器 OOMKilled，21 通过、2 测试文件失败；不隐藏此轮 |
| Linux native r3 | 同镜像、同 512 MiB、禁网、只读根、非 root；仅 test-concurrency=1 | 53 项全通过，无跳过，容器已停止，OOMKilled=false |
| Maven r10 | Java 21，17 模块 reactor，真实前端构建后 package | BUILD SUCCESS；common 6/6；MCP 17 项中 15 通过、2 跳过；API 127/127；无失败 |

MCP 的两个跳过测试不能计为已验证能力。Java 单元测试中使用 mock Silo，不代表实际对象存储、Worker 中断及完整执行链已通过。

## 首个真实 API 请求发现的缺陷

隔离部署后，第一个通用项目请求在模型调用前返回 HTTP 400：`Name for argument of type [java.lang.String] not specified`。构建未开启 Java `-parameters`，新增 Controller 未显式声明路径参数名。仅补齐该 Controller 的 `@PathVariable` 名称，不改全局构建参数或旧路径。

`NativeCodingControllerHttpTest` 经真实 Spring MVC 参数绑定复现：修复前 10 项均 error，修复后 10/10 通过、无跳过。测试覆盖提交、状态、文件、历史、Diff、事件、恢复、取消以及无认证身份和非法版本拒绝。日志分别为 `target/native-controller-http-before.log`（SHA256 `d3a28b4890d6eed0173408676e24a24430f643b8019f61fb403632ebf7de0cff`）、`target/native-controller-http-after.log`（SHA256 `a6151ef6243d93c61c7a987f5304b336f77dd43313febdfcd13fda45fdcef928`）。这是真实适配缺陷的最小修复，不计为模型修复成功。

隔离启动另发现部署脚本缺少 host-gateway 映射、MCP 镜像启动入口和 Worker 启动文件归属。这些仅修正临时验收部署；失败容器保留。启动所需的已发布 Runtime semantic policy 经显式表名复制；同服务器现有有效 License 文件用于隔离 MCP，原 License 功能与 MAC 校验保留，不关闭授权。

## 真实模型预检及运行资源缺失

任务 `native-f088508bb6901346da22fd421a8609185aaba35f4f6256e8` 使用原定模型和预算，模型通过原生 read_files 读取两个文件，通过 editor 将减法改为加法并提交 Silo。run_commands 多次失败为 `Invalid Coding callback binding`，实际没有启动 Python。8 次模型调用后终态 FAILED、executorStopped=true、无需 reconciliation；已知 Token 18529，未知部分 reservation 112543，费用也仍有 reservation，不能称为实际总 Token 或零费用。

8 份原始模型回执与 8 份独立工具停止证明均保留。每轮授权工具含 apply_patch/editor/read_files/run_commands/search_codebase；实际披露由该模型官方原生路由选择 editor/read_files/run_commands/search_codebase。apply_patch 本轮未披露是原生编辑模式选择，不能据此推断工具能力丢失。公开脱敏投影为远端 `C-preflight-B-r2/execution-public.json`，SHA256 `947a9f69f07762546249f1756b010892ead3910b6675c20616b3b456acac8e96`，不含提示词、模型自由文本、原始源码或凭据。

对真实 API JAR 的运行依赖逐包检查：helper 不存在；MCP JAR 自身包含 helper。原因是 API 的 MCP server 依赖仅为 test scope，单测能够读取 helper，生产包不能。修复将唯一 helper 移入已有 common 模块运行资源，API/MCP 同源；原生组件启动时即校验，默认关闭的旧功能不受影响。Java 不复制或生成编辑算法。

新增排除 MCP server 测试依赖的子 JVM 测试，以及真实 A2 下的命令准入到 governed dispatch 检查。当前三类回归 19/19 通过、无跳过，日志 `target/native-helper-resource-after-r2.log` SHA256 `5e1e40852b3c3f8d4bc5ec66d2bd536555adeed3d7ca77b918d9d9af8a498dcf`。最初子 JVM 测试遇到 Windows 命令行长度限制，改为 Java argfile 后通过，保留失败日志。尚须使用新运行包完成整链复测，不能以这 19 项替代。

## 第二轮真实执行与原生 Loop 集成缺陷

共享资源修复后的 API JAR SHA256 为 `3fe60c394a9abcaaa0f8baa91cbc811cfb8d58344d1a218ac0d81c054a0aad49`。包内 helper 位于 common 运行依赖，字节 SHA256 `768e21d085d74f98b77615acdab13f3de04a864293f366a21e6a2a2fa6659dcc`，与实际 MCP helper 相同。

相同输入、模型及预算上限的新预检任务 `native-e53c0fe9bce6a23b82bf0420b30ba4054a34cb6213089a4e`：8 次模型调用、6 个工具停止回执、3 次实际执行；模型读到原始失败测试，自主编辑后，真实输出包含三个测试通过。任务仍 FAILED，不能算完整通过。已知 Token 29347，Token reservation 101725、CPU reservation 225000 毫秒保留；实际 CPU 与费用未知。公开投影 SHA256 `062c1ab268188a01530128f92ba11c6d74650e5fb8d7884469b7913c2eff5e98`。

首轮模型同时请求 read_files/run_commands，原生 SDK 先调用全批 beforeTool，再顺序执行。过早登记第二个工具的 start intent，让读工具完成时出现未执行工具的未知状态，阻断首条命令。最小调整为实际 tool.execute 紧邻 I/O 时准入，保持 SDK 原生调度不变。

完成阶段原有 Adapter 只保护最新工具轮，未保护后续 SDK 验证反馈。使用安装版 SDK 压缩器的独立测试实际观察到 guard=-1；反馈丢失后模型连续输出完成文本而未执行声明的验证命令。通用 profile 现在保护工具轮及后续反馈，保持因果顺序，旧 profile 默认行为不变。

两项独立测试修复前均失败（日志 SHA256 `5ec2d4fc7fbb7b3b42a52aa0615292bd3793fa55d29946619703f7522a56d7f7`），修复后均通过（`2de238aba07b95e5fd88f774eada145097c3a3d94116cac3257fc6a32143d50e`）。Node 全量 r7 为 297 项、296 通过、1 项 Windows 链接相关跳过；日志 SHA256 `d9d6664880bef96d66942437b61a5df1aa876aee06312cacfe85c3a27055103a`。没有新增 Agent Loop、模型调用或 Prompt 来替代修复。

旧终态任务在 API 与 Worker 重建后仍可从实际 Silo 原样读取：manifest `5becb13153e4e44f2aad707d7ebbb549381a27e580ac40c24cdf7676a6c862fc`、历史 2 版、Diff SHA256 `9728fc20095cfb3879de9b18f36fd214f36da8c8654d07a4e38a2f6f9b8ec3b7`。旧任务模型次数 8、执行次数 0 均未变化。这只证明终态源码归档读取，不等同于进行中 Worker 恢复，也不是完整报告验收。

## 同一工具内的子执行边界

真实 A2 的独立两分支测试表明：一条 native CODE 已有独立 stop proof，仅剩同一会话的父 run_commands 工具时，旧逻辑仍将父工具标为未知，阻断其下一命令。正常场景修复前失败，存在其他未知模型的拒绝场景通过。修复前日志 `native-child-reconciliation-before-r2.log` SHA256 `51b5ef4366c41ee3213cfbd03d607889bd8210bdacca2309d073b2fa72391891`；早一轮测试夹具遗漏 input 字段，其失败日志也保留，不计为根因复现。

现在只在 native profile、子 CODE 已停止、父工具仍活跃、同 fence、父子均绑定已停止的真实模型操作，且唯一剩余 start intent 是该父工具时，将其视为可以正常继续的工具执行。父工具本身仍是未停止操作，仍阻止终态交付；其他未知模型或执行继续阻断准入。没有伪造父工具的 stop proof，也没有全局绕过 A2 reconciliation。

回归为 89/89、无跳过：新旧工作区、A2 持久化、旧恢复和模型 callback。日志 `native-child-reconciliation-after.log` SHA256 `cc328932157e8dbbd74fca27de49be9f89845db5721a517c272140c867d8562a`。测试使用明确的 unit stop proof 夹具，实际 MCP 父子整链仍需正式回归，不将该结果混作真实模型通过。

## 验证命令身份与成功后继续检查

只部署 Node Loop 修复的第三次真实预检 `native-845d00db6d86df53155ff3dff79640f3bdaa41cd68ffbf4a` 保留原输入、模型及预算上限。3 次实际命令均停止并返回三个测试通过；任务仍 FAILED。模型已收到验证反馈，又执行了无管道的 `cd /workspace/code && python -m unittest discover -s tests -v`，但 Runtime 按原样命令 Hash 拒绝等价目录前缀，造成额外模型/执行消耗。8 次模型调用，已知 Token 24890，未知 Token reservation 106182；不能把三个测试通过改写成整体完成。

验证修复不改变命令或结果。Java 仅由冻结的声明命令生成有限的 4 个 Hash：原样，以及固定 `/workspace/code` 目录的未引号、单引号、双引号前缀。执行器本来就固定该 cwd；原始命令 Hash、标准流和 stop proof 均保留。管道、额外重定向、其他目录及成功掩盖后缀不在等价集合。Java 最终再次核对实际已停止的操作、源码 Hash、当前 manifest 与回执绑定。

已验证执行与最新检查命令分别保存于既有 journal，后续同版本检查不能覆盖验证；任何源码版本变化仍要求再次验证。SDK 完成声明仍必须真实发出，Java 不把失败终态改为成功；独立项目验收仍为 NOT_EVALUATED/INCONCLUSIVE。

独立原生 Loop 测试覆盖目录前缀及后续检查、零退出管道拒绝、修改代码后旧验证失效；Java 覆盖精确等价集合及对抗命令。当前 Java 23/23 通过、无跳过；Node 全量 r8 为 300 项、299 通过、1 项 Windows 链接相关跳过。真实同预算复测待新制品部署完成，不能将这些测试计为整体验收通过。

## Linux 原始执行证据

远端目录 `/var/tmp/runtime-native-components-20261107-r2` 保留 r2 失败与 r3 串行成功两轮日志和 proof。r3 容器 `f4090aea94dae6e7a702bb2e425627b100c2365486badd13e99e5b5d96c1f8c4`，状态 exited，exitCode=0，Pid=0。镜像 `sha256:a3a279fc391bda1bb5aefb62acd8ee56200215ce2905cfb83d189398c33c5828`。日志 SHA256 `2375d1df8d71caf3b7aa87011d2dd32a86b89c51e9cfa74fc21afd4f7e1443c8`。

这些测试没有调用模型，不消耗或增加任务模型预算。全套 Node 测试进程并发的内存总和与单个实际 Worker/任务不是同一指标；r3 只证明这些组件在限制内串行测试可用，不能推导实际长任务内存表现。

## 可部署制品

API JAR 428935203 bytes，SHA256 `6b2c27ec4064e0b3c6ecc577a5167f1f0e1fa5452679ea03f2042e4546ab9f34`。

MCP JAR 254763219 bytes，SHA256 `e014d3475ac0c2c3555c547332e667c5d5ea4f59da7726d035b098fc7c326e8a`。

制品只用于隔离验收，不替换现有服务。部署是否启动、健康及真实任务是否通过，须由后续证据确认。

## 后续门槛与数据最小化

必须继续完成通用 A–G 真实模型任务、相同模型及预算的三组对照、代码/执行结果 Silo 归档再读取、权限负向、真实 Worker 中断恢复、原生 Compaction 与 MCP 治理整链。当前不得发布任务完成率改善结论，不进入金融等业务迁移。

隔离验收使用临时测试数据库命名空间，复制空表结构及模型/管理员/角色/环境配置。首次初始化表筛选误包含审计历史 6687 行，随后仅在独立验收库清空至 0，并将脚本改为明确白名单；原库未变，审计行内容未输出到公开证据。原始初始化和修正 proof 同时保留。该临时测试设施不是新增产品数据库或存储设计。
## Worker 恢复 fence 与工具回放专项

新增真实 A2/H2 测试分别复现：有遗留 native TOOL 时恢复 fence 被拒绝；已完成 TOOL 跨 fence 回放被当作重复 start 拒绝。修复前两个测试均 error，日志 `native-recovery-before.log` SHA256 `5cd7a85e232cfec5473306abaa60e574f30e095b6601a813ea713fc9a64a1134`。

最小修复仅允许 native profile 先取得隔离旧 SDK 的 fence，所有未知 start、预算、deadline 和 reconciliation 阻断均保留。验证原授权、输入身份及不可变停止回执后允许只读回放，不创建新 start。旧 profile 仍要求全部停止后恢复 fence。新测试同时验证旧 fence 拒绝、新模型准入被阻断、未知工具恢复为真实中断反馈、回放不重复计费、替换参数拒绝。相关持久化、模型回执及回调测试合计 **74/74 通过**。此项是组件验证，真实 Worker kill/restart 尚未通过验收。

通过日志 `native-recovery-after.log` SHA256 `01d488cce913a19fb31295250f5bf7142790befb6d4e6ee621791bb63279edc4`。

## SDK 对象命令完成校验

真实通用批次 B/C 的工具输入为 `{commands:[{command:"..."}]}`。安装版 SDK 保留对象形态，执行回执使用 canonical 对象 Hash。只列举字符串 Hash 会错误拒绝已通过的执行。最小修复为四种既有声明字符串各增加仅含 command 字段的对象身份，共八种；不将原始执行 Hash 改写成字符串，不接受额外 args/env 或管道。

Node 新测试实际经过安装版 `createDefaultTools`：修复前 1 fail（Hash 集合上限拒绝），修复后 1 pass；完整 native runner **12/12**，日志 SHA256 `e36724dd287d76c1c32178c30dd2f8d66e2d8b1119a234485db3cd136e0f962f`。Java 新增对象及注入负向测试，修复前 **3 项中 1 fail**（`native-object-verification-before-r2.log` SHA256 `b31c2fcd59cb80f3f61a70b4789ad0e073cb36410d30dd68755c38b50e54f2b7`），修复后连同真实 A2 workspace **15/15**（`native-object-verification-after.log` SHA256 `a73d952619c08060465c1bdd8777614afd9ee0885b55200d5d499b3995af3eb9`）。首次 before 命令因编译时间戳读取旧测试，仅运行原两项并通过，该日志保留；明确触碰新测试时间戳后上述三项才构成有效复现。

真实批次原始结果及独立源码复核见 [通用验收](native-coding-real-acceptance-20261107.md)，不以本组件测试代替整体验收。

## Coding Agent 截止后的停止与结算

过期合同取消 native Sandbox 的 HTTP 回调在派发前被本地 deadline 拒绝：新 HTTP 测试修复前 **1 fail**，日志 SHA256 `9b434f3ee576db783ffd802aaef195310ee6200be0617c23fea9beaea601a2cd`。修复仅将 `native-command-cancel`、`native-tool-finish` 作为有界停止通道，避免已中止的业务信号阻止停止回执；仍使用 control timeout，未允许任何新执行绕过截止时间。Java 的独立停止证明及租户/fence 校验保持执行。

完整 server **12/12 通过**，包含过期/已 abort 的停止回调、新执行拒绝、停止回调自身超时、原模型调用任务 deadline 和一般控制回调 timeout。日志 `native-stop-callback-after-r2.log` SHA256 `7880646cd870711690bab43b322faed73924f9f81532c98da308608c6e7d4b02`。未修改上层业务 Agent 的超时，也未把控制 HTTP 超时当作 Python 进程已停止的证明。

## stderr 合并与退出状态

第二批真实 B 使用 `cd /workspace/code && python -m unittest discover -s tests -v 2>&1`，两次实际测试均通过，仍被声明命令校验拒绝。`2>&1` 本身保留测试退出状态；之前把它列为失败负向项的预期过严。更正后允许四种固定 cwd 字符串各自带或不带这个字面尾缀，同时保留字符串及 SDK command-only 对象身份，共 16 个有限 Hash。仍不接受其他重定向、管道、额外 args/env、`|| true` 或不同目录，不从日志中的 OK 推断成功。

新增回归修复前：Java 4 项中 1 fail（日志 SHA256 `d287417b69cb109ac17006036268e098fe09c6a3b9ce67a2d4fff8b5c18f138f`）；安装版 SDK 对象工具测试 1 fail（`9aa3a1de7db8c2f26e28f1f13feedda4b31fc86fa0ebdf3268c3491d941ad5b7`）。修复后 Java 声明命令、真实 A2 工作区和 HTTP **26/26**，日志 `1bd6a85165359b0c583c9ff3bb6de54e14d1b03a6f0a9c2a2d5786d99c374295`；Node native runner/server **25/25**，日志 `822436bd5903cd8c2245a297d947941b76084489b21359060c617b1425dd35bc`。未改变模型、预算或 Agent Loop。

## 版本化 pipefail 候选

固定 SDK 的 Bash 描述建议 head/tail 过滤，旧完成校验却拒绝这类命令。候选 profile 为 `runtime-coding:native-project:v2`，保留 SDK 0.0.93、原生工具定义和 Agent Loop。`chatchat.coding.native.pipefail-verification-enabled` 默认 false；新任务显式启用后冻结策略及 helper 能力 Hash，旧 v1 任务使用原字节 helper。MCP 通过既有 input-capabilities 协商精确 wrapper/base Hash，Worker 通过既有 workspaceProfiles 协商版本；不匹配时在模型调用前拒绝。

实际同镜像容器矩阵 **8 项预期行为一致**，见 [原始证明](evidence/native-coding-20261107/pipefail-executor-matrix-r1.json)。同一 KeyError 输出 Hash 不变，旧管道返回 0，新策略返回 1；SyntaxError、NameError、TypeError 的过滤管道返回 1，成功管道仍返回 0。显式 `|| true` 仍可得到 shell 的 0，但不能匹配治理验证身份；Java 负向测试覆盖该拒绝，不能将该用例的退出 0 解读为测试通过。

原 helper SHA `768e21d085d74f98b77615acdab13f3de04a864293f366a21e6a2a2fa6659dcc` 未变。新 wrapper SHA `65b631cdb3323d09641767ae7328b78edd0f84e4c855c4a1fa12b1b033cdd82e`；通过环境设置复用原 SDK shell executor，没有实现第二套终端。

Java 初次覆盖 **117 项，115 通过、2 项原执行面测试跳过**，包括 98 项 API、4 项 common 和 15 项 MCP；日志 `426501e5ddd272e22676b270b08cd85598a60b470ce47dc06680de94ab39ca7c`。随后对受限只读检查命令组合及声明匹配重测 API **24/24**，日志 `de8c98d17a6c59a8181f65a3c2baefa1b83f189069786744d975138cb64ce2ee`。覆盖 workload 伪造验证标志、退出码与独立停止回执不符、不同源码版本、缺少停止证明、MCP 不支持时零任务准入、关闭开关后原策略和预算保持。

Node 原生 runner/workspace/server **56 项，55 通过、1 项 Windows 链接权限跳过**，日志 `19da1d54a84cbc36716c9c37222d7f8447f84e07b68c0b690e6f95a1ab27d008`。新 profile 覆盖过滤执行、旧回执不得冒充新策略、后续匹配测试失败或结果缺失时使先前成功失效。SDK 等待回执上限绑定原任务剩余期限；队列测试证明短 SDK timeout 不等于执行器停止。任务 300 秒、Sandbox 单次 75 秒、模型和 CPU 预算保持原值，旧 profile 的等待设置不变。以上组件与执行器证明不替代真实模型验收。

完整 Node 回归为 **309 项：308 通过、0 失败、1 项 Windows 链接权限跳过**，日志 SHA256 `bcd50b900f3eaac6565046016602c47648508d32274ca8ae38c4b20da4ae9d83`。本地 Node 22.14 低于项目要求的 22.18，首次直接运行因四个测试文件导入 TypeScript 而失败；启用原有 `--experimental-strip-types` 后复测，未修改测试或产品代码掩盖失败。实际 Linux Worker 使用 Node 24.21。API/MCP/common 运行包构建成功，保留真实前端资产检查，构建日志 `3da27626162f4c73e8cecb65baa0f2c2fbb0e838379b320eb2c72c7b12e35ddb`。

## 合入运行期间推进的新基准

远端基准推进至 `167dfedf5` 后，在独立分支通过 `23c077f6d` 合入，保留基准既有业务修复，不覆盖或重新归因其测试结果。冻结的原生工作区、命令执行器及 helper 源码没有变化；真实模型批次仍按其原镜像及提交记录。

合并后的完整 Node 回归 **311 项：310 pass、0 fail、1 Windows 链接跳过**，日志 SHA256 `faa01999bb7c310397aa4065fd805b5127c00cdbcae878cd454a76ae587f9810`。Java 首轮因重复审计清洗导致排序被覆盖而出现 1 fail；删除本分支重复段，复用基准有界清洗后，API **176/176**、common **4/4**、MCP **15 项中13 pass/2 skip**，合计 **195 项，193 pass、0 fail、2 skip**。日志 SHA256 `d41291f933541fa389774e15db38109d25e7de75acd66b24d216a07421aa7277`。既有隐私、非法工具名和 Schema 指纹负向预期保留，没有修改测试降低门槛。

