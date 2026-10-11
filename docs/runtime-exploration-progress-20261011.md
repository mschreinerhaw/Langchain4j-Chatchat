# Runtime OS 探索进度与显式外网补充

Runtime 的探索图投影模型声明及真实执行事实。模型自主决定分析路径、取证、发现、内容类型和结束时机；本次没有增加分析模板、强制补证、业务结论评分或报告改写。

## 联机定位

原任务 `eb204dd8-d5f3-4788-bac7-cabfd4dc21b2` 仅执行一次统一搜索，模型在第一轮声明 COMPLETE，未发起后续证据请求。其本地 `margin_trade_daily` 查询没有匹配记录，但其他行情记录进入外网路由的数量判断。没有取到融资明细不表示没有大规模融资买入。

原始行情返回 2026-10-09 的 `volume=3598020`、`amount=95473102`、`close=27.17`；2026-10-08 返回 `volume=1804728`、`amount=47634959`、`close=26.11`。原报告把成交量写成 359.8 万手，与成交额和价格的数量关系矛盾。Runtime 本次没有修改历史报告，也不根据该例内置金融判断规则。

## 改动

- Harness 在模型返回时发布公开探索摘要，随后逐次发布取证开始、取证返回及失败事件，沿用 HARNESS_TURN、现有 Runtime timeline 和 Task 事件桥接。
- 进度记录轮次、阶段、请求数和已返回请求数，不生成分析完成百分比或证据充分性评价。`progress.summary` 是可选的模型公开声明，上限 500 字符；私有 workspace notes 不进入进度事件。
- 探索图中开始与返回使用同一个请求节点身份；Runtime 生成的回执 ID 不再使进行中的节点被另一个节点替换。
- 日志记录 runId、轮次、阶段和请求计数，日志不包含模型私有笔记或工具参数。
- `web_search` 增加可选的 `external_search_required` 参数。模型选择 true 时，即使本地已有足够多条结果，仍尝试外网补充；继续遵守工具授权、供应商配置、缓存和资源约束。默认仍沿用现有检索路由。News Runtime 记录外网请求及成功状态，供应商不可用时保留警告。

## 验证和部署

后端 Harness Maven 定向回归 22 项，前端探索图及事件展示回归 20 项，前端生产构建通过。检索路由 11 项及 MCP Provider 17 项通过 JUnit Platform 验证。

完整 MCP/News Maven reactor 被本地未缓存的 gRPC、Protobuf、Temporal 依赖阻断。本次检索回归使用已部署模块类及现有依赖包的 classpath 编译并运行；没有替换线上依赖版本。线上包仅更新相关 class 和前端资源，保留部署前的其他内容及完整备份。更新前无运行或排队 Task。

备份：`/opt/chatchat-deploy-backup/exploration-progress-20261011/`。发布包 SHA-256：

| 服务 | SHA-256 |
| --- | --- |
| API | `26c9c99705d440382f6bbcedef3a81352359460356e5263763a44558caffc313` |
| MCP | `7140f9b1c6c9f62987f12b277a7103a851556bceb84a5849e6f5bbd081d9c02f` |
| News Runtime | `e8be87ac361d563a2f99100c235d95b00e2c671b57e7293ff6c43c6cf181a7e3` |

服务使用原 Java 17 路径恢复启动。API PID 2076352，MCP PID 2076292，News PID 2076242。首页 200，重新登录后 API 健康状态 UP。

显式外网请求的线上验证确实调用腾讯 WSA：2026-10-11 15:02:56（北京时间），`externalRequired=true`，供应商返回 HTTP 200、10 条结果，耗时 557ms，requestId 为 `624783b0-c0ad-4663-9f47-1a83f339dd57`。该辅助 `/analysis/tool` 请求随后因既有证据载荷限制返回未接受状态，不能作为完整业务分析通过的证明。

原问题重跑任务：`7e7d3db6-0020-3cc7-af94-4f5bbaac35fc`，运行 `att-1-3c5fabe9-c9b9-44ff-b6ca-663facb30c2d`。原始回执和 timeline 位于本地 `target/codex-live/exploration-progress-20261011/`。单独保留了首次测试中不受支持的 mode=agent 失败记录；有效重跑使用现有 agent_chat 入口。没有写入登录令牌或修改历史任务。

有效重跑最终返回 SUCCESS。真实模型一轮结束，timeline 记录 STARTED、MODEL_RETURNED/RUNNING、COMPLETED；日志包含同样的轮次和阶段。模型本轮没有请求额外 workspace 取证操作，因此逐次取证事件及取消收尾的验收来自独立回归夹具，不能宣称这次真实运行覆盖了全部分支。最终报告仍明确缺少个股融资融券明细，无法证实或证伪大规模融资买入。Runtime 没有强制第二轮查询或提高投资结论的确信度。

真实运行包含两次成功的统一搜索；Task 事件序号 35 为 MODEL_RETURNED 的 RUNTIME_OBSERVATION，状态 RUNNING。使用前端同一投影函数构建实际 timeline，得到 14 个节点、13 条边，开始与结束合并为一个 COMPLETED 探索轮次节点。最后再次确认 API 健康状态 UP。共计 70 项定向回归通过，不包含被依赖缓存阻断的完整 reactor 验证。
