# 通用 Coding Agent 安全、兼容性与恢复记录

状态：**部分验证通过，整体门槛未通过**。新 profile 和准入默认关闭；隔离验收环境单独启用，未替换原有 API/MCP/Worker 服务。共享模型及预算未修改，历史业务代码与证据保留。

## 真实 API 权限负向验证

仅在验收专用 API 数据库内，通过既有企业管理 API 创建两个测试用户及一个隔离租户，不新增产品数据库或权限协议。另一个用户、另一个租户分别尝试读取既有任务状态、源码、历史及取消任务：8 项全部 403。匿名读取及取消：2 项全部 401。目录穿越、Linux 绝对路径、Windows 绝对路径的项目准入：3 项全部 403。

合计 **13/13 通过**；没有披露原任务源码，原任务 usage 与归档重新读取均未变化。证据见 [实际 API 回执投影](evidence/native-coding-20261107/security-g-api-r1.json)。此结果只证明这些 API 边界，不等同于所有 MCP、Sandbox、工具和 Worker 恢复安全通过。

## 真实 Worker 恢复与已知边界

在已落盘编辑、丢失 ACK 窗口 kill Worker；退出 137 后，以相同 Worker 镜像重建并 resume 同一任务。原代码 manifest、历史和累计预算保留，SDK checkpoint 恢复事件及后续真实工具执行可观察。最终任务仍因未完成声明验证而 FAILED，最后源码独立测试通过。完整结果及尚缺的宕机前 SDK ID 独立观察见 [真实验收报告](native-coding-real-acceptance-20261107.md)。

真实 A2 回归已覆盖：未知 TOOL 保留停止阻断、取得新 fence 不代表执行已停止、旧 fence 拒绝、停止回执及源码不可变、已完成工具回放不重复扣费、替换参数拒绝。不能将这些组件测试替代宕机测试。本轮只验证 Node Worker 重建；API 进程同时重启后的租约接管与完整恢复尚未通过，不能泛化为全部服务恢复。

## 工具、执行与版本隔离

补充的 35 秒执行中断样本已在 kill 前后独立落盘观察：Worker 退出 137 时同一 Sandbox 继续运行，后续全部 CODE 取得独立 MCP 停止证明；原 SDK ID、合同、代码历史、deadline 和预算保持。恢复后的完整任务仍 FAILED。第一轮收集器缺失物理瞬间证据也保留为未知，未删去失败；第二轮只修正实验收集器，没有修改产品或提高预算。详见 [恢复补测与停止清点](native-coding-real-acceptance-20261107.md)。

受控 Sandbox 的固定六项安全断言已由实际模型触发执行，并由终态独立测试复核：非 root、无 Docker Socket、代码只读、根目录不可写、未授权网络拒绝和宿主 canary 不可读均通过。模型任务仍 FAILED，不能据此宣布 G 整体通过。[原始结果与 Hash](evidence/native-coding-20261107/G-isolation-summary.json) 和 [真实工具回执](evidence/native-coding-20261107/G-isolation-receipts.json) 与此前 13 项 API 拒绝证据并列。

文件工具使用固定 0.0.93 SDK 的定义与执行器；Runtime 提前校验路径、任务所有权、fence 和模型真实工具参数，并管理不可变快照。模型不能直接写版本/Hash。Editor/Patch 在受控工作区操作，Sandbox 从被冻结版本只读挂载；原 `python_execute` 兼容路径保留。

Worker 没有宿主 Shell 工具或 Docker Socket；命令通过 Java 治理进入既有 MCP Python Sandbox。验收 Sandbox 实际使用 NONE 网络、非 root、只读根、移除 capabilities；这些部署事实和已有组件负向检查不等同于全部越权攻击已整链路验收。磁盘及输出硬限额、CPU 精确计量仍有原执行面的限制，不能用未计量的 CPU=0 或预算 reservation 推断实际消耗。

Coding Agent deadline、模型请求、控制 HTTP 和 Sandbox 进程生命周期分别处理。过期后的取消和停止回执有独立控制超时；Java 仍须取得执行节点停止证明。上层业务 Agent 超时未在本轮修改。

## 未通过项目及回滚

尚未完成：宿主命令/动态 MCP 工具及全部攻击面的真实负向矩阵、发布 Skills 的长任务与撤权实测、MCP 动态 Schema 治理绑定、所有服务同时重启恢复、完整 CLI/旧 Runtime/新 Runtime 的三组可比重复性能验收。已执行网络拒绝和 SDK 参考对照不能外推为这些范围通过。SDK 接口预留、默认定义和单元测试不能替代缺失结果。

回滚先关闭新任务准入，保留现有 native task 的停止、恢复及归档读取。逐个确认 CODE/MODEL/TOOL 独立停止、保留 Silo 版本和 A2 证据后，再回滚 Worker/API/MCP 组件。不得通过删除任务、清空预算或丢弃未知执行记录简化回滚。原业务 profile 和旧修复路径保留，禁止在真实通用能力门槛通过前扩大到金融等业务集成。
