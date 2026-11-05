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
/** Display only. Original precision and units remain available in the report and tooltip. */
export function explorationMetricDisplay(value){
  const text=String(value??'');
  if(!/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(text))return text;
  const number=Number(text),magnitude=Math.abs(number);
  if(!Number.isFinite(number)||magnitude>Number.MAX_SAFE_INTEGER)return text;
  if(magnitude>0&&magnitude<1e-8)return number.toExponential(2);
  return new Intl.NumberFormat('zh-CN',{maximumFractionDigits:magnitude>0&&magnitude<.01?8:2}).format(number);
}
export function explorationNodeState(node,runningId=''){
  const receipt=node?.receipt||{},status=String(receipt.status||node?.status||'PREPARED').toUpperCase();
  if(['CANCELLED','CANCELED','TIMEOUT_CANCELLED','KILLED'].includes(status))return {code:'CANCELLED',label:'已停止'};
  if(['FAILED','REJECTED','INPUT_REQUIRED','RECOVERABLE','TIME_BUDGET_EXHAUSTED','MODEL_BUDGET_EXHAUSTED'].includes(status))return {code:'FAILED',label:status==='RECOVERABLE'?'可恢复':'分析未完成'};
  if(['SUCCEEDED','PARTIAL'].includes(status))return {code:'COMPLETED',label:receipt.resultAvailable?'已完成':'分析已结束'};
  if(receipt.terminal!==true&&(receipt.cancellationRequested||['CANCELLING','CANCEL_REQUESTED','STOPPING'].includes(status)))return {code:'STOPPING',label:'正在停止'};
  if(node?.id===runningId || receipt.agentTaskId && receipt.terminal!==true)return {code:'RUNNING',label:receipt.resultAvailable?'已有阶段成果 · 分析中':'正在分析'};
  if(receipt.resultAvailable)return {code:'COMPLETED',label:'已完成'};
  return {code:'QUEUED',label:'等待分析'};
}
export function explorationCanvasNode(nodes,selectedId){
  const selected=nodes.find(node=>node.id===selectedId);if(!selected)return null;
  if(selected.receipt?.resultAvailable)return selected;
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
