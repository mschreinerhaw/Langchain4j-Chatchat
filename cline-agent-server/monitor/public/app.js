const $=s=>document.querySelector(s),el=(tag,text,cls)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(cls)n.className=cls;return n;};
let config,current,detail,stream,items=[],observations=[],seen=new Set(),tab='models',pendingDispatch=null,refreshing=false,textBlock=null,renderTimer;
async function api(path,body){const r=await fetch('/api'+path,{method:body===undefined?'GET':'POST',headers:{'content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});const b=await r.json();if(!r.ok){if(r.status===401){$('#console').hidden=true;$('#login').hidden=false;}throw Error(b.message??r.status);}return b;}
const command=text=>$('#command-status').textContent=text;
const pretty=v=>JSON.stringify(v,null,2),unknown=v=>v===undefined||v===null?'UNKNOWN':String(v);
function dump(value){return el('pre',pretty(value));}
function card(title,value){const n=el('details'),s=el('summary',title);n.append(s,dump(value));return n;}
function badge(text){return el('span',unknown(text),'badge'+(/FAILED|EXCEEDED|UNKNOWN|PARTIAL/.test(text)?' bad':''));}
function listSessions(){
 const query=$('#search').value.toLowerCase(),mode=$('#filter').value,root=$('#sessions');root.replaceChildren();
 for(const item of items.filter(i=>(!mode||i.mode===mode)&&pretty(i).toLowerCase().includes(query))){const b=el('button',undefined,'session'+(current===item.id?' active':''));b.dataset.id=item.id;b.append(el('strong',item.mode==='native'?'Native 原生编码':'Runtime 业务集成'),badge(item.status),el('small',item.taskId??item.sessionId),el('small',item.nativeSessionId??'等待原生会话'));b.onclick=()=>select(item.id);root.append(b);}
 if(!root.children.length)root.append(el('p','尚无匹配会话'));
}
async function refresh(){if(refreshing)return;refreshing=true;try{items=(await api('/sessions')).items;listSessions();if(pendingDispatch){const matching=items.find(i=>i.mode==='runtime'&&i.id&&!seen.has('dispatch:'+i.id)&&!pendingDispatch.before.includes(i.id)&&i.requestId===(pendingDispatch.nodeId??pendingDispatch.requestId));if(matching){pendingDispatch=null;await select(matching.id);}else if(pendingDispatch.requestId){const status=await api('/dispatches/'+encodeURIComponent(pendingDispatch.requestId));if(status.status==='RESPONSE_ERROR'){command(status.message);pendingDispatch=null;}} }}catch(e){command(e.message);}finally{refreshing=false;}}
async function boot(){config=await api('/config');$('#login').hidden=true;$('#console').hidden=false;$('#logout').hidden=false;$('#workspace').replaceChildren(...config.workspaces.map(w=>new Option(w,w)));$('#model').replaceChildren(...config.models.map(w=>new Option(w,w)));$('#mode').options[0].disabled=!config.nativeEnabled;if(!config.nativeEnabled)$('#mode').value='runtime';await refresh();const id=decodeURIComponent(location.hash.slice(1));if(id&&items.some(i=>i.id===id))await select(id);}
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
 const message=el('div',undefined,'message '+cls);message.dataset.eventId=event.id;message.append(el('small',(event.timestamp??'')+' · '+event.source+' · '+event.id),el('div',text));$('#messages').append(message);
}
function accept(event){if(seen.has(event.id))return;seen.add(event.id);observations.push(event);conversation(event);if(!renderTimer)renderTimer=setTimeout(()=>{renderTimer=null;renderTimeline();renderTab();},120);}
function renderTimeline(){const root=$('#timeline');root.replaceChildren();for(const e of observations.filter(e=>/tool\.|runtime.event|mcp\.|task\.|interaction|permission/.test(e.type)).slice(-12).reverse()){const s=el('div',undefined,'step');s.append(el('strong',e.payload?.toolName??e.payload?.tool??e.payload?.name??e.type),el('div',e.payload?.status??e.type),el('time',e.timestamp??''));root.append(s);}}
function snapshot(d){
 detail=d;const listed=items.find(i=>i.id===current);if(listed&&listed.status!==d.status){listed.status=d.status;listSessions();}const heading=$('#session-heading');heading.replaceChildren(el('h1',d.mode==='native'?'Native 原生编码测试':'Runtime 业务集成测试'),badge(d.status));if(d.mode==='runtime')heading.append(badge('Runtime: '+unknown(d.runtimeStatus)));heading.append(el('p',d.taskId??d.sessionId??current));
 $('#send').disabled=!d.capabilities?.messages;$('#message').disabled=!d.capabilities?.messages;$('#cancel').disabled=!d.capabilities?.cancel;$('#reconcile').disabled=!d.capabilities?.reconcile;
 const state=$('#state');state.replaceChildren();const dl=el('dl');
 const fields=[['Native 状态',d.status],['Runtime 状态',d.mode==='runtime'?d.runtimeStatus:'未关联'],['状态来源',d.authority],['Session',d.sessionId],['Agent Session',d.nativeSessionId],['Task ID',d.taskId],['Worker 停止',d.workerStopped],['远端停止',d.remoteStopConfirmed],['待决 Python',d.activeOperations?.join(',')||'无已知活动操作'],['模型',d.model?.model??d.modelReceipts?.at(-1)?.providerModel],['输入 Token',sumUsage(d.modelReceipts,'inputTokens')],['输出 Token',sumUsage(d.modelReceipts,'outputTokens')]];
 for(const [k,v] of fields){dl.append(el('dt',k),el('dd',unknown(v)));}state.append(dl);
 const perms=$('#permissions');perms.replaceChildren();for(const p of d.pendingPermissions??[]){const div=el('div');div.append(el('p','待批准：'+p.toolName+' / '+p.toolCallId));div.append(card('Permission input / toolCallId',p));for(const [label,decision] of [['批准','approve'],['拒绝','deny']]){const b=el('button',label);b.onclick=()=>control('permission',{permissionId:p.id,decision});div.append(b);}perms.append(div);}
 if(d.status==='awaiting_input')command('Cline 正在等待回答；请根据会话中的问题回复。');renderTab();
}
function sumUsage(receipts,field){if(!receipts?.length)return 'UNKNOWN';const values=receipts.map(r=>r[field]??r.usage?.[field]);return values.every(Number.isFinite)?values.reduce((a,b)=>a+b,0):'UNKNOWN（部分回执用量未确认）';}
async function select(key){
 stream?.close();current=key;textBlock=null;location.hash=encodeURIComponent(key);observations=[];seen=new Set();$('#messages').replaceChildren();$('#timeline').replaceChildren();listSessions();
 try{snapshot(await api('/sessions/'+encodeURIComponent(key)));}catch(e){command(e.message);return;}
 $('#connection').textContent='连接持久事件流…';
 stream=new EventSource('/api/sessions/'+encodeURIComponent(key)+'/events');stream.onopen=()=>$('#connection').textContent='● 实时连接';stream.onerror=()=>$('#connection').textContent='连接中断 · 自动补拉中';
 stream.addEventListener('observation',e=>{if(key===current)accept(JSON.parse(e.data));});stream.addEventListener('snapshot',e=>{if(key===current)snapshot(JSON.parse(e.data));});stream.addEventListener('diagnostic',e=>command(JSON.parse(e.data).message));
}
function renderTab(){
 const root=$('#detail-content');if(!detail){root.replaceChildren(el('p','选择会话后查看执行详情'));return;}
 if(['files','code'].includes(tab))return;
 root.replaceChildren();let entries=[];
 if(tab==='models'){root.append(el('p','真实 Token 和计费以 Runtime 模型回执为准。缺失用量为 UNKNOWN。'));entries=detail.modelReceipts?.length?detail.modelReceipts:observations.filter(e=>/model\.|iteration|usage/.test(e.type));}
 if(tab==='tools')entries=observations.filter(e=>e.type.startsWith('tool.'));
 if(tab==='skills')entries=observations.filter(e=>/mcp\.|skills/i.test(e.type)||/skill|mcp|runtime__/i.test(pretty(e.payload)));
 if(tab==='python'){root.append(el('p','Python 执行成功、原生结束与 Runtime 业务交付分别展示。'));entries=[...(detail.executions??[]),...observations.filter(e=>/python_execute|executionFeedback|operationId/.test(pretty(e.payload)))];}
 if(tab==='events')entries=observations;
 if(tab==='diagnostics')entries=[{nativeStatus:detail.status,runtimeStatus:detail.runtimeStatus,authority:detail.authority,error:detail.error,activeOperations:detail.activeOperations,workerStopped:detail.workerStopped,remoteStopConfirmed:detail.remoteStopConfirmed},...observations.filter(e=>/error|fail|cancel|reconcil|recover/.test(e.type))];
 if(!entries.length)root.append(el('p','尚无实际回执；不推断为成功。'));
 for(const entry of entries.slice(-300).reverse()){
  const block=card([entry.timestamp??entry.startedAt??'',entry.type??entry.operationId??entry.executionId??entry.model??'回执',entry.payload?.toolName??entry.status??''].join(' · '),entry);root.append(block);
  const operation=entry.executionId??entry.operationId??entry.payload?.operationId;
  if(tab==='python'&&detail.mode==='runtime'&&/^cline-[a-f0-9]{64}$/.test(operation??''))for(const kind of ['code','stdout','stderr','timing']){const button=el('button','查看 '+kind);button.onclick=async()=>{try{block.append(dump(await api('/sessions/'+encodeURIComponent(current)+'/trace?operationId='+operation+'&kind='+kind)));block.open=true;}catch(e){command(e.message);}};block.append(button);}
 }
}
async function loadTab(){
 renderTab();if(!current)return;
 if(tab==='events'&&detail.mode==='runtime'){try{const data=await api('/sessions/'+encodeURIComponent(current)+'/runtime-events');$('#detail-content').prepend(card('Runtime Java Journal · 持久 sequence / hasMore',data));}catch(e){command(e.message);}}
 if(tab==='files'){const root=$('#detail-content');root.replaceChildren(el('p','正在查询 Runtime 归档…'));try{const data=await api('/sessions/'+encodeURIComponent(current)+'/assets');const files=Array.isArray(data)?data:data.items??data.files??[];root.replaceChildren(el('p','内容由 Runtime 按当前账户授权下载；列表只展示元数据。'));for(const f of files){const div=el('div');const a=el('a',f.fileName??f.name??f.id);a.href='/api/assets/'+encodeURIComponent(f.id)+'/content';a.download=f.fileName??f.name??f.id;div.append(a,el('pre',pretty({id:f.id,bytes:f.size,sha256:f.sha256??f.contentHash,state:f.state,kind:f.kind})));root.append(div);}if(!files.length)root.append(el('p','尚无实际归档文件'));}catch(e){root.replaceChildren(el('p',e.message));}}
 if(tab==='code'){const root=$('#detail-content');root.replaceChildren(el('p','正在读取实际源码证据…'));try{const code=await api('/sessions/'+encodeURIComponent(current)+'/code');root.replaceChildren(dump(code));if(code.completeDiffAvailable){const [a,b]=code.sources;const before=a.content.split('\n'),after=b.content.split('\n');let start=0;while(start<before.length&&start<after.length&&before[start]===after[start])start++;let endA=before.length,endB=after.length;while(endA>start&&endB>start&&before[endA-1]===after[endB-1]){endA--;endB--;}root.prepend(el('pre','--- version '+a.version+' '+a.codeHash+'\n+++ version '+b.version+' '+b.codeHash+'\n@@ line '+(start+1)+' @@\n'+before.slice(start,endA).map(l=>'- '+l).join('\n')+'\n'+after.slice(start,endB).map(l=>'+ '+l).join('\n')));}for(const e of observations.filter(e=>/apply_patch|editor|CODE_READY/.test(pretty(e))))root.append(card(e.timestamp+' · '+e.type,e));}catch(e){root.replaceChildren(el('p',e.message));}}
}
async function control(action,body={}){try{command('正在提交…');const r=await api('/sessions/'+encodeURIComponent(current)+'/'+action,body);command(pretty(r));snapshot(await api('/sessions/'+encodeURIComponent(current)));}catch(e){command(e.message);}}
$('#login-form').onsubmit=async e=>{e.preventDefault();try{await api('/login',{username:$('#username').value,password:$('#password').value});$('#password').value='';await boot();}catch(err){$('#login-error').textContent=err.message;}};
$('#logout').onclick=async()=>{stream?.close();await api('/logout',{});location.reload();};$('#search').oninput=listSessions;$('#filter').onchange=listSessions;$('#refresh').onclick=refresh;
$('#cancel').onclick=()=>control('cancel');$('#reconcile').onclick=()=>control('reconcile');$('#details').onclick=()=>$('#detail').scrollIntoView({behavior:'smooth'});
$('#tabs').onclick=e=>{if(!e.target.dataset.tab)return;tab=e.target.dataset.tab;for(const b of $('#tabs').children)b.classList.toggle('active',b.dataset.tab===tab);loadTab();};
$('#message-form').onsubmit=async e=>{e.preventDefault();const text=$('#message').value.trim();if(!text)return;$('#send').disabled=true;const question=observations.filter(e=>e.type==='interaction.requested').at(-1)?.payload;await control('messages',{text,commandId:Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join(''),...(question?.questionId?{interactionId:question.questionId}:{})});$('#message').value='';};
function mode(){const runtime=$('#mode').value==='runtime';$('#native-options').hidden=runtime;$('#runtime-options').hidden=!runtime;$('#instruction').required=runtime;}
$('#mode').onchange=mode;$('#close-dialog').onclick=()=>$('#create-dialog').close();$('#new').onclick=async()=>{mode();$('#create-error').textContent='';$('#create-dialog').showModal();try{const data=await api('/inputs');const files=Array.isArray(data)?data:data.items??data.files??[];$('#inputs').replaceChildren(...files.filter(f=>['DOCUMENT','DATASET','ARTIFACT'].includes(f.kind)&&f.state==='AVAILABLE').map(f=>new Option((f.fileName??f.name??f.id)+' · '+f.size+' B',f.id)));}catch(e){$('#create-error').textContent=e.message;}};
$('#create-form').onsubmit=async e=>{e.preventDefault();const button=e.submitter;button.disabled=true;try{const before=items.map(i=>i.id);const r=await api('/sessions',{commandId:Array.from(crypto.getRandomValues(new Uint8Array(16)),n=>n.toString(16).padStart(2,'0')).join(''),validationProfile:$('#validation-profile').value,valueField:$('#value-field').value,mode:$('#mode').value,workspaceRef:$('#workspace').value,instruction:$('#instruction').value,model:$('#model').value,fileIds:[...$('#inputs').selectedOptions].map(o=>o.value)});$('#create-dialog').close();if(r.id){await refresh();await select(r.id);}else{pendingDispatch={...r,before};command('Runtime 已持久化派发；等待关联 Native Session。项目 '+r.workspaceId+' / 节点 '+r.nodeId);}}catch(err){$('#create-error').textContent=err.message;}finally{button.disabled=false;}};
window.addEventListener('hashchange',()=>{const key=decodeURIComponent(location.hash.slice(1));if(key&&key!==current&&items.some(i=>i.id===key))select(key);});
await boot().catch(()=>{});setInterval(refresh,8000);
