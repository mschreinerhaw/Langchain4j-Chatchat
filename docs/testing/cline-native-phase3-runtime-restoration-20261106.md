# Phase 3 验证环境工件恢复

日期：2026-11-06，Asia/Shanghai。服务器为本轮已授权的验证环境。本文只记录工件恢复及相关复测，不能代替 Phase 3 全量验收。

## 恢复依据与镜像身份

核查时，业务 Python 镜像、沙箱 fixture 镜像及早前停止的回滚容器已不存在。用户确认没有其他人在清理，并明确要求从本地 Windows Docker 导入带中文字体的镜像，镜像名称必须与系统当前设置相同。现有信息不足以认定删除原因。

从 Windows Docker 缓存 `chatchat-python-runtime:3.11-local-source-20261102-r4` 导出 Linux amd64 镜像，使用系统既有标签 `chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022`。没有修改应用中的镜像配置、共享模型、预算或 Agent 参数。

| 项目 | 结果 |
|---|---|
| 导出归档 SHA-256 | `2b45feccfddb17db41028beebf2af1b447c7a565b47c45f45e9ccc4f1321452e` |
| 服务器导入后镜像 ID | `sha256:3434f7630ff7c54a053c7385710e089d249e65d668699ab96f9603b6e1b27eef` |
| 先前缺失镜像 ID | `sha256:7c665132dd360b4f84a669a16b53de93c0ff142e1ecf8f56bef1a07d6346fdc8` |
| 身份边界 | 标签一致，内容不同；按本次用户指定的 Windows 缓存恢复，不宣称找回原镜像 |
| Python / pandas / numpy / matplotlib | 3.11.18 / 2.3.3 / 2.3.5 / 3.10.0 |
| 字体 | 镜像内置 Noto Sans CJK JP 与 DejaVu Sans |

导出归档在服务器逐字节 SHA-256 校验后导入，再检查镜像 ID、平台及运行结果。Windows Docker 列表可能显示 OCI index ID；服务器 Docker 使用的是归档 manifest 指定的 Linux image config ID，未把两种摘要混为一谈。

## 字体及隔离门禁

本地和服务器均以网络关闭、根文件系统只读、UID/GID 10001、cap-drop ALL、no-new-privileges 的沙箱参数运行真实字体检查。检查中文字符及负号覆盖、PNG/SVG 导出、历史代码强制 `font.sans-serif=['DejaVu Sans']` 场景、缺字警告和 SVG 内嵌字形路径，全部通过。服务器导出的 PNG 已人工查看，中文标题、坐标标签及负号正常。

- [导入身份与字体检查](../evidence/cline-native-phase3-python-restoration-20261106.json)
- [服务器实际生成的中文 PNG](../evidence/cline-native-phase3-python-fonts-20261106.png)

只读容器中的 fontconfig 会提示缓存目录不可写；字体覆盖和两种输出检查通过，未为此放宽文件系统权限。第一次补存图片证据时，验证辅助命令误将 `verify-fonts.py` 当作可导入的 `verify_fonts` 模块，命令失败；改用 `runpy.run_path` 后复验成功。两次原始日志均保留，不将辅助命令错误归为业务镜像缺字。

沙箱 fixture `python:3.11-alpine` 也已从 Windows 缓存恢复，归档 SHA-256 为 `93e5305bda920d51cce6a9665a8aa0f143549a7b7dc4a0dbc6ecd53c13b99886`，服务器 image config ID 为 `sha256:607dc744833974b365cc338d565e01bb76eaebe25993fe328ce185170bcd8f05`。恢复后 Monitor 候选版本 Worker 回归 252/252、真实 Docker 沙箱回归 10/10 通过。之前缺镜像导致的沙箱失败日志单独保留。

## 回滚保护与部署边界

当前 P0-01 API、Worker 镜像已导出到服务器受限目录 `runtime-rescue/current-api-worker.tar`，SHA-256 为 `39eae19589f1aca3380511ff4d3b20f52a860eca8719c784f22c23adc44f2ef4`；容器配置快照仅存私有文件，不提交凭据。该导出保护当前版本，不能替代已经丢失的历史回滚容器。

Monitor 候选部署首次在切换服务前因历史构建标签 `runtime-os-api-runtime:17-sqlglot-20261024` 缺失而停止。该标签并非实际 Dockerfile 的父镜像。部署辅助脚本改为检查当前运行 API 的不可变 image ID，给它创建带完整摘要的离线父镜像标签，再验证标签所指 ID 后构建；原 JAR 基线校验、空闲检查、挂载/安全参数、回滚及部署就绪门禁继续保留。首次直接把 `sha256:<id>` 写入 FROM 的辅助脚本尝试构建失败，随后改为上述已验证本地标签；失败发生在服务切换前，日志保留。构建输出现在单独保留于 `api-build.log`。

P0-02 已部署：API JAR SHA-256 `ee2a057dd3d693f80a904b6487dd58bab578ae849b894af2d724e50de9ed3401`，Worker 镜像 `sha256:beecfec0d1d2f7e3b9321da785c30c5c3bd26e8616136618d95d78d6d14782aa`，产品源码提交 `b3cbbac70fc4dbf79ebb8c89809e8002752456a7`。P1 Decimal/报告事实约束提交尚未部署，不能计入本次线上结果。

[实际部署与运行镜像记录](../evidence/cline-native-phase3-monitor-deployment-20261106.json) 显示 API、Worker 均为 DEPLOYED，其他容器未被改动。本轮重新保留了切换前的 API、Worker 回滚容器，并保留此前导出的镜像包。

## 真实业务复测

恢复后使用服务器已有 `qwen3.7-plus`、原预算和真实 Java 模型桥/Python Sandbox，执行 `fault-success-windows-01`，结果 **PASS**。一次 execute 后仅轮询既有任务；在成功 Python 回执停止确认后 SIGKILL Worker，并启动同一个容器。实际结果如下：

| 核查 | 实际值 |
|---|---|
| 同一 Task ID | `analysis-coding-e1486aec5891067596c99a41de3b1624313ce7c10d9744d972c35cace569975a` |
| 同一 SDK Session | `0f715898-30be-4ac5-aadc-4a6d7f027e74` |
| Checkpoint / modelOrdinal | 5 / 3 → 6 / 4 |
| Checkpoint SHA-256 | `b8570cc7eeeff74d3fb161a344f902db6073215bfb68e89c3a942d719730d3fe` → `65ba05f74491fa30d4af45977a3673771b5d94f10af0c690745275fb79eccc1e` |
| SDK 实际恢复事件 | Journal sequence 73，`SDK_CHECKPOINT_RESTORED` |
| Fencing | 1 → 2，原模型操作身份保留 |
| 代码链 | 原 INITIAL v1 → PATCH v2 保留，成功 v2 的 Python 操作不重放 |
| 成功 Python operation | `cline-61d9587e4be7fb69ebe9a0793cec449bf261fb7545b3ad08c315fb10164d2f43` |
| 成功代码 SHA-256 | `22ed6b260b8761e55c379b8e5d722ee5ea87b423f5c64a5e4f4f2b4076b92f09` |
| 预算和截止时间 | 原值不变；UNKNOWN 预留继续保留，meteringComplete=false，不宣称零费用 |
| 最终终态 | `COMPLETED` |

- [中断前原始公开记录](../evidence/cline-native-phase3-windows-runtime-before-fault-20261106.json)
- [真实中断与恢复的严格门禁结果](../evidence/cline-native-phase3-windows-runtime-recovery-20261106.json)

同一任务真实浏览器检查 **PASS**：正式归档中的 Plotly 图正常渲染，中文标题和坐标轴正常；East 32、West 14、总和 46 与表格/叙述一致；断网、恢复网络及刷新后回执 hash 不变，未新增 execute，没有页面异常。该报告选择 Plotly；镜像内部 Matplotlib 中文 PNG/SVG 能力由前述实际字体检查另行证明。

- [报告页面检查](../evidence/cline-native-phase3-windows-report-browser-20261106.json)
- [真实报告截图](../evidence/cline-native-phase3-windows-report-20261106.png)

Monitor 实际 API 和浏览器检查覆盖两次此前真实故障任务与本次任务，三例全部 **PASS**。同一 task 的原 fence=1 观测仍为 INTERRUPTED，派生状态从 Java 权威记录确认为 COMPLETED；没有清空原始历史。新旧尝试均指向同一权威终态，结束时间一致；断网与刷新后事件/代码数量保留，未执行任务。检查工具早期因严格 CSP 的 `waitForFunction`、同文档 hash 导航假设和恰逢真实 SIGKILL 时的 502 分别失败，原始输出保留；最终使用不绕过 CSP 的 locator、新文档入口，等待真实故障实验结束后复核，未修改产品来迎合检查。

- [Monitor API/浏览器公开结果](../evidence/cline-native-phase3-monitor-browser-20261106.json)
- 复核工具：`scripts/verify-native-monitor-stage.mjs`，使用私有鉴权文件；无执行或重试提交路径。

## 尚未完成的 Phase 3 项目

本次完成 Windows 镜像与字体恢复、P0-02 发布、真实 Python 成功后 Worker 续跑和同任务页面复核。修复第二轮中断、真实 Compaction 后中断、Skills 调用边界、超时/取消/旧 fence 等完整故障矩阵，以及六类固定质量数据和新环境下配对性能复测仍需继续。不能据本次结果宣称 Phase 3 全量通过，也不能把更换 Python 环境前后的性能直接合并比较。
