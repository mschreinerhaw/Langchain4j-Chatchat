/** Real authenticated Runtime tasks; no synthetic source rows, model or Python executor. */
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {randomUUID,createHash} from 'node:crypto';
const origin=process.env.REPORT_ORIGIN||'http://192.168.195.221:18100';
const cases={
 A:{agent:'financial-news-agent',mode:'agent_chat',visual:false,query:'分析华星科技相关金融资讯，形成一份分析报告。请先核对目标实体，不要把华是科技等近似名称当作华星科技。筛选相关证据，去重并明确资料时间和信息不足之处。'},
 A2:{agent:'financial-news-agent',mode:'agent_chat',visual:false,query:'分析华是科技（301218.SZ）相关金融资讯并解释已知亏损信息，形成一份有证据支持的分析报告。核对实体、发布时间、重复记录与信息不足，不要把行情或新闻清单作为最终报告，也不要编造亏损归因。'},
 B:{agent:'resource_exploration_acceptance_20261104',mode:'role_chat',visual:true,query:'分析华是科技（301218.SZ）已采集的真实行情，生成图文分析报告。自主发现已有授权数据，核对证券身份和数据时间、字段含义及单位，选择有意义的图表并执行 Python 绘图，将真实图表与正文一起交付。结论仅限实际数据覆盖范围，不推断缺失的盈利或历史分位。'},
 C:{agent:'financial_indicator',mode:'agent_chat',visual:true,query:'查询客户号 070200046604 的交易和资产、盈亏情况；总结交易偏好。请形成分析报告，需要的图表必须来自实际授权数据；没有的历史趋势或指标口径请明确说明，保留数据访问权限边界。'},
 D:{agent:'resource_exploration_acceptance_20261104',mode:'role_chat',visual:true,query:'仅基于本题提供的定性文字形成研究报告：某研究团队认为长期决策需要核对证据、记录不确定性并定期复盘。说明这种方法的适用条件和局限。此题没有数值观察，不检索或编造数值，不需要为图文偏好强行绘图。'},
};
const id=process.argv[2];
const custom=process.env.REPORT_REQUEST_FILE?JSON.parse(fs.readFileSync(process.env.REPORT_REQUEST_FILE,'utf8')):null;
assert.ok(custom||cases[id],'Choose A, A2, B, C or D, or supply REPORT_REQUEST_FILE');
const spec=custom?{agent:custom.skillId,mode:custom.mode,visual:custom.toolInput?.reportMode==='visual',query:custom.query}:cases[id],out=process.env.REPORT_OUTPUT||`target/cline-report-live/${id}`;
fs.mkdirSync(out,{recursive:true});
const save=(name,data)=>fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(data??null,null,2));
const login=process.env.REPORT_AUTH_FILE?null:await fetch(origin+'/api/v1/enterprise/auth/login',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({username:process.env.REPORT_USER||'admin',password:process.env.REPORT_PASSWORD})});
const auth=process.env.REPORT_AUTH_FILE?JSON.parse(fs.readFileSync(process.env.REPORT_AUTH_FILE,'utf8')):(await login.json()).data;assert.ok(auth?.token,'Login failed');
fs.writeFileSync(path.join(out,'auth.private.json'),JSON.stringify(auth));
async function api(route,body){for(let attempt=0;;attempt++){try{const r=await fetch(origin+'/api/v1'+route,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+auth.token,'content-type':'application/json','X-Workspace-Id':'data-science'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(60000)});const j=await r.json();assert.equal(r.status,200,route+': '+j.message);assert.ok([200,201,202].includes(j.code),route+': '+j.message);return j.data;}catch(error){if(attempt>=2||body!==undefined)throw error;await new Promise(resolve=>setTimeout(resolve,1000*(attempt+1)));}}}
let submitted;
const prior=path.join(out,'submission.json');
if(fs.existsSync(prior))submitted=JSON.parse(fs.readFileSync(prior));
else {
 const request={mode:spec.mode,skillId:spec.agent,sessionId:randomUUID(),requestSource:'cline-report-skills-live-acceptance',idempotencyKey:randomUUID(),query:spec.query,toolInput:{resourceExplorationApproved:true,reportMode:spec.visual?'visual':'off'},...(custom||{})};
 if(process.env.REPORT_MODEL)request.modelName=process.env.REPORT_MODEL;
 save('request',request);submitted=await api('/agent/tasks',request);save('submission',submitted);
}
console.log(JSON.stringify({case:id,taskId:submitted.taskId,event:'SUBMITTED'}));
const query='?tenantId='+encodeURIComponent(auth.user.tenantId),start=Date.now();
let task;
for(;;){
 task=await api('/agent/tasks/'+submitted.taskId+query);save('task',task);
 console.log(JSON.stringify({case:id,taskId:task.taskId,status:task.status,state:task.canonicalState,elapsedSeconds:Math.round((Date.now()-start)/1000)}));
 if(['SUCCEEDED','PARTIAL','FAILED','CANCELLED','REJECTED'].includes(task.canonicalState))break;
 if(Date.now()-start>30*60*1000)throw Error('Observation deadline exceeded; task retained, no false cancellation or success');
 await new Promise(resolve=>setTimeout(resolve,15000));
}
const result=await api('/agent/tasks/'+task.taskId+'/result'+query);save('result',result);
let payload=result?.payload;if(typeof payload==='string')payload=JSON.parse(payload);save('response',payload);
const coding=task.attemptId?await api('/agent/runtime/runs/'+task.attemptId+'/coding'):null;save('coding',coding);
const events=[];let cursor=0;
for(let i=0;i<30;i++){const batch=await api('/agent/tasks/'+task.taskId+'/events'+query+'&limit=200&afterSequence='+cursor);if(!batch?.length)break;events.push(...batch);const next=Math.max(...batch.map(e=>Number(e.sequence)||0));if(next<=cursor)break;cursor=next;if(batch.length<200)break;}save('events',events);
const report=payload?.answer||task.answerSummary||'';fs.writeFileSync(path.join(out,'report.md'),report);
const executions=(coding?.tasks||[]).flatMap(t=>t.executions||[]),receipts=(coding?.tasks||[]).flatMap(t=>t.modelReceipts||[]);
const summary={case:id,taskId:task.taskId,attemptId:task.attemptId,canonicalState:task.canonicalState,status:task.status,agentId:spec.agent,requestedVisual:spec.visual,answerCharacters:report.length,rawRecordCards:(report.match(/记录\s*\d+|\*\*Record\s*\d+/g)||[]).length,reportSha256:createHash('sha256').update(report).digest('hex'),latencyMs:payload?.latencyMs??null,codeExecutions:executions.map(e=>({executionId:e.executionId,status:e.status,stage:e.stage,exitCode:e.exitCode,durationMs:e.durationMs,codeHash:e.codeHash,artifactRefs:e.artifactRefs,manifestHash:e.manifestHash,validationStatus:e.validationStatus,inputFiles:e.inputFiles})),modelCalls:receipts.map(r=>({selectedModel:r.selectedModel,durationMs:r.durationMs,usage:r.usage,state:r.state})),artifact:payload?.uiArtifact,eventCount:events.length,syntheticSources:false};
save('summary',summary);console.log(JSON.stringify({event:'CAPTURED',case:id,state:summary.canonicalState,reportCharacters:report.length,pythonExecutions:executions.length,modelCalls:receipts.length,output:out}));
assert.ok(['SUCCEEDED','PARTIAL'].includes(task.canonicalState),'Task did not publish a supported report');
