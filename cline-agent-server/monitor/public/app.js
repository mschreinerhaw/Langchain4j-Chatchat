const $=s=>document.querySelector(s),el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
let config,current,detail,stream,items=[],observations=[],seen=new Set(),tab='timeline',view='monitor',timelineLimit=40,pendingDispatch=null,refreshing=false,createCommandId=null,textBlock=null,renderTimer,traceCache=new Map();
async function api(path,body){const r=await fetch('/api'+path,{method:body===undefined?'GET':'POST',headers:{'content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});const b=await r.json();if(!r.ok){if(r.status===401){stream?.close();$('#console').hidden=true;$('#login').hidden=false;$('#view-switch').hidden=true;$('#logout').hidden=true;}throw Error(b.message??r.status);}return b;}
const command=text=>$('#command-status').textContent=text;
const pretty=v=>JSON.stringify(v,null,2),unknown=v=>v===undefined||v===null?'UNKNOWN':String(v);
function dump(value){return el('pre',pretty(value));}
function card(title,value){const n=el('details'),s=el('summary',title);n.append(s,dump(value));return n;}
function badge(text){return el('span',unknown(text),'badge'+(/FAILED|EXCEEDED|UNKNOWN|PARTIAL|ABORTED|CANCELLED|RECOVERABLE/i.test(text)?' bad':''));}
const statusOf=item=>item.mode==='runtime'?(item.runtimeStatus??item.status):item.status;
const orderedItems=()=>[...items].sort((a,b)=>(Date.parse(b.updatedAt)||0)-(Date.parse(a.updatedAt)||0));
function conversationOpen(open){$('#conversation-body').hidden=!open;$('#toggle-conversation').setAttribute('aria-expanded',String(open));$('#toggle-conversation').textContent=open?'收起记录':'展开记录';}
function configureView(next){
 view=next;$('#console').dataset.view=view;for(const name of ['monitor','chat']){$('#view-'+name).classList.toggle('active',view===name);$('#view-'+name).setAttribute('aria-pressed',String(view===name));}
 $('#list-heading').textContent=view==='monitor'?'Runtime 任务':'Native 调试会话';$('#new').textContent=view==='monitor'?'＋ 新建分析任务':'＋ 新建对话';
 $('#conversation-heading').textContent=view==='monitor'?'执行对话记录':'Native 对话测试';$('#conversation-hint').textContent=view==='monitor'?'辅助查看 Cline 的文字响应；业务状态以任务回执为准。':'向官方 Native Session 发送编码任务或继续对话。';
 $('#filter').value=view==='monitor'?'runtime':'native';$('#status-filter').value='';conversationOpen(view==='chat');listSessions();
}
async function switchView(next){configureView(next);const item=orderedItems().find(i=>i.mode===(next==='monitor'?'runtime':'native'));if(item)await select(item.id);else{stream?.close();current=null;detail=null;location.hash='';observations=[];seen=new Set();$('#session-heading').replaceChildren(el('h1',next==='monitor'?'暂无 Runtime 任务':'暂无 Native 调试会话'));$('#state').replaceChildren();$('#overview').replaceChildren();$('#messages').replaceChildren();$('#permissions').replaceChildren();$('#message-form').hidden=true;for(const id of ['send','cancel','reconcile'])$('#'+id).disabled=true;command('');renderTab();}}
function listSessions(){
 const query=$('#search').value.toLowerCase(),mode=$('#filter').value,root=$('#sessions'),status=$('#status-filter'),selected=status.value;root.replaceChildren();
 const candidates=orderedItems().filter(i=>(!mode||i.mode===mode)&&pretty(i).toLowerCase().includes(query));status.replaceChildren(new Option('全部状态',''),...[...new Set(candidates.map(statusOf).filter(Boolean))].sort().map(s=>new Option(s,s)));status.value=[...status.options].some(o=>o.value===selected)?selected:'';
 const visible=candidates.filter(i=>!status.value||statusOf(i)===status.value);$('#task-count').textContent=visible.length+' / '+candidates.length+' 条记录';
 for(const item of visible){const b=el('button',undefined,'session'+(current===item.id?' active':''));b.dataset.id=item.id;const id=item.taskId??item.sessionId;b.title=id;b.append(el('strong',item.mode==='native'?'Native 调试会话':'分析任务 · '+String(id).replace('analysis-coding-','').slice(0,8)),badge(statusOf(item)),el('small',id),el('small',item.mode==='runtime'?(item.runtimeStatus?'Runtime 已查询':'Native 网关观测'):(item.nativeSessionId??'等待原生会话')));b.onclick=()=>select(item.id);root.append(b);}
 if(!root.children.length)root.append(el('p','尚无匹配任务'));
}
async function refresh(){if(refreshing)return;refreshing=true;try{const known=new Map(items.filter(i=>i.runtimeStatus).map(i=>[i.id,i.runtimeStatus]));items=(await api('/sessions')).items.map(i=>i.mode==='runtime'&&known.has(i.id)?{...i,runtimeStatus:known.get(i.id)}:i);listSessions();if(pendingDispatch){const matching=items.find(i=>i.mode==='runtime'&&i.id&&!seen.has('dispatch:'+i.id)&&!pendingDispatch.before.includes(i.id)&&i.requestId===(pendingDispatch.nodeId??pendingDispatch.requestId));if(matching){pendingDispatch=null;await select(matching.id);}else if(pendingDispatch.requestId){const status=await api('/dispatches/'+encodeURIComponent(pendingDispatch.requestId));if(status.status==='RESPONSE_ERROR'){command(status.message);pendingDispatch=null;}} }}catch(e){command(e.message);}finally{refreshing=false;}}
async function boot(){config=await api('/config');$('#login').hidden=true;$('#console').hidden=false;$('#logout').hidden=false;$('#view-switch').hidden=false;$('#view-chat').disabled=!config.nativeEnabled;$('#workspace').replaceChildren(...config.workspaces.map(w=>new Option(w,w)));$('#model').replaceChildren(...config.models.map(w=>new Option(w,w)));$('#mode').options[0].disabled=!config.nativeEnabled;configureView('monitor');await refresh();const id=decodeURIComponent(location.hash.slice(1)),selected=items.find(i=>i.id===id)??orderedItems().find(i=>i.mode==='runtime');if(selected)await select(selected.id);}
function conversation(event){
 const p=event.payload??{},a=p.payload?.event??p.event??p;let text,cls='';
 if(event.type==='native.event'&&a.contentType==='text'){
  const root=$('#messages'),follow=root.scrollHeight-root.scrollTop-root.clientHeight<100;
  if(!textBlock){const block=el('div',undefined,'message');block.dataset.eventId=event.id;const content=el('div','');block.append(el('small',(event.timestamp??'')+' · '+event.source+' · '+event.id),content);root.append(block);textBlock=content;}
  if(typeof a.accumulated==='string')textBlock.textContent=a.accumulated;
  else if(a.type==='content_delta')textBlock.textContent+=a.text??a.delta??'';
  else if(typeof a.text==='string')textBlock.textContent=a.text;
  if(a.type==='content_end')textBlock=null;
  if(follow)root.scrollTop=root.scrollHeight;return;
 }
 if(event.type==='task.accepted'&&p.text){text=p.text;cls='user';}
 else if(event.type==='native.event'&&a.contentType==='text')text=a.text??a.delta??a.content;
 else if(event.type==='interaction.requested')text=p.question??p.text??pretty(p);
 else if(event.type.startsWith('tool.')){text=(p.toolName??'工具')+' · '+event.type+(p.outputSummary?' · '+p.outputSummary:'');cls='tool';}
 else if(event.type==='turn.completed')text='原生会话本轮结束；业务交付状态请查看 Runtime 回执。';
 else if(/failed|failure|error/.test(event.type)){text=event.type+' · '+(p.message??p.error??pretty(p));cls='tool';}
 if(typeof text!=='string'||!text)return;
 const root=$('#messages'),follow=root.scrollHeight-root.scrollTop-root.clientHeight<100;const message=el('div',undefined,'message '+cls);message.dataset.eventId=event.id;message.append(el('small',(event.timestamp??'')+' · '+event.source+' · '+event.id),el('div',text));root.append(message);if(follow)root.scrollTop=root.scrollHeight;
}
function accept(event){if(seen.has(event.id))return;seen.add(event.id);observations.push(event);conversation(event);if(!renderTimer)renderTimer=setTimeout(()=>{renderTimer=null;renderTimeline();renderTab();},120);}
function renderTimeline(){const root=$('#timeline');root.replaceChildren();for(const e of observations.filter(e=>/model\.|broker.failure|tool\.|runtime.event|mcp\.|task\.|interaction|permission/.test(e.type)).slice(-12).reverse()){const s=el('div',undefined,'step');s.append(el('strong',e.payload?.toolName??e.payload?.tool??e.payload?.name??e.type),el('div',e.payload?.status??e.type),el('time',e.timestamp??''));root.append(s);}}
function snapshot(d){
 detail=d;const listed=items.find(i=>i.id===current);if(listed){listed.status=d.status;if(d.mode==='runtime')listed.runtimeStatus=d.runtimeStatus;listSessions();}const heading=$('#session-heading');heading.dataset.session=d.id;heading.replaceChildren(el('p',d.mode==='runtime'?'RUNTIME TASK':'NATIVE CHAT TEST','eyebrow'),el('h1',d.mode==='runtime'?'任务执行监控':'Native 对话测试'),badge(d.mode==='runtime'?d.runtimeStatus:d.status));heading.append(el('p',d.taskId??d.sessionId??current,'task-id'));if(typeof d.objective==='string')heading.append(el('p',d.objective.split('\n')[0],'objective'));
 $('#message-form').hidden=d.mode!=='native';
 $('#show-conversation').hidden=d.mode==='native';
 const overview=$('#overview');overview.replaceChildren();
 const runtimeObserved=d.mode==='runtime'&&d.authority==='RUNTIME_CONFIRMED',modelObserved=runtimeObserved||d.mode==='native';
 for(const [label,value,note] of [[d.mode==='runtime'?'Runtime 业务状态':'Native 状态',d.mode==='runtime'?d.runtimeStatus:d.status,d.authority],['模型调用',modelObserved&&Array.isArray(d.modelReceipts)?d.modelReceipts.length:null,d.mode==='runtime'?(runtimeObserved?'Runtime 模型回执':'Runtime 回执未取得'):'Core 轮次观测'],['Python 执行',d.executions?.at(-1)?.status,runtimeObserved&&Array.isArray(d.executions)?'已记录 '+d.executions.length+' 次执行':'未关联 Python 回执'],['业务验证',d.outcome?.validationStatus,'独立于进程退出状态']]){const m=el('div',undefined,'metric'+(/FAILED|EXCEEDED|UNKNOWN|PARTIAL|ABORTED/.test(String(value))?' bad':/COMPLETED|SUCCEEDED|PASSED/.test(String(value))?' good':''));m.append(el('span',label,'metric-label'),el('strong',unknown(value),'metric-value'),el('span',unknown(note),'metric-note'));overview.append(m);}
 $('#send').disabled=!d.capabilities?.messages;$('#message').disabled=!d.capabilities?.messages;$('#cancel').disabled=!d.capabilities?.cancel;$('#reconcile').disabled=!d.capabilities?.reconcile;
 const state=$('#state');state.replaceChildren();const dl=el('dl');
 const fields=[['Native 状态',d.status],['Runtime 状态',d.mode==='runtime'?d.runtimeStatus:'未关联'],['状态来源',d.authority],['Session',d.sessionId],['Agent Session',d.nativeSessionId],['Task ID',d.taskId],['Worker 停止',d.workerStopped],['远端停止',d.remoteStopConfirmed],['待决 Python',d.activeOperations?.join(',')||'无已知活动操作'],['模型',d.model?.model??d.modelReceipts?.at(-1)?.providerModel],['输入 Token',sumUsage(d.modelReceipts,'inputTokens')],['输出 Token',sumUsage(d.modelReceipts,'outputTokens')]];
 fields[0][0]=d.mode==='runtime'?'Native 网关':'Native Session';
 const remote=fields.find(f=>f[0]==='远端停止');if(remote){remote[0]='Python 停止';remote[1]=d.lastPythonStopped;}
 if(d.mode==='runtime')fields.splice(1,0,['Core 本轮',d.nativeTurnStatus],['Python 状态',d.executions?.at(-1)?.status],['结束工具',d.nativeCompletion?.toolCallId]);
 const metrics=d.metrics??{};
 fields.push(['模型请求总数',metrics.modelRequests],['Token 总消耗',metrics.tokens],
  ['累计费用',metrics.cost==null?null:metrics.cost+' '+(metrics.currency??'')],
  ['Native 工具调用次数',metrics.toolCalls],['Runtime 受管工具次数',metrics.managedToolCalls],['Python 自动修复次数',metrics.automaticRepairs],
  ['实际运行耗时 ms',metrics.elapsedMillis],['当前执行阶段',metrics.phase],
  ['错误和中断原因',d.error??metrics.interruptionReason],['产物数量',metrics.artifacts],['归档文件数量',metrics.archivedFiles],
  ['产物校验状态',metrics.artifactValidation]);
 for(const [k,v] of fields){dl.append(el('dt',k),el('dd',unknown(v)));}state.append(dl);
 const perms=$('#permissions');perms.replaceChildren();for(const p of d.pendingPermissions??[]){const div=el('div');div.append(el('p','待批准：'+p.toolName+' / '+p.toolCallId));div.append(card('Permission input / toolCallId',p));for(const [label,decision] of [['批准','approve'],['拒绝','deny']]){const b=el('button',label);b.onclick=()=>control('permission',{permissionId:p.id,decision});div.append(b);}perms.append(div);}
 if(d.status==='awaiting_input')command('Cline 正在等待回答；请根据会话中的问题回复。');if(d.mode==='runtime'&&d.runtimeStatus==='COMPLETED')command('Runtime 已完成独立验算与归档交付。');renderTab();
}
function sumUsage(receipts,field){if(!receipts?.length)return 'UNKNOWN';const values=receipts.map(r=>r[field]??r.usage?.[field]);return values.every(Number.isFinite)?values.reduce((a,b)=>a+b,0):'UNKNOWN（部分回执用量未确认）';}
async function select(key){
 stream?.close();$('.workspace').scrollTop=0;const selectedMode=key.startsWith('native:')?'chat':'monitor';if(view!==selectedMode)configureView(selectedMode);current=key;tab='timeline';timelineLimit=40;for(const b of $('#tabs').children)b.classList.toggle('active',b.dataset.tab===tab);textBlock=null;traceCache=new Map();location.hash=encodeURIComponent(key);observations=[];seen=new Set();$('#messages').replaceChildren();$('#timeline').replaceChildren();command('');conversationOpen(view==='chat');listSessions();
 detail=null;$('#connection').textContent='正在读取任务…';$('#session-heading').dataset.session='';$('#session-heading').replaceChildren(el('h1','正在读取任务…'));$('#overview').replaceChildren();$('#state').replaceChildren();$('#permissions').replaceChildren();$('#message-form').hidden=true;for(const id of ['send','cancel','reconcile'])$('#'+id).disabled=true;renderTab();
 try{const value=await api('/sessions/'+encodeURIComponent(key));if(current!==key)return;snapshot(value);await loadTab();if(current!==key)return;}catch(e){if(current===key)command(e.message);return;}
 $('#connection').textContent='连接持久事件流…';
 stream=new EventSource('/api/sessions/'+encodeURIComponent(key)+'/events');stream.onopen=()=>{if(key===current)$('#connection').textContent='● 实时连接';};stream.onerror=()=>{if(key===current)$('#connection').textContent='连接中断 · 自动补拉中';};
 stream.addEventListener('observation',e=>{if(key===current)accept(JSON.parse(e.data));});stream.addEventListener('snapshot',e=>{if(key===current)snapshot(JSON.parse(e.data));});stream.addEventListener('diagnostic',e=>command(JSON.parse(e.data).message));
}
function renderTab(){
 const root=$('#detail-content');if(!detail){root.replaceChildren(el('p','选择任务后查看执行详情'));return;}
 if(['files','code'].includes(tab))return;
 const opened=new Set([...root.querySelectorAll('details[open]')].map(n=>n.dataset.entryKey));
 root.replaceChildren();let entries=[];
 if(tab==='timeline'){
  const events=observations.filter(e=>/^(model\.|tool\.|mcp\.|task\.|broker\.|runtime.event|turn.completed|permission\.|native.submit|session\.)/.test(e.type));
  root.append(el('p','已取得 '+events.length+' 条执行事件 · 最新在前 · 点击展开真实回执'));
  const labels={'model.request':'模型请求','model.receipt':'模型回执','model.usage':'模型用量','model.iteration.started':'Core 模型轮次','tool.started':'工具开始','tool.completed':'工具完成','tool.failed':'工具失败','mcp.request':'MCP 请求','mcp.receipt':'MCP 回执','mcp.failure':'MCP 失败','broker.failure':'调用失败','task.delivered':'Runtime 交付','turn.completed':'原生本轮结束'};
  for(const e of events.slice(-timelineLimit).reverse()){
   const p=e.payload??{},state=p.state??p.status??p.receipt?.status,problem=/fail|error/.test(e.type)||/FAILED|EXCEEDED/.test(state??'')||Boolean(p.error),row=el('details',undefined,'event-row'+(problem?' error':''));row.dataset.entryKey='timeline:'+e.id;row.open=opened.has(row.dataset.entryKey);
   const summary=el('summary'),title=el('span',(labels[e.type]??e.type)+(p.toolName??p.name?' · '+(p.toolName??p.name):''),'event-title');
   const description=typeof p.error==='string'?p.error:p.error?.message??p.errorMessage??p.failureSummary??p.message??p.operationId??p.receipt?.operationId??p.type??e.id;title.append(el('span',String(description).slice(0,240),'event-description'));
   const time=el('time',e.timestamp?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(new Date(e.timestamp)):'UNKNOWN');time.title=e.timestamp??'UNKNOWN';summary.append(time,title,el('span',state??e.source,'event-state'));row.append(summary,dump(e));root.append(row);
  }
  if(events.length>timelineLimit){const more=el('button','显示更早的执行事件（剩余 '+(events.length-timelineLimit)+' 条）');more.onclick=()=>{timelineLimit+=40;renderTab();};root.append(more);}if(!events.length)root.append(el('p','等待持久事件补拉，不推断执行阶段。'));return;
 }
 if(tab==='models'){
  root.append(el('p',detail.mode==='native'?'Native Core 轮次观测；模型为会话配置，用量为原生上报。观测耗时由轮次开始到 usage 事件计算，不代表供应商耗时或计费回执。缺失值为 UNKNOWN。':'真实模型、耗时和 Token 来自 Runtime 模型回执。缺失用量为 UNKNOWN。'));
  const table=el('table');table.id='model-calls';const head=el('tr');for(const title of ['调用 / Core 轮次','模型','实际状态','耗时 ms','输入 Token','输出 Token','错误'])head.append(el('th',title));table.append(head);
  for(const r of detail.modelReceipts??[]){const row=el('tr');for(const v of [r.operationId+(r.iteration===undefined?'':' / '+r.iteration),r.providerModel??r.selectedModel??r.configuredModel,r.state,r.durationMs??r.observedDurationMs,r.usage?.inputTokens,r.usage?.outputTokens,r.error===undefined?null:pretty(r.error)])row.append(el('td',unknown(v)));table.append(row);}root.append(table);
  entries=[...(detail.modelReceipts??[]),...observations.filter(e=>/model\.|iteration|usage|broker.failure/.test(e.type))];
 }
 if(tab==='tools')entries=observations.filter(e=>e.type.startsWith('tool.'));
 if(tab==='skills')entries=observations.filter(e=>/mcp\.|skills/i.test(e.type)||/skill|mcp|runtime__/i.test(pretty(e.payload)));
 if(tab==='python'){
  root.append(el('p','Python 执行成功、原生结束与 Runtime 业务交付分别展示。'));
  const table=el('table'),head=el('tr');for(const title of ['Python operationId','实际状态','退出码','源码 Hash','校验'])head.append(el('th',title));table.append(head);
  for(const e of detail.executions??[]){const row=el('tr');for(const v of [e.executionId,e.status,e.exitCode,e.codeHash,e.validationStatus])row.append(el('td',unknown(v)));table.append(row);}root.append(table);
  entries=[...(detail.executions??[]),...observations.filter(e=>/python_execute|executionFeedback|operationId/.test(pretty(e.payload)))];
 }
 if(tab==='events')entries=observations;
 if(tab==='diagnostics')entries=[{nativeStatus:detail.status,runtimeStatus:detail.runtimeStatus,authority:detail.authority,error:detail.error,activeOperations:detail.activeOperations,workerStopped:detail.workerStopped,remoteStopConfirmed:detail.remoteStopConfirmed},...observations.filter(e=>/error|fail|cancel|reconcil|recover/.test(e.type))];
 if(!entries.length)root.append(el('p','尚无实际回执；不推断为成功。'));
 for(const entry of entries.slice(-300).reverse()){
  const block=card([entry.timestamp??entry.startedAt??'',entry.type??entry.operationId??entry.executionId??entry.model??'回执',entry.payload?.toolName??entry.state??entry.status??''].join(' · '),entry);block.dataset.entryKey=tab+':'+(entry.id??entry.operationId??entry.executionId??'snapshot');block.open=opened.has(block.dataset.entryKey);root.append(block);
  const operation=entry.executionId??entry.operationId??entry.payload?.operationId;
  if(tab==='python'&&detail.mode==='runtime'&&/^cline-[a-f0-9]{64}$/.test(operation??''))for(const kind of ['code','stdout','stderr','timing']){const button=el('button','查看 '+kind),key=current+':'+operation+':'+kind;if(traceCache.has(key))block.append(dump(traceCache.get(key)));button.onclick=async()=>{const session=current;block.open=true;button.disabled=true;try{if(!traceCache.has(key))traceCache.set(key,await api('/sessions/'+encodeURIComponent(session)+'/trace?operationId='+operation+'&kind='+kind));if(current===session&&tab==='python')renderTab();}catch(e){command(e.message);}finally{button.disabled=false;}};block.append(button);}
 }
}
async function loadTab(){
 const key=current,selectedTab=tab,active=()=>current===key&&tab===selectedTab;renderTab();if(!key)return;
 if(selectedTab==='events'&&detail.mode==='runtime'){try{const data=await api('/sessions/'+encodeURIComponent(key)+'/runtime-events');if(active())$('#detail-content').prepend(card('Runtime Java Journal · 持久 sequence / hasMore',data));}catch(e){if(active())command(e.message);}}
 if(selectedTab==='files'){const root=$('#detail-content');root.replaceChildren(el('p','正在查询 Runtime 归档…'));try{const data=await api('/sessions/'+encodeURIComponent(key)+'/assets');if(!active())return;const files=Array.isArray(data)?data:data.items??data.files??[];root.replaceChildren(el('p','内容由 Runtime 按当前账户授权下载；列表只展示元数据。'));for(const f of files){const div=el('div');const a=el('a',f.fileName??f.name??f.id);a.href='/api/assets/'+encodeURIComponent(f.id)+'/content';a.download=f.fileName??f.name??f.id;div.append(a,el('pre',pretty({id:f.id,bytes:f.size,sha256:f.sha256??f.contentHash,state:f.state,kind:f.kind})));root.append(div);}if(!files.length)root.append(el('p','尚无实际归档文件'));}catch(e){if(active())root.replaceChildren(el('p',e.message));}}
 if(selectedTab==='code'){const root=$('#detail-content');root.replaceChildren(el('p','正在读取实际源码证据…'));try{const code=await api('/sessions/'+encodeURIComponent(key)+'/code');if(!active())return;root.replaceChildren(dump(code));if(typeof code.patch==='string')root.prepend(el('pre',code.patch));if(code.completeDiffAvailable){const [a,b]=code.sources;const before=a.content.split('\n'),after=b.content.split('\n');let start=0;while(start<before.length&&start<after.length&&before[start]===after[start])start++;let endA=before.length,endB=after.length;while(endA>start&&endB>start&&before[endA-1]===after[endB-1]){endA--;endB--;}root.prepend(el('pre','--- version '+a.version+' '+a.codeHash+'\n+++ version '+b.version+' '+b.codeHash+'\n@@ line '+(start+1)+' @@\n'+before.slice(start,endA).map(l=>'- '+l).join('\n')+'\n'+after.slice(start,endB).map(l=>'+ '+l).join('\n')));}for(const e of observations.filter(e=>/apply_patch|editor|CODE_READY/.test(pretty(e))))root.append(card(e.timestamp+' · '+e.type,e));}catch(e){if(active())root.replaceChildren(el('p',e.message));}}
}
async function control(action,body={}){try{command('正在提交…');const r=await api('/sessions/'+encodeURIComponent(current)+'/'+action,body);command(pretty(r));snapshot(await api('/sessions/'+encodeURIComponent(current)));}catch(e){command(e.message);}}
$('#login-form').onsubmit=async e=>{e.preventDefault();try{await api('/login',{username:$('#username').value,password:$('#password').value});$('#password').value='';await boot();}catch(err){$('#login-error').textContent=err.message;}};
$('#logout').onclick=async()=>{stream?.close();await api('/logout',{});location.reload();};$('#search').oninput=listSessions;$('#filter').onchange=listSessions;$('#status-filter').onchange=listSessions;$('#refresh').onclick=refresh;
$('#view-monitor').onclick=()=>switchView('monitor');$('#view-chat').onclick=()=>switchView('chat');$('#toggle-conversation').onclick=()=>conversationOpen($('#conversation-body').hidden);
$('#cancel').onclick=()=>control('cancel');$('#reconcile').onclick=()=>control('reconcile');$('#details').onclick=()=>{tab='timeline';for(const b of $('#tabs').children)b.classList.toggle('active',b.dataset.tab===tab);loadTab();$('#detail').scrollIntoView({behavior:'smooth'});};$('#show-conversation').onclick=()=>{conversationOpen(true);$('#conversation-panel').scrollIntoView({behavior:'smooth'});};
$('#tabs').onclick=e=>{if(!e.target.dataset.tab)return;tab=e.target.dataset.tab;for(const b of $('#tabs').children)b.classList.toggle('active',b.dataset.tab===tab);loadTab();};
$('#message-form').onsubmit=async e=>{e.preventDefault();const text=$('#message').value.trim();if(!text)return;$('#send').disabled=true;const question=observations.filter(e=>e.type==='interaction.requested').at(-1)?.payload;await control('messages',{text,commandId:Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join(''),...(question?.questionId?{interactionId:question.questionId}:{})});$('#message').value='';};
function mode(){const runtime=$('#mode').value==='runtime';$('#native-options').hidden=runtime;$('#runtime-options').hidden=!runtime;$('#instruction').required=runtime;$('#create-heading').textContent=runtime?'新建分析任务':'新建对话测试';}
$('#mode').onchange=mode;$('#close-dialog').onclick=()=>$('#create-dialog').close();$('#new').onclick=async()=>{createCommandId=Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join('');$('#mode').value=view==='chat'&&config.nativeEnabled?'native':'runtime';mode();$('#create-error').textContent='';$('#create-dialog').showModal();try{const data=await api('/inputs');const files=Array.isArray(data)?data:data.items??data.files??[];$('#inputs').replaceChildren(...files.filter(f=>['DOCUMENT','DATASET','ARTIFACT'].includes(f.kind)&&f.state==='AVAILABLE').map(f=>new Option((f.fileName??f.name??f.id)+' · '+f.size+' B',f.id)));}catch(e){$('#create-error').textContent=e.message;}};
$('#create-form').onsubmit=async e=>{e.preventDefault();const button=e.submitter;button.disabled=true;try{const before=items.map(i=>i.id);const r=await api('/sessions',{commandId:createCommandId,validationProfile:$('#validation-profile').value,valueField:$('#value-field').value,mode:$('#mode').value,workspaceRef:$('#workspace').value,instruction:$('#instruction').value,model:$('#model').value,fileIds:[...$('#inputs').selectedOptions].map(o=>o.value)});$('#create-dialog').close();if(r.id){await refresh();await select(r.id);}else{pendingDispatch={...r,before};command('Runtime 已持久化派发；等待关联 Native Session。项目 '+r.workspaceId+' / 节点 '+r.nodeId);}}catch(err){$('#create-error').textContent=err.message;}finally{button.disabled=false;}};
window.addEventListener('hashchange',()=>{const key=decodeURIComponent(location.hash.slice(1));if(key&&key!==current&&items.some(i=>i.id===key))select(key);});
await boot().catch(()=>{});setInterval(refresh,8000);
