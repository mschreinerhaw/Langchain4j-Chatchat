# P3 第一阶段关键证据

入口：[测试报告](../../cline-server-p3-stage1-20261108.md)。evidence-manifest.json 记录66个导出文件的 SHA256，本索引和 .gitattributes 后加；文件不做换行转换。

- stage1-summary.json、coding-summary.json、simultaneous-status.json：阶段边界、两个真实原生任务及并发现场。
- contracts.json、http.json.gz：真实签名合同的公共内容、身份Hash与实际API响应；不导出可用签名token。
- scoped-sse-and-quota.json：治理入口真实SSE断线/回放、外国SSE拒绝、独立租户限额、过期及越权数据合同拒绝、正文跨撤权竞态403；该兼容性探测没有模型推理。
- a/、b/：原生工具回执、完整输入/输出、独立测试、代码Diff/Hash、runtime校验、Docker隔离配置及最终映射。
- adversarial-summary.json、a/adversarial.log、b/adversarial.log：实际 UID1000 文件/Socket/网络/MCP拒绝与合法数据读取，不是模拟探测结果。
- revocation-summary.json、revocation-http.json.gz、revocation-native-events.json.gz：真实运行中Shell撤权，仅本地停止；remoteStopConfirmed=false。
- control-audit.json、mcp-real-file-receipts.json：Worker外控制审计和真实MCP文件读取日志。
- native-source-integrity.json、adapter-files.json、runtime-verification.json、deployment-final.json：608个Core/Agents零修改、原4处宿主补丁、服务源码Hash、1850资源、实际镜像与无活跃Worker证明。
- failures/ 保留初次资源装配/状态/原生权限拒绝；previous-pass/ 保留修正资源预留前一版已通过结果；最终测试对应 bfea8255 镜像。
- evidence-export-tests.log：实际文件、目录symlink、越界路径、FIFO的安全读取回归；导出成果不依赖不可信workspace Git。

JSON.gz 为gzip压缩JSON；reasoning与重复Agent chunk在原生事件出口排除，toolCallId/输入/结构化回执保留。所有发行密钥、provider/MCP上游密钥和各Worker API/broker令牌经扫描脱敏。可见 marker 为专用安全测试文件数据，无真实客户业务数据。

这些材料只证明第一阶段基础隔离与本地撤权。操作恢复及MCP/Python远端长作业停止均未运行，不能从这些结果推导整体P3验收通过。
