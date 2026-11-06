export function workspaceBusinessName(value){
  const name=String(value||'客户资产分析').replace(/\s*\d{4}-\d{2}-\d{2}T\S+/g,'').replace(/\s+[a-f\d]{8}(?:-[a-f\d]{4}){3}-[a-f\d]{12}$/i,'').trim();
  return name.replace(/^客户资产探索验收$/,'客户资产分析')||'数据分析';
}
export function businessNodeTitle(node){
  const report=node?.receipt?.visualReport;
  const value=String(node?.title||report?.title||'').trim();
  if(value&&!/JSON|Python|Sandbox|不得|指令|图表轴/i.test(value))return value.length>32?value.slice(0,31)+'…':value;
  const query=String(node?.question||'');
  if(/单位|图表轴|生产者/.test(query)&&/资产/.test(query))return '资产结构与口径核对';
  const first=query.split(/[。\n；;]/)[0].replace(/^(请|帮我|基于当前节点|基于当前)/,'').trim();
  return first.length>28?first.slice(0,27)+'…':first||'新的分析';
}
export function businessNodeDescription(node){
  const summary=node?.receipt?.visualReport?.summary||'';
  return summary.length>48?summary.slice(0,47)+'…':summary;
}
/** Independent receipt facts: a model answer or zero Python exit never proves report delivery. */
export function explorationDeliveryStages(events=[],receipt={}){
  const state={model:'待响应',data:'待确认',execution:'未执行',report:'未交付'};
  let latestPythonOperation=null;
  for(const event of events){
    let body=event.payload||event;
    try{if(typeof body==='string')body=JSON.parse(body);}catch{continue;}
    const payload=body.payload||body,meta=payload.metadata||{},worker=meta.workerActivity||payload.workerActivity||payload;
    const type=String(worker.type||meta.codingEventType||meta.eventKind||event.type||'').toUpperCase();
    if(type==='MODEL_STARTED')state.model='等待响应';
    if(type==='PLAN_READY'||type==='MODEL_TIMING'&&worker.status==='SUCCEEDED')state.model='已响应';
    if(type==='MODEL_FAILED')state.model='响应失败';
    if(type==='CODING_INPUT_READY'||meta.eventKind==='CODING_INPUT_READY')state.data='快照已就绪';
    if(type==='TOOL_FINISHED'&&worker.tool==='input_read'&&worker.status==='SUCCEEDED')state.data='已读取';
    if(type==='TOOL_STARTED'&&worker.tool==='python_execute'){state.execution='执行中';latestPythonOperation=worker.operationId||null;}
    if(type==='TOOL_FINISHED'&&worker.tool==='python_execute'&&worker.executorStopped===true){
      state.execution=worker.status==='SUCCEEDED'?'进程已结束':'执行失败';
      state.report=worker.resultAvailable===false?'交付未通过':'待校验';
      if(worker.resultAvailable===true&&worker.resultDatasetRef&&worker.validationRecordRef&&worker.evidenceBundleRef)state.report='已归档';
    }
  }
  const archivedExecution=receipt.visualReport?.execution;
  if(archivedExecution?.inputSnapshotRef&&state.data==='待确认')state.data='快照已就绪';
  if(archivedExecution?.executorStopped===true&&archivedExecution.operationId
    &&(state.execution==='未执行'||latestPythonOperation===archivedExecution.operationId))state.execution='进程已结束';
  if(receipt.terminal===true){
    if(state.model==='等待响应')state.model='已停止';
    if(state.execution==='执行中')state.execution='最终状态待核对';
  }
  if(receipt.executionMode==='TEXT_ONLY')state.execution='无需 Python';
  if(receipt.resultAvailable===true){
    const requirement=receipt.visualReport?.requirementStatus||receipt.results?.[0]?.analysisStatus;
    state.report=requirement==='INPUT_REQUIRED'?'已归档 · 需补充数据':requirement==='PARTIAL'||receipt.visualReport?.status==='PARTIAL'?'部分归档':'已归档';
  }
  else if(receipt.terminal===true&&receipt.resultAvailable===false)state.report='交付未通过';
  return [{key:'model',label:'模型响应',value:state.model},{key:'data',label:'数据供给',value:state.data},
    {key:'execution',label:'Python 执行',value:state.execution},{key:'report',label:'报告交付',value:state.report}];
}
/** Display only. Original precision and units remain available in the report and tooltip. */
export function explorationMetricDisplay(value){
  const text=String(value??'');
  if(!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(text))return text;
  const significant=text.split(/e/i)[0].replace(/[-.]/g,'').replace(/^0+/,'').replace(/0+$/,'').length;
  if(/[.e]/i.test(text)&&significant>15)return text;
  const number=Number(text),magnitude=Math.abs(number);
  if(!Number.isFinite(number)||magnitude>Number.MAX_SAFE_INTEGER)return text;
  if(magnitude>0&&magnitude<1e-8)return number.toExponential(2);
  return new Intl.NumberFormat('zh-CN',{maximumFractionDigits:magnitude>0&&magnitude<.01?8:2}).format(number);
}
export function explorationNodeState(node,runningId=''){
  const receipt=node?.receipt||{},status=String(receipt.status||node?.status||'PREPARED').toUpperCase();
  if(['CANCELLED','CANCELED','TIMEOUT_CANCELLED','KILLED'].includes(status))return {code:'CANCELLED',label:'已停止'};
  if(status==='BUDGET_EXCEEDED')return {code:'FAILED',label:'预算已耗尽'};
  const requirement=receipt.visualReport?.requirementStatus||receipt.results?.[0]?.analysisStatus;
  if(receipt.resultAvailable===true&&requirement==='INPUT_REQUIRED'&&['COMPLETED','SUCCEEDED','PARTIAL'].includes(status))return {code:'COMPLETED',label:'需补充数据'};
  if(receipt.resultAvailable===true&&requirement==='PARTIAL'&&['COMPLETED','SUCCEEDED','PARTIAL'].includes(status))return {code:'COMPLETED',label:'部分交付'};
  if(['FAILED','REJECTED','INPUT_REQUIRED','RECOVERABLE','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED'].includes(status))return {code:'FAILED',label:status==='RECOVERABLE'?'可恢复':'分析未完成'};
  if(['COMPLETED','SUCCEEDED','PARTIAL'].includes(status))return {code:'COMPLETED',label:status==='PARTIAL'||receipt.visualReport?.status==='PARTIAL'?'部分交付':receipt.resultAvailable?'已完成':'分析已结束'};
  if(receipt.terminal!==true&&(receipt.cancellationRequested||['CANCELLING','CANCEL_REQUESTED','STOPPING'].includes(status)))return {code:'STOPPING',label:'正在停止'};
  if(node?.id===runningId || (receipt.agentTaskId||receipt.taskId) && receipt.terminal!==true)return {code:'RUNNING',label:receipt.resultAvailable?'已有阶段成果 · 分析中':'正在分析'};
  if(receipt.resultAvailable)return {code:'COMPLETED',label:'已完成'};
  return {code:'QUEUED',label:'等待分析'};
}
export function explorationCanvasNode(nodes,selectedId){
  const selected=nodes.find(node=>node.id===selectedId);if(!selected)return null;
  if(selected.receipt?.resultAvailable)return selected;
  // Keep this task's stage evidence, but never present another task as a failed task's result.
  if(['FAILED','COMPLETED'].includes(explorationNodeState(selected).code))return null;
  const seen=new Set([selected.id]);let parent=selected.parentId;
  while(parent&&!seen.has(parent)){seen.add(parent);const node=nodes.find(n=>n.id===parent);if(!node)break;if(node.receipt?.resultAvailable)return node;parent=node.parentId;}
  return [...nodes].reverse().find(node=>node.receipt?.resultAvailable)||null;
}
/** Status polls preserve immutable artifact/component identities and ignore late workspace revisions. */
export function reconcileExploration(previous,next){
  if(!previous||previous.id!==next.id)return next;
  if(Number(next.version)<Number(previous.version))return previous;
  const existing=new Map(previous.nodes.map(node=>[node.id,node]));
  return {...next,nodes:next.nodes.map(node=>{
    const old=existing.get(node.id);if(!old)return node;
    if(JSON.stringify(old)===JSON.stringify(node))return old;
    return JSON.stringify(old.receipt)===JSON.stringify(node.receipt)?{...node,receipt:old.receipt}:node;
  })};
}
export function explorationTreeRows(nodes,collapsed=[]){
  const byId=new Map(nodes.map(n=>[n.id,n])),children=new Map(),hidden=new Set(collapsed),rows=[],visited=new Set();
  for(const node of nodes){const parent=byId.has(node.parentId)&&node.parentId!==node.id?node.parentId:'';
    if(!children.has(parent))children.set(parent,[]);children.get(parent).push(node);}
  function visit(node,depth){
    if(visited.has(node.id))return;visited.add(node.id);
    const descendants=children.get(node.id)||[];
    rows.push({...node,depth,hasChildren:!!descendants.length,expanded:!hidden.has(node.id),businessTitle:businessNodeTitle(node),description:businessNodeDescription(node)});
    if(!hidden.has(node.id))descendants.forEach(n=>visit(n,depth+1));
  }
  (children.get('')||[]).forEach(n=>visit(n,0));
  const inCollapsedBranch=node=>{const seen=new Set();let p=node.parentId;while(byId.has(p)&&!seen.has(p)){if(hidden.has(p))return true;seen.add(p);p=byId.get(p).parentId;}return false;};
  for(const node of nodes)if(!visited.has(node.id)&&!inCollapsedBranch(node))visit(node,0);
  return rows;
}
export function sortedTableRows(rows,column,direction){
  if(column==null)return rows.slice();
  const numeric=v=>typeof v==='number'||typeof v==='string'&&/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(v);
  return rows.map((row,index)=>({row,index})).sort((a,b)=>{
    const x=a.row[column],y=b.row[column];let result;
    if(x==null||y==null)result=x==null?(y==null?0:1):-1;
    else result=numeric(x)&&numeric(y)?Number(x)-Number(y):String(x).localeCompare(String(y),'zh-CN',{numeric:true});
    return (direction==='desc'?-result:result)||a.index-b.index;
  }).map(item=>item.row);
}
