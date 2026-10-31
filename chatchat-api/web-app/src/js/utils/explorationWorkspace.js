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
