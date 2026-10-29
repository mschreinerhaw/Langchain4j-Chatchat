# 独立 Cline `/monitor` Python 执行与修复诊断修复

已部署到用户指定的 `http://192.168.195.221:18100/monitor`，应用版本 `a076b3f323d3`。使用方式：刷新监控页、用原只读监控凭据连接，选择任务后点击 **Python 执行与修复**，展开执行记录或点击 **查看真实修复 Diff**。

## 断点与调用链

此前的诊断修复位于主站 Vue `CodingTaskMonitor`。独立 `/monitor` 由 `coding-agent-node/web` 提供，只读取 Node 受限观测，缺少 Java 持久化回执、修复父版本及后续验证。部署主站不能补齐这个独立页面。

本次链路为：

```text
/monitor 的原 HttpOnly 运维会话
 → GET /v1/monitor/tasks/{observationId}/python
 → 本节点保留的真实任务身份（不接受浏览器自报租户/用户）
 → 原签名回调通道 POST /internal/coding/cline/monitor-python
 → CodingInvocationContext.READ
 → 原 DurableCodingTaskService + CodingExecutionFeedbackService.timeline

用户展开执行或查看 Diff
 → /python/timing/{operationId} 或 /python/codes/{operationId}
 → 原 RuntimePythonAssetArchiver 的 receipt.json / CodingCodeArtifactStore
 → 校验执行 ID 与代码 SHA256 后展示
```

Java 回调继续使用原 HMAC v2、来源地址与防重放校验。Nginx 8081 回调白名单仅增加 `monitor-python`。历史只读查询不要求仍有效的执行租约，也不续租、恢复任务、调用模型或启动 Sandbox。授权仅为 READ；运维凭据不能发起执行、取消或修改业务权限。

没有新增监控数据库、执行器、分类器或日志系统，没有把完整反馈和源码复制到 Node 的持久化 observations。Node 的原 `NODE_OBSERVATION_ONLY` 状态保持原语义；Python 页明确展示 Java `DURABLE_CODING_JOURNAL` 的任务状态和真实回执。

## 页面内容

- 已归档执行、进程退出0、进程失败/超时、产物校验失败、自动修复、修复后未通过分别计数。取消与未知退出码不会冒充进程失败。
- 每条执行展示真实执行 ID、脚本版本/文件、归档开始结束时间、耗时、退出码、代码摘要、异常类型、分类、错误码、安全消息及受限文件行号。
- 进程退出0、产物可用和业务校验分开，未独立验证的业务仍显示未确认。OUTPUT_CONTRACT 不冒充 Python 异常。
- 修复关系要求前一执行确实允许修复、父摘要一致、修复计数恰好递增且代码改变；普通重试、成功后的复核、相邻版本不会自动算作修复。
- 修复区关联触发失败、前后代码摘要、后端安全修复指导、真实归档 Diff、后续执行及产物结果。模型判断不标为已证实根因。
- 时间线来自真实回执顺序；原始日志不可用时明确提示，不拼造完整 Traceback。缺少旧归档或连接暂不可用时显示不可用并保留已取得信息，不推断执行成功或任务失败。
- 源码与 Diff 仅由原独立运维凭据按需访问本节点保留任务对应的归档，不存入 Node 观测文件。源/目标版本均核对实际执行摘要，渲染使用 textContent，不执行源码或 HTML。

## 验证证据

| 范围 | 结果 |
| --- | --- |
| Java | 34项通过：原回调鉴权、反馈回归及新只读投影；含历史读取无租约副作用、无 WORKER 授权、源码摘要拒绝与归档时间绑定 |
| Node | 120项通过：原SDK/运行回归及修复关系、Diff边界、取消/未知退出码分离、只读身份绑定、不持久化反馈 |
| Docker/Nginx | 使用服务器缓存镜像执行1项白名单测试通过；后端明确为鉴权测试 fixture，不将此项冒充真实 Java 联调 |
| 隔离真实 `/monitor` | 历史真实任务2次执行、1次修复；KeyError、project/main.py:5、归档时间、Diff、后续成功与刷新恢复通过 |
| 生产真实 `/monitor` | 历史真实任务3次执行、2次修复；KeyError → SyntaxError → 成功，两个真实 Diff、对应后续验证和刷新恢复通过；页面0个脚本错误 |
| 只读性 | 页面操作前后 Java 任务 revision、完整执行回执及 Node 模型调用数保持一致；本轮不重新执行模型或 Python来伪造展示数据 |

生产验收任务：`analysis-coding-3cdb574ebd28219f7545d4fb98d39fb750ade956d9a76b5b2e2362054a4fab5b`；对应监控 observation：`0a75cbf93d13b576b4d685b68ee5bbd526ad4b5c083e575777609bf777c66450`。这些是此前真实 Cline、配置模型及 MCP Sandbox 执行的受控源码场景。本轮复用其真实持久化证据验证只读页面，不宣称进行了新的全量业务分析。

- [生产真实错误截图](cline-operator-python-monitor-20261029-evidence/production-operator-python-C-error.png)
- [生产真实修复 Diff 截图](cline-operator-python-monitor-20261029-evidence/production-operator-python-C-diff.png)
- [生产浏览器核对](cline-operator-python-monitor-20261029-evidence/production-browser-proof.json)
- [隔离浏览器核对](cline-operator-python-monitor-20261029-evidence/preview-browser-proof.json)
- [镜像与源码摘要](cline-operator-python-monitor-20261029-evidence/images.json)

浏览器脚本为 `scripts/verify-cline-operator-python-browser.mjs`，需要忽略目录中的只读凭据和真实任务清单。测试没有拦截网络、替换响应、放宽 CSP 或添加模拟进度；测试等待使用真实 DOM 查询。

Windows 本地网关 Docker 测试因未具备所需缓存镜像而未完成，随后同一测试已在服务器缓存环境通过。没有临时联网拉取镜像以绕过离线约束。

## 部署与回退

仅精确替换隔离/生产的 API、Cline Node 和共享 API 网络命名空间的代理；未使用 Compose 重建选择器，避免影响同标签预览容器。正式切换前验证无活动任务或 Sandbox，备份 Node 原持久化历史与原容器配置。生产 Compose 镜像配置同步保存，回调配置仅追加只读路径；其余运行容器 ID 保持不变。未变更数据库 Schema、反馈契约、License、密钥、模型选择和执行预算。

当前发布和备份目录见 [production-deployment.json](cline-operator-python-monitor-20261029-evidence/production-deployment.json)。其中 `retired` 保存精确旧容器名称，`backup` 指向受限原环境/Compose/代理与 Node 历史备份。回退应先确认无活动任务，再恢复原配置及原 API/Node/代理容器，重新解析网络身份应用原8081防火墙规则，校验 HTTP、Cline及网关；不覆盖数据库或删除任务、源码、回执。Node 重启会使原监控 Cookie 过期，用未变更的原只读凭据重新连接即可。

旧任务如果不在本节点原有保留上限内，或 Java 回执、源码、时间归档已经不存在，仍不能恢复缺失数据；页面不会据此虚构 Traceback、源码或修复关系。本轮真实页面验收覆盖 KeyError、SyntaxError 及修复成功；ModuleNotFoundError、OUTPUT_CONTRACT 的显示继续采用同一原契约，前者未在本轮独立页面重新执行真实案例，后者计数分离由自动化测试覆盖。
