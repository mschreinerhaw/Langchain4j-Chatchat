import fs from 'node:fs';import crypto from 'node:crypto';
const op=process.argv[2],origin=process.env.STUDIO_ORIGIN,authFile=process.env.STUDIO_AUTH_FILE;
if(!origin||!authFile)throw Error('Set STUDIO_ORIGIN and STUDIO_AUTH_FILE');
const auth=JSON.parse(fs.readFileSync(authFile));
async function api(path,method='GET',data){const response=await fetch(origin+'/api/v1'+path,{method,headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science','Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});const value=await response.json();if(!response.ok||value.code!==200)throw Error(JSON.stringify({path,status:response.status,...value}));return value.data;}
const folder=(process.env.STUDIO_OUTPUT||'target/studio-python-acceptance').replace(/[\\/]$/,'')+'/';fs.mkdirSync(folder,{recursive:true});const save=(n,x)=>fs.writeFileSync(folder+n+'.json',JSON.stringify(x,null,2)),read=n=>JSON.parse(fs.readFileSync(folder+n+'.json'));
if(!['generate','poll','refine','save','trial','publish','connection','agent','ask','poll-agent'].includes(op))throw Error('Use generate, poll, refine, save, trial, publish, connection, agent, ask or poll-agent');
let config;
if(fs.existsSync(folder+'config.json'))config=read('config');else{const tag=process.env.STUDIO_TAG||String(Date.now());if(!/^[A-Za-z0-9_]{1,32}$/.test(tag))throw Error('Invalid STUDIO_TAG');config={domain:process.env.STUDIO_DOMAIN||'tpcds_python_'+tag,agentId:process.env.STUDIO_AGENT_ID||'tpcds_studio_'+tag,scriptFileName:'tpcds_reconcile_'+tag+'.py',templateName:'TPCDS门店销售年度月度对账'+tag};save('config',config);}
const domain=config.domain;
const base='/data-science/python';
if(op==='generate'){
 const source='import json\nimport os\n';
 const request={action:'generate',sourceCode:source,modelName:'deepseek-v4-flash',skillIds:[],prompt:'开发一个可以在Python Studio试运行、发布为可复用Agent数据服务的tpcds年度与月度门店销售对账程序。完整生成tpcds_reconcile.py。只读CHATCHAT_INPUT_JSON环境变量JSON，不联网、不执行SQL、不读主机文件。输入annual_rows数组为真实SQL年度结果(sales_year,sales_rows,total_quantity,total_net_paid,total_net_profit,earliest_date,latest_date)，monthly_rows数组为SQL月度结果(sale_year,sale_month,category,row_count,total_quantity,total_net_paid,total_net_profit)，sales_year整数默认1999。按sales_year筛选两个数组并用Decimal精确汇总，返回年度金额、月度金额、年度减月度差额、数量、月份覆盖、缺失月份和月度明细。金额输出十进制字符串，不假设币种或量纲。不要硬编码任何数据行或计算结果。验证行结构、字段、重复年度/月份品类键和非法数值，缺少月份不补零、不插值，多个品类要先按月聚合；缺少年度或月度实际输入应报错且非零退出。仅stdout输出一个JSON对象schemaVersion=tpcds_reconciliation.v1，metrics为对象包含annual_net_paid,monthly_net_paid,diff_net_paid,annual_net_profit,monthly_net_profit,diff_net_profit,annual_quantity,monthly_quantity,months_covered；monthly为逐月实际汇总数组；missing_months为1到12未覆盖月份。另外输出chart为一个合法Plotly figure对象data/layout，两个scatter lines+markers轨迹分别净收款、利润，x为1到12月份，y采用数值且缺失月份null，金额单位未声明、零基准线与负值保留。差额只说明不同筛选口径，不能直接断言数据库错误。解释利润差额率负分母含义（如计算该指标）。用__main__调用main，异常输出到stderr并退出非零。请按需选择Python开发/数据分析技能；若需要MCP语义可按需读取Microsoft MCP Builder，不要加载Azure技能。无需执行示例数据，也不要凭编译结果声称运行成功。'};
 request.modelName=process.env.STUDIO_MODEL||'qwen3.7-plus';
 request.prompt+='\n实现保持简洁，代码不超过120行，不生成说明文档或测试文件，优先完整交付本程序。';
 const plan=await api(base+'/assist/plan','POST',request);save('plan',plan);request.planId=plan.planId??plan.id;save('request',request);
 const task=await api(base+'/assist/coding','POST',{request,requestId:'tpcds-python-service-'+crypto.randomUUID(),entryPath:'tpcds_reconcile.py',files:[{path:'tpcds_reconcile.py',content:source}]});save('coding-task',task);console.log({taskId:task.taskId,status:task.status});
}
if(op==='poll'){
 const task=read('coding-task'),r=await api(base+'/assist/coding/'+task.taskId);save('coding-result',r);console.log({status:r.status,keys:Object.keys(r),outputKeys:r.output?Object.keys(r.output):null,error:r.outcome?.error});
}
if(op==='refine'){
 const previous=read('coding-result');save('generated-before-review',previous);
 const source=previous.output.files.find(f=>f.path==='tpcds_reconcile.py').content;
 const request={action:'fix',sourceCode:source,modelName:'qwen3.7-plus',skillIds:[],prompt:'审查并修复当前tpcds对账程序：1. Decimal数值必须is_finite，拒绝NaN和Infinity。2. sales_year、sale_year、sales_year输入及sale_month需接受整数与整数字符串但拒绝小数/布尔；重复键按规范化后的整数年月判定，月份必须1到12。3. metrics.months_covered应为覆盖月份个数而非数组，可另保留covered_months列表。4. json.loads使用parse_float=Decimal保留金额精度。5. 图表轴和标题明确金额单位源端未声明，缺失月份为null且connectgaps=false，不补零。6. 输出limitations说明不同模板筛选口径，差额不能直接认定数据库错误。仍只从CHATCHAT_INPUT_JSON读取实际数组，不联网，不写死任何行或结果。保持现有schemaVersion和字段名称，仅修复必要内容，完整交付tpcds_reconcile.py，不附测试文件，代码保持简洁不超过150行。'};
 const plan=await api(base+'/assist/plan','POST',request);save('review-plan',plan);request.planId=plan.planId??plan.id;save('review-request',request);
 const task=await api(base+'/assist/coding','POST',{request,requestId:'tpcds-python-review-'+crypto.randomUUID(),entryPath:'tpcds_reconcile.py',files:[{path:'tpcds_reconcile.py',content:source}]});save('coding-task',task);console.log({taskId:task.taskId,status:task.status});
}
if(op==='save'){
 const r=read('coding-result');if(r.status!=='COMPLETED')throw Error('No verified Coding delivery');
 const file=r.output.files.find(f=>f.path==='tpcds_reconcile.py');fs.writeFileSync(folder+'tpcds_reconcile.py',file.content);
 const w=await api(base+'/workbench');const asset=w.assets.find(a=>a.status==='READY'&&a.id===w.defaultAssetId)||w.assets.find(a=>a.status==='READY');if(!asset)throw Error('Owned ready environment absent');save('asset',asset);
 const script=await api(base+'/scripts','POST',{assetId:asset.id,fileName:config.scriptFileName,title:'TPCDS SQL结果对账数据服务',sourceCode:file.content,expectedVersion:0});save('script',script);
 const schema={type:'object',properties:{annual_rows:{type:'array',minItems:1,maxItems:100,items:{type:'object'},description:'年度SQL服务实际返回的完整rows，不是工具列表或说明'},monthly_rows:{type:'array',minItems:1,maxItems:100,items:{type:'object'},description:'月度SQL服务实际返回的完整rows，保留实际数值及字段'},sales_year:{type:'integer',default:1999,minimum:1900,maximum:2100}},required:['annual_rows','monthly_rows'],additionalProperties:false};save('schema',schema);
 if(!process.env.STUDIO_JDBC_FILE)throw Error('Set STUDIO_JDBC_FILE to actual independently executed JDBC baselines');const jdbc=JSON.parse(fs.readFileSync(process.env.STUDIO_JDBC_FILE)).baselines;for(const b of [jdbc.yearly,jdbc.monthly])if(b.source!=='DIRECT_MYSQL_JDBC'||!b.readOnly)throw Error('Actual read-only JDBC baseline required');const params={annual_rows:jdbc.yearly.rows,monthly_rows:jdbc.monthly.rows,sales_year:1999};save('trial-inputs',params);
 console.log({scriptId:script.id,version:script.currentVersion,sourceSha256:crypto.createHash('sha256').update(file.content).digest('hex'),assetId:asset.id});
}
if(op==='trial'){
 const script=read('script'),schema=read('schema'),r=await api(base+'/scripts/'+script.id+'/execute','POST',{parameters:read('trial-inputs'),inputSchema:JSON.stringify(schema)});save('trial',r);console.log({id:r.id,status:r.status,exit:r.exitCode,duration:r.durationMs,stderr:r.stderr,stdout:r.stdout?.slice(0,850)});
}
if(op==='publish'){
 const script=read('script'),trial=read('trial');if(trial.status!=='SUCCEEDED'||trial.exitCode!==0)throw Error('Actual trial not successful');
 const request={templateName:config.templateName,scenario:'对248测试数据库tpcds的真实SQL年度与月度结果进行精确金额对账和趋势分析',description:'接收年度服务完整annual_rows与月度服务完整monthly_rows，使用Decimal计算年度净收款、利润、月度合计、差额和数量，输出覆盖月份、缺失月份及Plotly趋势数据。CHATCHAT_INPUT_JSON；stdout为tpcds_reconciliation.v1 JSON。不能把模板发现当作查询结果，不推断币种，不把缺少月份补零或将筛选差额认定数据库错误。',keywords:'tpcds,销售,年度,月度,对账,SQL,Python,net_paid,net_profit',domain:'retail_analysis',version:'1.0.0',inputSchema:JSON.stringify(read('schema')),outputSchema:JSON.stringify({type:'object',properties:{schemaVersion:{type:'string'},metrics:{type:'object'},monthly:{type:'array'},chart:{type:'object'}},required:['schemaVersion','metrics','monthly','chart']})};save('publish-request',request);const result=await api(base+'/scripts/'+script.id+'/publish','POST',request);save('template',result);console.log({id:result.id,status:result.status,runtime:result.runtimeStatus,sync:result.mcpSyncStatus,toolName:result.toolName});
}
if(op==='connection'){
 const auth=JSON.parse(fs.readFileSync(authFile)),template=read('template');const role=auth.user.roleIds[0];
 const catalog=await api('/tool-publications/catalog/templates?roleId='+role+'&parentToolName=python_analysis_query');save('python-catalog',catalog);
 const candidate=catalog.find(t=>t.templateId===template.id);if(!candidate)throw Error('Published Python template not authorized in catalog');
 let draft=fs.existsSync(folder+'connection-validate.json')?read('connection-validate'):await api('/tool-publications','POST',{title:'TPCDS Python对账服务20261101',kind:'CONTROLLED_TEMPLATE',description:'发现已验证的TPCDS年度月度销售对账Python模板。该连接只发现模板；必须通过Runtime Python执行器取得真实执行回执。',definition:{parentToolName:'python_analysis_query',roleId:role,subjectType:'USER',userId:auth.user.id,domainCode:domain,templateKeys:[candidate.key],enabled:false},inputSchema:{type:'object',properties:{query:{type:'string'}},required:['query'],additionalProperties:false},outputSchema:{type:'object',properties:{}}});save('connection-draft',draft);
 for(const action of (draft.state==='VALIDATED'?['test','publish']:['validate','test','publish'])){draft=await api('/tool-publications/'+draft.id+'/'+action,'POST',action==='test'?{input:{query:'TPCDS 年度月度销售对账 Python'}}:{});save('connection-'+action,draft);}
 console.log({id:draft.id,state:draft.state,definition:draft.definitionJson,output:draft.testEvidenceJson});
}
if(op==='agent'){
 const state=JSON.parse(fs.readFileSync(process.env.STUDIO_SQL_STATE||'target/tpcds-live-acceptance/state.json')),connection=read('connection-publish');
 const tools=await api('/mcp/runtime/tools?serviceId=chatchat-mcp-server');const pyName=(Array.isArray(tools)?tools:tools.tools).find(t=>t.remoteToolName===domain+'_template_query')?.localToolName;if(!pyName)throw Error('Published discovery absent from actual MCP catalog');
 let agent=await api('/agents/workshop','POST',{id:config.agentId,name:'TPCDS SQL与Python模板验收',defaultMode:'agent_chat',modelName:process.env.STUDIO_AGENT_MODEL||'qwen3.6-plus',systemPrompt:'使用已授权发布的数据服务完成销售对账。优先获取真实SQL年度、月度结果，再通过发现的Python对账模板计算，禁止以手工推测替代实际Python执行。年度与月度筛选不同，不把差额直接判定数据库错误。金额单位未声明。需要图表时使用真实结果。不要自行构造数据库明细或硬编码统计量。对需要上游工具结果的模板，必须先完成数据查询，再进行模板发现和选择：发现节点也必须依赖提供数据的上游查询节点。当前模板选择阶段会校验必填参数，不允许在真实结果返回前提前选择或填空数组。把实际返回记录绑定为annual_rows、monthly_rows，sales_year取用户指定年份。不要在数据源服务失败时用临时编程代替已发布模板。 本场景仅使用经过试执行验证的只读SQL统计服务，以及已发布、无网络访问、无持久副作用的Python数据处理模板。没有写库、外发、修改权限或运行任意用户代码。请依据已发布工具契约和实际副作用判断整体风险，不要仅因工具名称含execute就把只读统计判断成修改数据库。仍严格遵守工具权限、Schema、数据来源和运行时风险门禁。',boundMcpServiceIds:[],boundMcpToolNames:[pyName],boundDocumentIds:[],boundDomainSkillIds:[],toolConfigs:[pyName].map(toolName=>({toolName,serviceId:'chatchat-mcp-server',enabled:true,callWeight:1})),marketStatus:'draft',defaultAgent:false,workflowConfig:{enabled:true,runtimeEnvironment:'DEV',workflow:'agent_workflow',executionStrategy:{mode:'sequential',stopOnError:true,maxSteps:12,costBudget:30,latencyBudgetMs:240000,toolRetryAttempts:1,allowParallel:false},steps:[]}});save('agent',agent);
 for(const name of ['yearly','monthly'])await api('/tool-publications/capabilities/agents/'+agent.id+'/tools/'+state[name].row.publication.toolName,'PUT',{});
 agent=await api('/agents/workshop/'+agent.id+'/publish','POST',{});save('agent',agent);console.log({id:agent.id,tools:agent.boundMcpToolNames});
}
if(op==='ask'){
 const task=await api('/agent/tasks','POST',{agentId:read('agent').id,mode:'agent_chat',query:'请通过248测试数据库tpcds已发布的年度与月度门店销售数据服务，分析商品主键1在1999-01-01至1999-12-31的销售对账。月度最低购买数量0，品类不限。使用已发布的TPCDS门店销售年度月度对账Python服务处理实际查询返回的完整记录，返回程序实际计算的净收款、利润、数量、口径差额、覆盖月份与月度趋势数据，并说明限制。',idempotencyKey:'studio-sql-python-'+crypto.randomUUID(),historyWindow:0});save('agent-task',task);console.log(task);
}
if(op==='poll-agent'){
 const auth=JSON.parse(fs.readFileSync(authFile)),task=read('agent-task');
 const r=await api('/agent/tasks/'+task.taskId+'?tenantId='+auth.user.tenantId);save('agent-result',r);
 const events=await api('/agent/tasks/'+task.taskId+'/events?tenantId='+auth.user.tenantId+'&limit=1000');save('agent-events',events);
 console.log({taskId:task.taskId,state:r.canonicalState,answer:r.answerSummary?.slice(0,900),events:events.length});
 const runs=[...new Set(events.map(e=>{try{return JSON.parse(e.payload).runId}catch{return null}}).filter(Boolean))];
 for(const run of runs){try{const rows=await api('/agent/runtime/runs/'+run+'/observations?limit=1000');save('observations-'+run,rows);console.log({run,observations:rows.length,tools:rows.filter(o=>o.metadata?.stepOutput).map(o=>({name:o.source,preview:o.contentPreview?.slice(0,150),keys:Object.keys(o.metadata.stepOutput)}))});}catch(e){if(r.canonicalState==='FAILED')throw e;}}
}
