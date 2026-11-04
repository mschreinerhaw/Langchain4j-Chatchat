# 检索引擎维护、多选与 Cline 实测

日期：2026-11-05，环境 `192.168.195.221:18100`，分支 `feature/cline-internet-search`。维护方法见[操作说明](../operations/search-engine-management.md)，实现边界见[联网架构](../architecture/cline-internet-search.md)。

## 交付行为

开关文案为“联网搜索”。模型管理增加“检索引擎”维护入口，支持名称、URL、API Key、腾讯 Secret ID、启停和调用限制；密钥留空保留，输入新值替换，保存后不回显。可新增、编辑或删除多个实例，不在启动时重建固定条目。模型改为多选已配置且启用的引擎，按模型独立保存，旧单选配置兼容读取。

任务冻结所选引擎集合，工具提供受限的 `provider_id` 枚举。API 在实际知识工具入口接纳该参数，再检查所选集合、模型/Provider 版本和用户/Agent 权限；未选引擎不能调用。参数校验覆盖统一入口，未仅修改工具定义和底层 HTTP 转发。

## 真实页面与接口

| 操作 | 实际结果 | 证据 |
|---|---|---|
| 新增自定义引擎 | 通过真实页面提交名称、URL、有效凭据，服务端生成实例 ID | `create-ui-proof.json` |
| 编辑名称与 URL，密钥留空 | 保存后 configured=true、版本递增；随后该实例真实搜索成功，证明凭据仍有效 | `create-ui-proof.json`、`source-proof.json` |
| 勾选两个引擎并保存模型 | 刷新页面以及更新 API 后，选择均保持 | `inspect-ui-proof.json`、`model-multi-selection.png` |
| 停用实例 | 从模型内打开维护窗口停用后，选项无需整页刷新即更新 | `disable-ui-proof.json` |
| 删除临时实例 | 真实确认框执行 DELETE，列表不再返回该项 | `delete-ui-proof.json` |
| 无登录 GET/POST/DELETE 维护接口 | 均为 401 | `final-api-proof.json` |
| 配置 GET 响应 | 不包含 API Key、Secret ID 或密文字段 | `final-api-proof.json` |

临时 ID：`search-0b7305a6-39bd-4af6-8ac3-6a22ba501da1`。复用已有有效腾讯凭据创建独立维护实例；**本次两个实例均为腾讯 WSA，并非两个独立供应商**。Brave/Tavily 适配仍保留，但没有其有效凭据，本轮未宣称完成这两个供应商的真实调用验收。

截图：[维护表单](search-engine-management-evidence/engine-maintenance.png)、[模型多选](search-engine-management-evidence/model-multi-selection.png)。截图密钥字段为空；只提交脱敏回执，不提交私有认证文件、原始模型会话或凭据。

## 真实 Cline 执行与报告

- Runtime OS 页面提交任务：`0dfa15d4-e9a8-4dd1-be1d-a3ad948cf7ef`，会话 `1793834856762-9535f23efe1658`。
- 冻结集合包含 `tencent-wsa`（revision 6）及临时实例（revision 2），模型为 `qwen3.7-plus`、policy revision 5。
- 实际工具事件：两次 `web_search` 成功，分别明确选择上述两个实例；一次 `web_fetch` 成功。数据库中产生 11 条不可变公开来源证据，包含实际参数、来源 URL、采集时间和内容哈希。
- 共 5 次模型调用，最终 `SUCCEEDED` / `COMPLETED`。期间报告提交曾触发复核和修正，保留事件，不将中间提交全部标为成功。
- 完整文本报告交付并可历史重开。此任务明确不要求 Python/图表，代码执行次数为 0；不以此代替此前图表验收，也不宣称本轮重新生成图表。
- 报告原文为 3484 字节，SHA256 `9ff6c52b3454065dc52030b627488534221ea3d2c480cba591ceaa1155ab25b8`。

见[执行回执](search-engine-management-evidence/execution.json)、[工具事件](search-engine-management-evidence/tool-events.json)、[来源记录投影](search-engine-management-evidence/source-proof.json)、[实际报告](search-engine-management-evidence/report.md)、[历史展示截图](search-engine-management-evidence/runtime-history-reopened.png)。来源记录由实际任务作用域中的持久化证据投影取得，不是预置或模拟返回。

验收后恢复模型原有开关及引擎选择，再停用、删除临时实例。两项被检查模型均恢复为关闭搜索、保留原有 `tencent-wsa` 选择。清理后再次通过真实浏览器重开报告，来源权限仍按当前用户复核，不重新联网；页面无错误。见[最终 API 检查](search-engine-management-evidence/final-api-proof.json)和[清理后浏览器回执](search-engine-management-evidence/browser-proof.json)。

## 出口、测试与部署

新 Worker 容器内实际 TCP/DNS 探测：公网 IP、元数据、主机私网、普通 API 端口和控制网络均不可直连；仅专用回调可连通，公网 DNS 不可解析。Docker coding 网络仍为 internal=true、IPv6=false，Worker 仍为非 root、drop ALL capabilities。没有通过引擎配置扩大代码环境网络权限。见[实际出口探测](search-engine-management-evidence/worker-network.json)与[容器产物证明](search-engine-management-evidence/acceptance-deployment-public.json)。

验证包括：前端模型管理 3 项测试；首轮完整打包 45 项后端相关测试；补齐统一参数入口后 44 项后端回归（与前者部分重叠）；Worker 在 Windows 和服务器离线环境分别通过全量 189 项测试。新增用例覆盖多选持久化、单选兼容、未选引擎拒绝、IO 中撤销、入口参数白名单、密钥保留及删除版本不回退。前端执行真实 Vite build，部署完整 Maven JAR 与 Worker 归档。

R1 配套部署源 `c7823a191b6023d0409907679f9c8db72b855dea`；统一入口修复源 `ad46a71aaa86bda02d6cdec929a0759886b8b04a`，最终 release 为 `/opt/chatchat-releases/cline-report-skills-search-providers-r2`。最终 API JAR SHA256 `59b7450bee95744fdf1f4a80ac0ed90db804edae3f5e3a955c3e1ba2a9cbeb4b`。MCP、News、Worker 复用配套 R1 的验证产物。

部署先检查空闲、备份，再替换；News H2 在停写后备份。新建模型—引擎关联表，旧单选行不清空。工具目录恢复为 HEALTHY、39 项注册工具；部署时原有会话内容、消息索引数量和无关容器均通过保留校验。见[部署回执](search-engine-management-evidence/deployment.json)、[完整产物哈希](search-engine-management-evidence/source-artifacts.json)、[Worker 离线测试](search-engine-management-evidence/worker-build.json)。所有提交证据的字节哈希见[index.json](search-engine-management-evidence/index.json)。
