# Cline Native Skills 首批整改验证

日期：2026-11-06（Asia/Shanghai）。分支 `refactor/cline-native-skills-20261106`，起点 `edc637eb4`。按[根因与修改矩阵](../audits/cline-native-skills-remediation-matrix-20261106.md)先提交调查，再分批改动，没有部署或切换生产流量。

## 已实施

| 提交 | 范围 |
|---|---|
| `fe4b4a199` | 源码根因、调用链、P0/P1 文件级矩阵、SDK 复用分类与验收缺口 |
| `e33b3e5c8` | 预算耗尽与显式 COMPLETED 展示、失败无成果不借用别的节点、部分报告标签、Java terminal 投影 |
| `fe47cf494` | 安全 Plotly 输出示例、results 路径与真实图表注册说明；read-artifact 的可用图数/图表状态/诊断投影；不修改不可变报告或放宽安全规则 |
| `b17104b87` | 原生 Skill 文件越界读取的精确诊断和不含请求路径的事件；15/138/515 项公开 SDK Schema 成本测量脚本 |

`artifactValidationStatus` 的既有含义保持为清单接纳状态，`chartValidationStatus` 是从原报告计算的读取结果，不是新任务终态。没有新增存储、Skill Loader、模型调用层、SDK fork、固定调用次数限制或 SDK 升级。文本模式未被强制改为 visual，模型仍可自主跳过或组合 Skills。

## 本地验证

| 检查 | 结果 / 复现 |
|---|---|
| 探索前端 | `npm.cmd run test:exploration`：11 文件、130 项通过，0 失败；包括预算失败刷新后的无旧图回显、运行中祖先展示、取消后已有成果、迟到请求、状态轮询 |
| 报告前端 | `npm.cmd run test:visual-report`：7 文件、62 项通过，0 失败；与上一组存在重叠，不相加冒充唯一用例数 |
| 首轮 Node 全量 | `npm.cmd test`：241 项通过、0 失败、0 跳过；包括真实已安装 SDK、受控模型和恢复协议，不代表真实 Provider 业务验收 |
| 增量原生 Skills | `node --test coding-agent-node/test/native-skills.test.js`：14 项通过、0 失败、0 跳过，含四种越界精确诊断与事件脱敏 |
| Java 最终定向 | `CodingAnalysisServiceTest,VisualReportArtifactsTest`：74 项通过、0 失败、0 跳过；包含终态/未决执行、合法字体属性与恶意 span、有效与无效图并存、只读投影不修改报告 |
| Java 编译和前端生产构建 | 首轮在线 Maven reactor test 成功，完成 API/MCP 前端构建；生成的 static 输出已恢复，避免把构建产物混入源码修复 |
| 补充回调回归 / Node 最终全量 | 回调 19 项 + 报告 29 项 = 48 项通过、0 失败/错误/跳过，Maven BUILD SUCCESS；Node 最终全量 241 项通过、0 失败/跳过。Java 三个类合计 93 项唯一用例，不重复计入重跑的报告 29 项 |

Maven 命令：

```powershell
mvn.cmd -o -pl chatchat-api -am test '-Dtest=CodingAnalysisServiceTest,VisualReportArtifactsTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' '-Dfrontend.skip=true' '-Dfrontend.install.skip=true' '-Dmaven.repo.local=.m2/repository' '-Dfile.encoding=UTF-8'
```

回调补充用相同命令替换 test 为 `ClineModelCallbackControllerTest,VisualReportArtifactsTest`。Java 摘要和 Node 最终计数另附 `evidence/cline-native-skills-remediation-20261106-checks.txt`；本机完整原始日志在 `target/native-skills-remediation-*.log`。

首次尝试保留两类失败：PowerShell 将 npm 解析为受执行策略限制的 npm.ps1，后改用 npm.cmd；Maven 离线仓库缺少 Google BOM，在线补齐依赖后成功。前端首轮一项既有取消回显断言失败，随后修正本次过滤范围保留原行为，未删除或放宽历史断言。它们不计为通过测试。

补充回调初轮有 3 项断言失败：新增可用图计数从 Integer 变成 Long，Jackson 树节点类型变化，即使 JSON 文本同为 0，原有相等断言仍失败。计数实现已保持原 Integer 返回类型，未削弱断言；初次失败日志为 `target/native-skills-remediation-callback-final.log`，最终重跑为 `target/native-skills-remediation-callback-verified.log`。

## P1 计量

复现：`node coding-agent-node/scripts/measure-native-skills-catalog.mjs target/native-skills-catalog-cost.json`。本机 SDK `0.0.93`，单进程合成目录；[输出](evidence/cline-native-skills-remediation-20261106-catalog.json)。

| 总 Skills | 工具 Schema 字节 | 用途投影字节 | 初始正文加载 |
|---:|---:|---:|---:|
| 15 | 8835 | 4912 | 0 |
| 138 | 39637 | 28198 | 0 |
| 515 | 135395 | 100582 | 0 |

保留完整授权目录；没有以限制数量或隐藏能力降低成本。字节/4 只是估算 Token，Provider 用量明确记为 UNKNOWN；单次准备耗时不能作为性能改善结论。此次未调用真实模型，未证明 P50/P90、总调用或 Token 成本改善。

## 仍未验收

P0 未关闭：文本入口图片实际文件与 Markdown/正式引用绑定、直接文件分析 HTTP 中断后的只读状态恢复、Worker 故障到所有入口终态一致性、正式探索真实模型生成两类图且可下载。安全契约和准确反馈可以减少失败，但不能替代这些实际归档证明。

P1 未关闭：固定六类任务的 Provider 配对测试、分阶段时钟/未知调用解释、Parquet 正向自主选择、动态撤权和压缩/重启后的真实业务恢复证明。此次没有新 Docker 验收或生产浏览器截图；历史 10 项 Docker 与旧模型成绩没有算成本次通过。

每个代码提交可单独 revert；数据库和 SDK 版本未改。生产部署前仍需核验在途父任务/Worker/Python、准备确切工件/hash和镜像配置备份，并获得任务书要求的切流确认。
