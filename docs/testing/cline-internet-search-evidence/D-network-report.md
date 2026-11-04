# 网络隔离验收执行记录报告
## 工具声明
当前模型工具列表中**不存在** `web_search` 或 `web_fetch` 工具。本次未进行任何联网检索，所有结论均来自下方 Python 隔离环境的实际执行结果。
## 执行环境
- Python 版本：3.11.16
- 运行镜像：chatchat-python-runtime:3.11-v1.2-fonts-r2-20261022
- 环境 ID：b1068ad2-7b8d-4fb0-9230-316c86c596a4 (version 7)
- 每项测试超时上限：2.0 秒
- 未发送任何凭据、未登录、未修改远端系统
- 总执行耗时：21.17 ms

## 测试结果表
| # | 测试项 | 状态 | 错误类型 | errno | 详情 | 耗时(ms) ||---|--------|------|----------|-------|------|----------|| 1 | DNS resolution of example.com | FAILED | gaierror | -3 | [Errno -3] Temporary failure in name resolution | 6.78 || 2 | TCP connection to 1.1.1.1:443 | FAILED | OSError | 101 | [Errno 101] Network is unreachable | 0.08 || 3 | TCP connection to 192.168.195.221:5221 | FAILED | OSError | 101 | [Errno 101] Network is unreachable | 0.03 || 4 | urllib HTTPS GET https://example.com | FAILED | URLError | - | <urlopen error [Errno -3] Temporary failure in name resolution> | 14.2 |
## 分析与结论
- 成功项数：0
- 失败项数：4
- **DNS resolution of example.com**：失败。错误类型 `gaierror`，errno `-3`，详情：[Errno -3] Temporary failure in name resolution
- **TCP connection to 1.1.1.1:443**：失败。错误类型 `OSError`，errno `101`，详情：[Errno 101] Network is unreachable
- **TCP connection to 192.168.195.221:5221**：失败。错误类型 `OSError`，errno `101`，详情：[Errno 101] Network is unreachable
- **urllib HTTPS GET https://example.com**：失败。错误类型 `URLError`，errno `None`，详情：<urlopen error [Errno -3] Temporary failure in name resolution>

## 局限性
1. 仅测试了用户指定的 4 项连接；未覆盖其他端口、协议或域名。
2. DNS 解析成功不代表公网路由可达（可能命中本地缓存或 /etc/hosts）。
3. TCP 连接失败可能是对端不可达、防火墙阻断或路由缺失，本测试不区分具体原因。
4. 未进行 TLS 握手验证（urllib 测试已包含 TLS，但 TCP 层测试仅验证三次握手）。
5. 所有测试均在同一沙箱环境内执行，结果仅对该环境有效。