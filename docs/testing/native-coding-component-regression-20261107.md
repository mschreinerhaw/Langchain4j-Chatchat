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
