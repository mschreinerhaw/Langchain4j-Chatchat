# Phase 1 原生 CLI 检查记录

源码：`cli-v3.0.74` → `071e1b25b48f9ef5dff7fc32085e6a16f02bf02e`。
环境：Windows x64，使用 registry 正式 `@cline/cli-windows-x64@3.0.74`，未全局安装，未改变生产配置。

| 检查 | 实际结果 | 证明范围 |
|---|---|---|
| 发布包 SHA512 | 与 npm registry integrity 一致 | 下载包完整性 |
| SLSA payload | subject digest 与下载包一致，来源 commit 与 tag 解引用一致 | 发布载荷来源绑定；未独立验证证书链 |
| `--version` | exit=0，输出 `3.0.74` | 固定二进制可启动 |
| `--help` | exit=0，含 config/data-dir/id/json/acp/timeout 参数 | 发布二进制参数入口 |
| ACP initialize | exit=0，收到 JSON-RPC id=1 的 result，protocolVersion=1、agentInfo.version=3.0.74、loadSession=true | 官方 ACP 入口可握手；不证明 loadSession 已实际恢复 |
| Agent Core 调用链 | 固定源码逐层追踪到 model.stream 和 executePreparedTool | 官方实现复用；未执行真实模型任务 |
| Java/Node 语法和文档范围 | 本次仅 Node 证据脚本与 Markdown/JSON；不改生产 Java/Node | 不需要编译部署 Java 项目 |

详见 [机器原始结果](evidence/cline-cli-native-phase1-20261107/source-and-smoke.json) 和 [发布记录](evidence/cline-cli-native-phase1-20261107/release-pin.json)。

复跑（目录可替换，运行前准备同一固定源码 archive 和正式 binary tarball，target/cli-baseline/binary.json 为该版本 registry 元数据）：

```powershell
node docs/testing/cline-cli-native-phase1.mjs ../Langchain4j-Chatchat/target/cline-cli-source-3.0.74 target/cli-baseline
```

脚本逐文件记录 SHA256，校验 tarball SHA512 后执行三个无模型检查。子进程去除 provider 凭据和 CLINE 环境配置，设置任务私有配置/状态目录及 local backend。未发送 prompt，未请求模型执行、访问业务数据或更改 SDK。

**未执行 / 未通过验收的事项：** 相同模型参数下的 A/B 自主编码、Skill 选择、MCP 调用与动态变更、多租户负向隔离、local 首轮 timeout/abort、Hub/worker crash、Session 实际恢复、远端停止和去重交付。C 组尚未实施。不存在本次三组性能或 token 比较结果。

原因：本次没有冻结的同模型执行配置、任务输入和授权测试环境；仅使用已存在的生产设置或历史结果不能产生有效对照，也不能擅自绕过 Java 累计预算。上述未完成项作为 Phase 1 评审缺口保留；只有发布/入口检查通过，不能宣称完整原生基准完成。
