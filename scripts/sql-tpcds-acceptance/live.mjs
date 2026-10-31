/** Real, resumable acceptance. No mock routes, synthesized rows or signed caller-proof shortcuts. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const origin=process.env.TPCDS_ORIGIN,authPath=process.env.TPCDS_AUTH_FILE;
assert.ok(origin&&authPath,'Set TPCDS_ORIGIN and TPCDS_AUTH_FILE (private login response containing token/user).');
const auth=JSON.parse(fs.readFileSync(authPath)),out=path.resolve(process.env.TPCDS_OUTPUT||'target/tpcds-live-acceptance');
fs.mkdirSync(out,{recursive:true});
const save=(name,value)=>fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(value,null,2));
const load=name=>JSON.parse(fs.readFileSync(path.join(out,name+'.json')));
let state=fs.existsSync(path.join(out,'state.json'))?load('state'):{};
const id=()=>crypto.randomUUID();
async function api(route,method='GET',body,workspace='data-science'){
 const r=await fetch(origin+'/api/v1'+route,{method,headers:{Authorization:'Bearer '+auth.token,'Content-Type':'application/json','X-Workspace-Id':workspace},body:body===undefined?undefined:JSON.stringify(body)});
 const j=await r.json();if(!r.ok||j.code!==200)throw Object.assign(Error(JSON.stringify({route,status:r.status,code:j.code,message:j.message,requestId:j.requestId})),{code:j.code});return j.data;
}
const base=()=>'/data-science/sql/projects/'+state.project.id;
const pub=name=>base()+'/files/'+state[name].file.id+'/template-publications';
const schema={type:'object',additionalProperties:false,properties:{product_id:{type:'integer',minimum:1,default:1},start_date:{type:'string',format:'date'},end_date:{type:'string',format:'date'},min_quantity:{type:'integer',minimum:0,default:0},category:{type:['string','null'],default:null}},required:['start_date','end_date']};
const parameters=name=>name==='yearly'?{}:{start_date:'1999-01-01',end_date:'1999-12-31'};
const prompts={
 yearly:'为248测试数据库tpcds生成sql/tpcds_yearly.sql，无参数MySQL只读查询：限定store_sales.ss_item_sk=1，关联date_dim.d_date_sk=ss_sold_date_sk，按d_year统计sales_year、COUNT(*) sales_rows、SUM(ss_quantity) total_quantity、SUM(ss_net_paid) total_net_paid、SUM(ss_net_profit) total_net_profit、MIN/MAX(d_date) earliest_date/latest_date。按年升序LIMIT 10。注释明确单品范围。不要执行或虚构数据。',
 monthly:'生成sql/tpcds_monthly.sql，只读MySQL：tpcds.store_sales ss关联date_dim d和item i；以未加引号的{{product_id}}整数限定ss_item_sk，以{{start_date}}/{{end_date}}限定d_date含边界，以{{min_quantity}}限定ss_quantity下限，({{category}} IS NULL OR i.i_category={{category}})可空字符串。按d_year,d_moy,i_category分组，返回sale_year,sale_month,category,COUNT(*) row_count,SUM(ss_quantity) total_quantity,SUM(ss_net_paid) total_net_paid,SUM(ss_net_profit) total_net_profit，按年月品类升序LIMIT 24。所有占位符保持原样，外面不加引号。不要执行或虚构数据。'
};
const command=process.argv[2];
if(command==='setup'){
 assert.ok(!state.project,'Use another output directory for a fresh run.');
 const sources=await api('/data-science/sql/template-publications/datasources');
 state.source=sources.find(s=>s.name===(process.env.TPCDS_ASSET_NAME||'248测试数据库'));assert.ok(state.source,'Authorized data source absent');
 assert.equal(state.source.databaseType.toLowerCase(),'mysql');
 state.project=await api('/data-science/sql/projects','POST',{name:'TPCDS real chain '+new Date().toISOString(),dialect:'mysql'});save('state',state);
 const context=await api(base()+'/metadata/contexts','POST',{purpose:'QUERY',targetDialect:'mysql',bindings:[{serviceId:'chatchat-mcp-server',assetRef:state.source.id,role:'SOURCE',database:'tpcds'}]});
 const tables=await api(base()+'/metadata/contexts/'+context.discoveryId+'/operations','POST',{bindingId:context.bindings[0].bindingId,arguments:{operation:'LIST_TABLES',database:'tpcds',pageSize:100,includeColumns:true}});
 save('metadata',tables);save('context',context);
 const chosen=tables.metadata.items.filter(t=>['store_sales','date_dim','item'].includes(t.tableName));assert.equal(chosen.length,3,'Actual registered table metadata required');
 state.mapping=await api(base()+'/files','PUT',{path:'mapping/tpcds-schema.json',kind:'MAPPING',expectedVersion:0,content:JSON.stringify({source:'REGISTERED_METADATA_NOT_LIVE',database:'tpcds',tables:chosen})});
}
if(command==='generate'){
 for(const name of Object.keys(prompts)){
  if(!state[name]?.generationId){
   const gen=await api(base()+'/generations','POST',{prompt:prompts[name],dialect:'mysql',mappingIds:[state.mapping.id],referenceIds:[],action:'generate'});
   state[name]={generationId:gen.generationId};save('state',state);
  }
  if(!state[name].codingStarted){await api(base()+'/generations/'+state[name].generationId+'/coding','POST',{});state[name].codingStarted=true;save('state',state);}
 }
}
if(command==='generated'){
 for(const name of Object.keys(prompts)){
  const result=await api(base()+'/generations/'+state[name].generationId);save(name+'-generation',result);
  assert.ok(!result.outputError,'Generation output failed');
  assert.ok(result.files?.some(f=>f.path==='sql/tpcds_'+name+'.sql'),'No generated file yet; poll this command later.');
  if(state[name].file)continue;
  await api(base()+'/generations/'+state[name].generationId+'/apply','POST',{paths:['sql/tpcds_'+name+'.sql']});
  state[name].file=(await api(base()+'/files')).find(f=>f.path==='sql/tpcds_'+name+'.sql');save('state',state);
 }
}
if(command==='draft'){
 for(const name of Object.keys(prompts)){
  if(state[name].row)continue;
  state[name].operationId??=id();save('state',state);
  state[name].row=await api(pub(name),'POST',{operationId:state[name].operationId,expectedSourceVersion:state[name].file.version,title:'TPCDS real '+name,description:'Single product, actual TPCDS retail data; amount currency unspecified.',category:'business_analysis',datasourceId:state.source.id,databaseType:'mysql',parameterSchema:name==='monthly'?schema:{type:'object',properties:{},additionalProperties:false}});save('state',state);
 }
 console.log('Approve these exact codes using existing datasource template authorization; keep other configuration intact: '+Object.keys(prompts).map(n=>state[n].row.code).join(', '));
}
if(['test','publish'].includes(command)){
 for(const name of Object.keys(prompts)){
  let item=state[name];if(command==='publish')assert.equal(item.row.testEvidence?.success,true,'Actual trial required');
  item.row=await api(pub(name)+'/'+item.row.id+'/'+command,'POST',{expectedSourceVersion:item.file.version,expectedVersion:item.row.expectedVersion,testId:item.testId??=id(),parameters:parameters(name),expectedActiveReleaseId:''});
  save('state',state);save(name+'-'+command,item.row);
  assert.equal(item.row.testEvidence.success,true,'Real query failed');assert.ok(item.row.testEvidence.rowCount>0,'Empty result is not sufficient for this acceptance');
 }
}
if(command==='agent'){
 const agentId=state.agent?.id||'tpcds_acceptance_'+id().replaceAll('-','').slice(0,12);
 if(!state.agent){state.agent=await api('/agents/workshop','POST',{id:agentId,name:'TPCDS real acceptance '+agentId,defaultMode:'agent_chat',modelName:process.env.TPCDS_MODEL||'qwen3.7-plus',systemPrompt:'Use only actual authorized query records. TPCDS is retail, not financial customer data. Report missing evidence and failed execution honestly.',boundMcpServiceIds:[],boundMcpToolNames:[],boundDocumentIds:[],boundDomainSkillIds:[],toolConfigs:[],marketStatus:'draft',defaultAgent:false,workflowConfig:{enabled:true,runtimeEnvironment:'DEV',workflow:'agent_workflow',executionStrategy:{mode:'sequential',stopOnError:true,maxSteps:8,costBudget:20,latencyBudgetMs:240000,toolRetryAttempts:1,allowParallel:false},steps:[]}});save('state',state);}
 for(const name of Object.keys(prompts))await api('/tool-publications/capabilities/agents/'+agentId+'/tools/'+state[name].row.publication.toolName,'PUT',{});
 state.agent=await api('/agents/workshop/'+agentId+'/publish','POST',{});
}
if(command==='ask'){
 assert.ok(!state.exploration,'Task exists; poll without submitting another one.');
 const p='/data-science/explorations';
 let e=await api(p,'POST',{title:'TPCDS actual SQL and Python acceptance'},'data-exploration');
 e=await api(p+'/'+e.id+'/nodes','POST',{version:e.version,agentId:state.agent.id,fileIds:[],question:process.env.TPCDS_QUESTION||'请分析248测试数据库tpcds中商品主键1在1999-01-01至1999-12-31的门店销售情况，月度最低购买数量0、品类不限，将年度汇总与逐月明细进行勾稽对账，解释差异，输出Python计算的对账表和月度净收款、利润图报表。金额单位按数据来源声明，不要推断。'},'data-exploration');
 state.exploration={id:e.id,nodeId:e.nodes.at(-1).id};save('state',state);
 e=await api(p+'/'+e.id+'/nodes/'+state.exploration.nodeId+'/execute','POST',{},'data-exploration');save('exploration',e);
}
if(command==='poll'){
 const e=state.exploration,p='/data-science/explorations/'+e.id+'/nodes/'+e.nodeId;
 const value=await api(p+'/status','GET',undefined,'data-exploration');save('exploration',value);
 const receipt=value.nodes.find(n=>n.id===e.nodeId).receipt;console.log(JSON.stringify({taskId:receipt.agentTaskId,status:receipt.status,terminal:receipt.terminal}));
 const events=await api('/agent/tasks/'+receipt.agentTaskId+'/events?tenantId='+auth.user.tenantId+'&limit=1000');save('agent-events',events);
 const runs=[...new Set(events.map(v=>{try{return JSON.parse(v.payload).runId}catch{return null}}).filter(Boolean))];
 for(const run of runs){
  try{save('observations-'+run,await api('/agent/runtime/runs/'+run+'/observations?limit=100'));}
  catch(error){if(receipt.terminal||error.code!==404)throw error;console.log('Runtime run not registered yet; poll again after planning.');}
 }
 if(receipt.terminal){
  assert.equal(receipt.resultAvailable,true,'No analysis result');assert.equal(receipt.visualReport?.schemaVersion,'python_visual_report.v1','A text answer or successful tool listing is not Python acceptance');
  const execution=receipt.visualReport.execution;assert.ok(execution?.operationId&&execution.codeSha256);assert.equal(execution.executorStopped,true);
  assert.ok(receipt.visualReport.charts?.length>0,'Actual chart artifacts required');
  const executions=[];
  for(const run of runs){const monitor=await api('/agent/runtime/runs/'+run+'/coding');save('coding-'+run,monitor);executions.push(...monitor.tasks.flatMap(t=>t.executions||[]));}
  const actual=executions.find(e=>e.executionId===execution.operationId);
  assert.ok(actual,'Missing durable Python execution feedback');assert.equal(actual.status,'SUCCEEDED');assert.equal(actual.exitCode,0);
  assert.equal(actual.stage,'VALIDATED');assert.equal(actual.resultAvailable,true);assert.ok(!actual.error);
  assert.equal(receipt.visualReport.artifactValidationStatus,'PASSED');
  // Execution integrity and business findings have different meanings. Preserve
  // FAILED/INCONCLUSIVE business checks (e.g. missing months); never certify them
  // from exit code alone. The independent JDBC reconciliation is a separate gate.
  save('business-validation',{runtime:actual.validationStatus,report:receipt.visualReport.validation});
  assert.equal(actual.codeHash,execution.codeSha256);
  for(const artifact of [execution.codeArtifact,...receipt.visualReport.charts.map(c=>c.artifact)]){
   assert.ok(artifact?.fileId,'Missing artifact');const r=await fetch(origin+'/api/v1/interactions/user-assets/'+artifact.fileId+'/content',{headers:{Authorization:'Bearer '+auth.token}});assert.equal(r.status,200);
   const bytes=Buffer.from(await r.arrayBuffer());const hash=crypto.createHash('sha256').update(bytes).digest('hex');assert.equal(hash,artifact.sha256??execution.codeSha256);fs.writeFileSync(path.join(out,artifact.fileId+'.artifact'),bytes);
  }
  console.log('Actual Python execution and artifact bytes verified. Independently reconcile the returned rows and computed totals before marking E17 passed.');
 }
}
save('state',state);
