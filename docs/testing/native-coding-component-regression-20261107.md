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
