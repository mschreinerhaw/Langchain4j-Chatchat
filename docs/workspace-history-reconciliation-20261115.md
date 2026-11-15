# 工作区历史构建产物清理

原工作区存在 17 项构建目录改动，其中有效内容变更只有两个生成的 HTML 入口和一份过期 CSS；其他主要是换行差异。项目 `.gitignore` 已明确忽略 API static 与 MCP static，但历史提交仍跟踪了 20 个文件，导致每次源码构建后工作区再次变脏，且只提交入口会遗漏被忽略的新哈希资源。

本次停止跟踪这 20 个生成文件，保留本地构建输出。Vue/JavaScript 源码、两个 web-app 的入口及 public 品牌资源继续跟踪，不提交零散构建快照。API 使用 `npm --prefix chatchat-api/web-app run build`，MCP 使用 `npm --prefix chatchat-mcp-server/web-app-mcp run build`；Maven 默认构建流程仍负责打包这些产物。跳过前端构建只适用于已验证具有完整前端的增量部署基线。

两个前端源码构建均已通过，并检查生成 HTML 的本地资源引用均存在。此提交不部署或覆盖服务器前端，不修改业务数据，也不删除历史分支。
