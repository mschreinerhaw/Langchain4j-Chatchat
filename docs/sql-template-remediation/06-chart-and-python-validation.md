# 页面图表与 Python 实测

两种结构的图表链路已真实运行：仓库国家／省州分组，以及按日期聚合的库存快照。均从普通 Agent 管理页进入对话，打开图文报告，实际查询资产 248，交给 Cline 编写 Python，经 Sandbox 执行、上传产物，最终由系统页面加载图表。未使用导出 PNG 替代页面验收，也未 Mock 网络。

- [C01 分组柱状图](cases/C01/after-r9/verdict.md)：1 行 × 7 个源字段，与独立 JDBC 相同；报告6个源字段相同，派生占比、合计及交互图值另行检查。真实 Python 和 PNG 字节哈希与执行、上传回执一致，页面图片已加载。
- [C02 日期折线图](cases/C02/after-r9/verdict.md)：5 行 × 5 个源字段、报告明细 25 单元格与 JDBC 相同；最终轮日期折线图在系统内实际渲染。日期为 1998-01-01、08、15、22、29，未补零、未插值，未知库存单位未猜测，未把跨日库存相加当成时点余额。

报告自身保持 PARTIAL：数据覆盖和业务单位并没有因为程序执行成功而自动确认。功能验收 PASS 与模型声明的分析限制分别保留。

## 故障与部分成功

[独立受限环境](python-negative-r2/environment-cleanup.json)按正常管理接口创建，网络 NONE、CPU 0.5、内存 128m、超时 30 秒；测试后已停用。没有修改既有环境。

- [异常原始回执](python-negative-r2/python-exception.json)：FAILED / exit 1；[实际 Studio 页面](python-negative-r2/python-exception-system-page.png)与[UI 回执](python-negative-r2/python-exception-ui-receipt.json)明确显示错误。
- [超时原始回执](python-negative-r2/python-timeout.json)：TIMED_OUT / exit 124；[实际 Studio 页面](python-negative-r2/python-timeout-system-page.png)与[UI 回执](python-negative-r2/python-timeout-ui-receipt.json)明确显示超时。首轮 5 秒环境被冷启动抢先耗尽，原记录保留在 python-negative/，不作为预期异常测试通过。
- C02 after-r6 历史实际执行保留一次 NameError / exit 1，模型修复后再次 VALIDATED / exit 0；原始 coding-*.json 同时保留，不能把执行历史概括为没有失败。
- [A06 输出契约拒绝](cases/A06/after-r6/output-contract-negative-proof.json)：required edge 字段未生成，STEP_OUTPUT_CONTRACT_FAILED / EDGE_CONTRACT_FAILED，库存下游未执行。最终只提供已有仓库分布，状态 PARTIAL。该记录是失败路径证据，不能当作多工具完成。

## 防止验收硬编码

生产 Java 只交付实际 SQL、参数绑定顺序和来源回执，不包含 warehouse_count、日期、仓库编号或统计答案的场景分支。实际执行的 .py 原件随两个用例归档：从不可变 manifest 和数据分片读取，验证 SHA256，再生成表格和图值。每次模型生成的分析程序允许包含本次问题的标题、字段映射、期间和业务对象；这与在生产代码中预置答案是不同的证据范围。

## 最终 r9 原件与独立检查

[C01](cases/C01/after-r9/verdict.md)同时生成静态PNG和Plotly动态柱状图，[C02](cases/C02/after-r9/verdict.md)生成观测日库存记录折线图。图表实际页面加载、浏览器render事件、产物SHA256、Sandbox结束状态、Python原件均已归档；C01静态图及动态series=5，与真实仓库数一致。报告数值另由 verify-final-acceptance.mjs 读取独立JDBC原值验证，不只采信Python自报检查。静态图的证据为真实程序/产物/页面一致，不冒称做了像素OCR数值认证。

商品2此前因Parquet物理列位置猜测连续执行失败，原始失败程序保留在 cases/A02-productdata/after-r8/。修复的是通用传输契约：按manifest.columns[].storageName读取，presence列独立，原生TEXT保持文本；未在生产逻辑写入商品号或交易字段。最终after-r9同一问题成功，12行84字段及回答合计通过独立对账。
