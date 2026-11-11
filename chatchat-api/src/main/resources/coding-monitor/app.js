'use strict';
const candidate=new URLSearchParams(location.search).get('diagnostics')==='true';
const $=id=>document.getElementById(id),root=candidate?'/api/v1/coding-node-diagnostics':'/api/v1/coding-monitor',storage='runtime-coding-monitor-session';
const accountLabel=document.querySelector('.top .muted');
if($('account').parentElement!==accountLabel)accountLabel.append(document.createTextNode(' \u00b7 '),$('account'));
// Keep older cached page shells compatible during a container upgrade.
for(const [content,id,title] of [['timeline','timeline-panel','执行时间线'],['files','artifacts-panel','交付产物']]){
 const section=$(content).closest('.panel');if(section.tagName==='DETAILS')continue;
 const panel=document.createElement('details'),summary=textHeading(title);panel.id=id;panel.className='panel fold';section.replaceWith(panel);panel.append(summary,...section.childNodes);panel.querySelector('h2')?.remove();
}
function textHeading(title){const summary=document.createElement('summary');summary.textContent=title;return summary;}
const labels={SEALED_PENDING_VALIDATION:"Native sealed; awaiting acceptance",CREATED:'已创建',RUNNING:'执行中',COMPLETED:'已完成',PARTIAL:'部分完成',FAILED:'失败',CANCELLED:'已取消',BUDGET_EXCEEDED:'超时 / 资源限制',TASK_STARTED:'任务开始',MODEL_REQUEST_STARTED:'模型请求',MODEL_REQUEST_COMPLETED:'模型回执',TOOL_STARTED:'工具开始',TOOL_COMPLETED:'工具完成',FILE_CHANGED:'文件修改',COMMAND_STARTED:'命令执行',COMMAND_COMPLETED:'执行回执',ERROR_OCCURRED:'执行失败',REPAIR_ATTEMPTED:'修复证据',ARTIFACT_UPLOADED:'成果上传',DELIVERY_VALIDATED:'Java 验收',TASK_COMPLETED:'任务完成',TASK_FAILED:'任务失败',TASK_CANCELLED:'任务取消',OBSERVATION:'执行记录'};
let token=sessionStorage.getItem(storage)||'',page=0,total=0,selected=null,events=new Map(),cursor=0,stream=null,refreshTimer=null,objectUrl=null;
const text=(tag,value,cls)=>{const n=document.createElement(tag);n.textContent=value??'—';if(cls)n.className=cls;return n;};
const date=value=>{const t=typeof value==='number'?value:Date.parse(value);return Number.isFinite(t)?new Date(t).toLocaleString('zh-CN',{hour12:false}):'—';};
const number=value=>typeof value==='number'&&Number.isFinite(value)?value.toLocaleString('zh-CN'):'未提供';
const duration=ms=>typeof ms==='number'&&Number.isFinite(ms)?ms>=60000?`${Math.floor(ms/60000)} 分 ${Math.floor(ms%60000/1000)} 秒`:`${(ms/1000).toFixed(1)} 秒`:'未提供';
const elapsed=row=>{const start=Date.parse(row.startedAt||row.createdAt),end=row.endedAt?Date.parse(row.endedAt):Date.now();return Number.isFinite(start)&&Number.isFinite(end)?duration(Math.max(0,end-start)):'—';};
const notice=message=>{$('notice').textContent=message||'';};
function signOut(message){token='';sessionStorage.removeItem(storage);selected=null;stopStream();$('login').hidden=false;$('list').hidden=true;$('detail').hidden=true;$('logout').hidden=true;$('account').textContent='';notice(message);}
async function request(path,{body,raw=false}={}){
 const response=await fetch(path,{method:body===undefined?'GET':'POST',headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body===undefined?{}:{'Content-Type':'application/json'})},body:body===undefined?undefined:JSON.stringify(body),cache:'no-store'});
 if(response.status===401){signOut('登录状态已失效，请重新认证。');throw Error('请重新登录');}
 if(raw){if(!response.ok)throw Error(response.status===403?'无权访问此文件':`读取失败 (${response.status})`);return response;}
 const value=await response.json();if(!response.ok||value.code&&value.code!==200)throw Error(value.message||`请求失败 (${response.status})`);return value.data??value;
}
async function authenticate(){const user=await request('/api/v1/enterprise/auth/me');$('account').textContent=user.displayName||user.username||'已登录';$('login').hidden=true;$('logout').hidden=false;await showList();}
$('login-form').onsubmit=async event=>{event.preventDefault();notice('');const form=event.currentTarget;try{const user=await request('/api/v1/enterprise/auth/login',{body:{username:form.username.value,password:form.password.value}});form.password.value='';if(!user.token)throw Error('Java 服务未返回登录凭据');token=user.token;sessionStorage.setItem(storage,token);await authenticate();}catch(error){notice(error.message);}};
$('logout').onclick=()=>signOut('已退出监控页面。');
function badge(value){return text('span',labels[value]||value,'badge '+value);}
function stopStream(){if(stream)stream.abort();stream=null;clearTimeout(refreshTimer);}
async function showList(){selected=null;stopStream();$('detail').hidden=true;$('list').hidden=false;await loadList();}
$('back').onclick=()=>showList().catch(error=>notice(error.message));
$('filters').onsubmit=event=>{event.preventDefault();page=0;loadList().catch(error=>notice(error.message));};
$('refresh').onclick=()=>loadList().catch(error=>notice(error.message));
$('previous').onclick=()=>{page=Math.max(0,page-1);loadList().catch(error=>notice(error.message));};
$('next').onclick=()=>{page++;loadList().catch(error=>notice(error.message));};
async function loadList(){
 if(!token||selected)return;const filter=$('filters'),params=new URLSearchParams({page:String(page),size:'20',name:filter.elements.namedItem('name').value,status:filter.status.value});
 if(filter.from.value)params.set('from',String(new Date(filter.from.value).getTime()));if(filter.to.value)params.set('to',String(new Date(filter.to.value).getTime()));
 const result=await request(root+'/tasks?'+params);if(selected)return;total=result.total;$('rows').replaceChildren();
 for(const row of result.items){const tr=document.createElement('tr'),title=document.createElement('td'),link=text('button',row.name,'link');link.onclick=()=>openTask(row.runId,row.taskId).catch(error=>notice(error.message));title.append(link,text('small',row.taskId));tr.append(title);
  const times=document.createElement('td');times.append(text('span',date(row.createdAt)),text('small','开始 '+date(row.startedAt)));tr.append(times);
  const state=document.createElement('td');state.append(badge(row.status),text('small',row.currentAction));tr.append(state,text('td',row.lastError||'—'),text('td',number(row.modelRequests)),text('td',row.tokens==null&&row.confirmedTokenLowerBound>0?'Confirmed '+number(row.confirmedTokenLowerBound):number(row.tokens)),text('td',elapsed(row)));$('rows').append(tr);}
 $('empty').hidden=result.items.length>0;$('page').textContent=`第 ${page+1} 页 · 共 ${total} 个任务`;$('previous').disabled=page===0;$('next').disabled=(page+1)*20>=total;
 notice(result.projectionLagging?'监控查询投影正在补读；执行仍由原任务日志控制。':'');
}
function route(suffix=''){return root+'/tasks/'+encodeURIComponent(selected.runId)+'/'+encodeURIComponent(selected.taskId)+suffix;}
async function openTask(runId,taskId){
 stopStream();selected={runId,taskId};events=new Map();cursor=0;$('list').hidden=true;$('detail').hidden=false;notice('');
 $('timeline-panel').open=false;$('artifacts-panel').open=false;
 await loadDetail();if(!selected)return;await loadEvents(Math.max(0,(selected.outcome.lastEventSequence||0)-64));await loadFiles();startStream();
}
function facts(values){const dl=document.createElement('dl');dl.className='facts';for(const [name,value]of values){const div=document.createElement('div');div.append(text('dt',name),text('dd',value));dl.append(div);}return dl;}
async function loadDetail(){
 if(!selected)return;const key=selected.runId+'\0'+selected.taskId,result=await request(route());if(!selected||key!==selected.runId+'\0'+selected.taskId)return;
 selected={...selected,...result};const out=result.outcome;$('overview').replaceChildren(text('h1',result.name),badge(out.status),facts([['Task ID',result.taskId],['Run ID',result.runId],['Cline Session',result.sessionId||'未建立'],['创建时间',date(out.createdAt)],['启动时间',date(out.startedAt)],['已执行时间',elapsed(out)],['Java 验收',out.validationStatus||'NOT_RUN']]));
 if(candidate&&typeof out.fixtureValidationPassed==='boolean')$('overview').append(facts([['Independent fixture verification',out.fixtureValidationPassed?'PASSED':'FAILED']]));
 renderModels(result.modelReceipts);renderExecutions(result.executions);renderRepairs();
}
async function loadEvents(after=0){const data=await request(route('/events')+'?afterSequence='+after+'&limit=128');mergeEvents(data.events);}
function mergeEvents(rows){for(const row of rows||[]){if(!Number.isSafeInteger(row.sequence))continue;events.set(row.sequence,row);cursor=Math.max(cursor,row.sequence);}if(events.size>2000){const keys=[...events.keys()].sort((a,b)=>a-b);for(const key of keys.slice(0,events.size-2000))events.delete(key);}renderTimeline();renderRepairs();}
function detailButton(assetId,label='查看原始回执'){const button=text('button',label);button.onclick=()=>preview(assetId,label).catch(error=>notice(error.message));return button;}
function renderTimeline(){
 const fragment=document.createDocumentFragment(),rows=[...events.values()].sort((a,b)=>a.sequence-b.sequence);
 for(const row of rows){const item=document.createElement('article');item.className='event';item.append(text('time',date(row.occurredAt)));const body=document.createElement('div');body.append(text('strong',labels[row.type]||row.type),text('p',row.summary||row.nativeEventType));
  const details=document.createElement('details');details.append(text('summary',`事件 #${row.sequence} · ${row.nativeEventType||'Runtime'}`));details.append(text('pre',JSON.stringify(row,null,2)));if(row.detailAssetId)details.append(detailButton(row.detailAssetId));body.append(details);item.append(body);fragment.append(item);}
 $('timeline').replaceChildren(fragment);if(!rows.length)$('timeline').append(text('p','尚未收到执行事件。'));$('history').disabled=rows.length===0||rows[0].sequence<=1;
 const action=rows.at(-1);if(action){const current=$('overview').querySelector('.current-action');if(current)current.remove();$('overview').append(text('p','当前动作：'+(action.summary||labels[action.type]||action.type),'current-action'));}
}
$('history').onclick=async()=>{try{const oldest=Math.min(...events.keys());await loadEvents(Math.max(0,oldest-65));}catch(error){notice(error.message);}};
function startStream(){
 if(candidate){const controller=new AbortController();stream=controller;$('connection').textContent='Candidate polling (3s)';const tick=async()=>{if(controller.signal.aborted||!selected)return;try{await loadEvents(cursor);await loadDetail();await loadFiles();}catch(error){notice(error.message);}if(!controller.signal.aborted)refreshTimer=setTimeout(tick,3000);};tick();return;}

 stopStream();if(!selected||!token)return;const controller=new AbortController();stream=controller;const url=route('/events/stream')+'?afterSequence='+cursor;
 (async()=>{try{
  $('connection').textContent='连接中';const response=await fetch(url,{headers:{Authorization:'Bearer '+token,'Last-Event-ID':String(cursor)},signal:controller.signal,cache:'no-store'});
  if(response.status===401){signOut('登录状态已失效，请重新认证。');return;}if(!response.ok)throw Error('增量连接失败');$('connection').textContent='实时更新';
  const reader=response.body.getReader(),decoder=new TextDecoder();let pending='';
  for(;;){const {value,done}=await reader.read();if(done)break;pending+=decoder.decode(value,{stream:true}).replaceAll('\r\n','\n');let split;
   while((split=pending.indexOf('\n\n'))!==-1){const block=pending.slice(0,split);pending=pending.slice(split+2);const lines=block.split('\n'),type=lines.find(line=>line.startsWith('event:'))?.slice(6).trim(),raw=lines.filter(line=>line.startsWith('data:')).map(line=>line.slice(5).trim()).join('\n');
    if(type==='authentication-required'){signOut('登录状态已失效，请重新认证。');return;}
    if(raw&&type==='coding-event'){mergeEvents([JSON.parse(raw)]);scheduleDetail();}
   }
  }
  $('connection').textContent='历史已补读';
 }catch(error){if(controller.signal.aborted)return;$('connection').textContent='连接中断，按事件序号补读';}
 finally{if(!controller.signal.aborted&&selected&&token)setTimeout(()=>{if(stream===controller)startStream();},2000);}
 })();
}
function scheduleDetail(){if(refreshTimer)return;refreshTimer=setTimeout(async()=>{refreshTimer=null;try{await loadDetail();}catch(error){notice(error.message);}},1000);}
function receiptRows(value){if(Array.isArray(value))return value;return value&&typeof value==='object'?Object.values(value).filter(row=>row&&typeof row==='object'):[];}
function renderModels(value){
 const unique=new Map();for(const row of receiptRows(value)){const id=row.requestId||[row.attemptId,row.operationId].join(':');unique.set(id,row);}const rows=[...unique.values()];$('model-rows').replaceChildren();
 function aggregate(field){let sum=0,unknown=0;for(const row of rows){const value=row.usage?.[field];if(typeof value==='number'&&Number.isFinite(value))sum+=value;else unknown++;}return unknown?`${sum.toLocaleString()} 已确认 · ${unknown} 次未提供`:number(sum);}
 $('usage').replaceChildren(facts([['实际模型请求',number(rows.length)],['输入 Token',rows.length?aggregate('inputTokens'):'未提供'],['输出 Token',rows.length?aggregate('outputTokens'):'未提供'],['累计请求耗时',rows.length&&rows.every(row=>typeof (row.durationMs??row.durationMillis)==='number')?duration(rows.reduce((sum,row)=>sum+(row.durationMs??row.durationMillis),0)):'未提供']]));
 for(const row of rows){const usage=row.usage||{},tr=document.createElement('tr'),model=document.createElement('td');model.append(text('span',row.providerModel||row.model||row.selectedModel||'未提供'),text('small',row.requestId||row.operationId));
  const total=usage.totalTokens??(typeof usage.inputTokens==='number'&&typeof usage.outputTokens==='number'?usage.inputTokens+usage.outputTokens:null);
  tr.append(model,text('td',row.status||row.state||'STARTED'),text('td',number(usage.inputTokens)),text('td',number(usage.outputTokens)),text('td',number(total)),text('td',`读 ${number(usage.cacheReadTokens)} / 写 ${number(usage.cacheWriteTokens)}`),text('td',duration(row.durationMs??row.durationMillis)));$('model-rows').append(tr);}
}
function renderExecutions(value){$('executions').replaceChildren();for(const row of receiptRows(value)){const box=document.createElement('details');box.append(text('summary',row.command||row.executionId||row.toolCallId||'执行回执'),text('pre',JSON.stringify(row,null,2)));for(const output of Array.isArray(row.output)?row.output:row.output?[row.output]:[]){if(output.query)box.append(text('h4','Command'),text('pre',output.query));if(output.result)box.append(text('h4','Native command output'),text('pre',output.result));}if(row.detailAssetId)box.append(detailButton(row.detailAssetId,'查看原始命令回执'));for(const stream of ['stdout','stderr'])if(row[stream+'AssetId'])box.append(detailButton(row[stream+'AssetId'],stream));for(const source of row.sourceSnapshots||[])if(source.assetId)box.append(detailButton(source.assetId,source.path));if(row.exceptionSummary)box.append(text('p',row.exceptionSummary,'error'));$('executions').append(box);}if(!$('executions').children.length)$('executions').append(text('p','尚无已记录的执行回执；源码按产物清单按需读取。'));}
function renderRepairs(){
 if(!selected)return;$('repairs').replaceChildren();const errors=[...events.values()].filter(row=>row.type==='ERROR_OCCURRED');
 for(const error of errors){const box=document.createElement('article');box.append(text('p',error.errorSummary||error.summary,'error'));if(error.detailAssetId)box.append(detailButton(error.detailAssetId,'查看完整异常与对应执行'));$('repairs').append(box);}
 const repairs=receiptRows(selected.repairs).filter(row=>row.verified===true&&row.failureEventId&&row.editEventId&&row.executionEventId);
 for(const repair of repairs){const box=document.createElement('article');box.append(text('p',repair.exceptionSummary||'异常后修改源码并重跑'),text('pre',JSON.stringify(repair,null,2)));for(const [key,label] of [['failureStdoutAssetId','异常 stdout'],['failureStderrAssetId','异常 stderr'],['rerunStdoutAssetId','重跑输出']])if(repair[key])box.append(detailButton(repair[key],label));$('repairs').append(box);}
 if(!errors.length&&!repairs.length)$('repairs').append(text('p','暂无已记录的异常或修复证据。'));else if(!repairs.length)$('repairs').append(text('p','尚未建立经核验的失败—修改—再次执行关联。'));
}
async function loadFiles(){
 if(!selected)return;const key=selected.runId+'\0'+selected.taskId,result=await request(route('/artifacts'));if(!selected||key!==selected.runId+'\0'+selected.taskId)return;
 $('files').replaceChildren();$('delivery-state').textContent='Java 验收：'+(result.validationStatus||'NOT_RUN')+' · 文件状态由 Java 登记，生成不代表已验收。';
 if(candidate)$('delivery-state').textContent='Candidate: Native sealed inventory; preview checks SHA-256 against the local sealed file. Not registered as a formal Java delivery.';
 for(const file of result.files||[]){const item=document.createElement('article');item.className='file';const name=document.createElement('div');name.append(text('strong',file.fileName),text('small',`${file.kind} · ${file.state} · ${number(file.size)} 字节`),text('small','SHA-256 '+file.sha256));const actions=document.createElement('div');
  const view=text('button','预览');view.onclick=()=>preview(file.id,file.fileName).catch(error=>notice(error.message));const download=text('button','下载');download.onclick=()=>downloadFile(file).catch(error=>notice(error.message));actions.append(view,download);item.append(name,actions);$('files').append(item);}
 if(!$('files').children.length)$('files').append(text('p','尚无 Java 已登记文件。'));
}
$('refresh-files').onclick=()=>loadFiles().catch(error=>notice(error.message));
async function preview(id,name){const response=await request(route('/artifacts/')+encodeURIComponent(id)+'/content',{raw:true});$('preview-title').textContent=name;$('preview-body').replaceChildren();if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=null;
 if(response.headers.get('Content-Type')?.startsWith('image/png')){objectUrl=URL.createObjectURL(await response.blob());const img=document.createElement('img');img.alt=name;img.src=objectUrl;$('preview-body').append(img);}else $('preview-body').append(text('pre',await response.text()));$('preview').showModal();}
async function downloadFile(file){const response=await request(route('/artifacts/')+encodeURIComponent(file.id)+'/content?download=true',{raw:true}),url=URL.createObjectURL(await response.blob()),link=document.createElement('a');link.href=url;link.download=file.fileName;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
$('close-preview').onclick=()=>{$('preview').close();if(objectUrl)URL.revokeObjectURL(objectUrl);objectUrl=null;};
setInterval(()=>{if(token&&!selected&&!$('list').hidden)loadList().catch(error=>notice(error.message));},15000);
if(token)authenticate().catch(error=>signOut(error.message));
