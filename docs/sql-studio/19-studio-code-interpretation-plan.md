# Python / SQL Studio 代码解释计划扩展

本批为两个 Studio 的新一轮 AI 请求增加必经阶段：制定代码协作计划、校验并保存计划、按计划分析与探索、解释或生成建议、人工审查代码变更。计划校验通过后自动继续，不新增人工审批环节。

## 复用边界

复用已有 InterpretationPlan、InterpretationPlanValidator.validateAnalysisPlan、InterpretationPlanStore 和 DAG 投影。新增 StudioCodePlanService 作为代码协作领域适配器，负责模型计划提示、校验和请求绑定；没有修改 Runtime 核心或建立另一套工具执行循环。

计划由模型根据当前操作、代码、选区和上下文生成。公开内容为目标、任务说明、已知信息和待补充信息；不展示私有推理过程，不固定表名、业务 SQL 模板、工具或探索顺序。代码计划的步骤最多 16 项，可用 reasoning、retrieval、aggregation、validation；retrieval 表示后续探索意图，不是读取成功的证据。

计划不能包含 mcp_tool、工具名或 execution_policy，也不能提前交付 final_answer。它不赋予文件写入、Python 运行、SQL 查询或数据库连接权限。Agent 模式后续仍走原有可执行计划、工具授权和证据机制；离线模型模式只分析已经提供的上下文。

## Python 链路

1. 页面调用 POST `/api/v1/data-science/python/assist/plan`，使用当前文件、选区、操作、需求、模型和所选 Skills 创建解释计划。
2. 服务端复用原计划存储保存 ANALYSIS_READY 快照及 DAG，返回 planId、interpretationPlan、status=READY。
3. 页面先展示计划，再调用原 `/assist`，附带服务器返回的 planId。
4. 服务端核对计划记录、身份和请求内容，之后才请求代码解释或生成结果。

请求绑定摘要覆盖租户、用户、语言、操作、完整模型上下文与模型名称；其中包含源码、选区、需求和当前可用 Skill 内容。摘要由服务端覆盖写入计划约束，模型不能自行指定。代码、需求、模型、操作或身份变化会使旧计划不匹配；计划有效期为 30 分钟。旧客户端不传 planId 时，服务端仍先创建和校验计划，不能跳过该阶段。

解释与问答继续返回说明，不产生可应用的代码替换；生成、修复等仍使用原差异审查。响应新增 plan，计划可随该轮助手消息保留。每一轮请求创建新的计划，不将上一轮计划默认认定为本轮已经完成。

## SQL 链路

原 POST `/projects/{projectId}/generations` 在创建 Generation、Runtime 会话或提交任务之前先创建代码解释计划。计划保存为 taskRequest.toolInput.studioCodePlan，并作为已接受的分析上下文附加到冻结的请求中；历史 Generation 可恢复查看计划。

计划阶段不创建任何数据库访问授权。原有模型 / Agent 选择、Runtime 事件、元数据权限和生成期间拒绝业务 SQL 执行的边界继续生效。老版本已保存的 Generation 没有计划时保持兼容，新准备的请求必须经过计划阶段。

## 页面状态

共享 StudioCodePlan 组件展示目标、步骤和待补充信息，区分制定计划、计划就绪、分析探索、处理结束与后续失败。计划步骤不显示伪造的完成勾选，不把计划中的检查描述当作已执行的验证。

Python 在收到有效 READY 计划并完成页面更新后才发送第二个请求。SQL 在准备阶段显示“正在制定代码协作计划”，计划返回后再提交原任务。计划失败时停止后续处理，既不继续生成，也不创建 SQL Generation。

## 验证范围

后端测试覆盖实际 InterpretationPlan 解析、现有校验器与存储、DAG 生成、非法 JSON / 环路 / 工具 / 执行策略拒绝、计划请求绑定、跨身份与源码变化拒绝、模型不能伪造绑定摘要、Python 先计划后生成，以及 SQL 计划失败时不创建后续任务。

前端测试覆盖计划请求完成前没有生成请求、先展示再提交、上下文快照不随 UI 修改漂移、计划失败与无效计划阻断、每轮重新计划，以及计划卡片各状态渲染。生产构建检查两个 Studio 组件集成。

本批后端 44 项、前端 29 项相关测试通过，生产构建通过。本地日志：target/studio-code-plan-tests.log；机器可读测试汇总：target/studio-code-plan-verification.json。测试使用模型夹具；没有将本次回归当作真实模型、联机元数据或服务器部署验收。本批未部署。
