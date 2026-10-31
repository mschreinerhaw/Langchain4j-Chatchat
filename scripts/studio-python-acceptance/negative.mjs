import fs from 'node:fs';import assert from 'node:assert/strict';import crypto from 'node:crypto';const origin=process.env.STUDIO_ORIGIN,authFile=process.env.STUDIO_AUTH_FILE;if(!origin||!authFile)throw Error('Set STUDIO_ORIGIN and STUDIO_AUTH_FILE');const auth=JSON.parse(fs.readFileSync(authFile));async function api(path,method='GET',data){const response=await fetch(origin+'/api/v1'+path,{method,headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science','Content-Type':'application/json'},body:data===undefined?undefined:JSON.stringify(data)});const value=await response.json();if(!response.ok||value.code!==200)throw Error(JSON.stringify({path,status:response.status,...value}));return value.data;}
const dir=(process.env.STUDIO_OUTPUT||'target/studio-python-acceptance').replace(/[\\/]$/,'')+'/',read=n=>JSON.parse(fs.readFileSync(dir+n+'.json')),script=read('script'),schema=read('schema'),request=read('publish-request'),params=read('trial-inputs'),results=[];
const endpoint='/data-science/python/scripts/'+script.id;
let blocked=false;const changed=structuredClone(schema);changed.properties.sales_year.default=2000;
try{await api(endpoint+'/publish','POST',{...request,inputSchema:JSON.stringify(changed)});}catch(error){assert.match(error.message,/重新试运行/);blocked=true;results.push({name:'changed-schema-after-success',status:'REJECTED',reason:error.message});}assert.ok(blocked,'Changed input contract must not reuse trial evidence');
for(const [name,mutate]of [
 ['missing-annual',p=>delete p.annual_rows],['empty-monthly',p=>p.monthly_rows=[]],
 ['duplicate-month-key',p=>p.monthly_rows.push({...p.monthly_rows[0]})],
 ['non-finite-amount',p=>p.monthly_rows[0].total_net_paid='NaN'],
 ['invalid-month',p=>p.monthly_rows[0].sale_month=13]
]){
 const input=structuredClone(params);mutate(input);const r=await api(endpoint+'/execute','POST',{parameters:input,inputSchema:JSON.stringify(schema)});
 assert.notEqual(r.status,'SUCCEEDED',name);assert.notEqual(r.exitCode,0,name);
 results.push({name,executionId:r.id,status:r.status,exitCode:r.exitCode,stderr:r.stderr,inputSha256:crypto.createHash('sha256').update(JSON.stringify(input)).digest('hex')});
}
let rejected=false;try{await api(endpoint+'/publish','POST',request);}catch(error){assert.match(error.message,/必须先.*成功测试/);rejected=true;results.push({name:'failed-trial-invalidates-success',status:'REJECTED',reason:error.message});}assert.ok(rejected);
const workbench=await api('/data-science/python/workbench'),current=workbench.scripts.find(s=>s.id===script.id);assert.equal(current.lastTestSucceeded,false);assert.equal(current.lastTestEvidenceJson,null);
fs.writeFileSync(dir+'negative-matrix.json',JSON.stringify({capturedAt:new Date().toISOString(),results,scriptStateAfterFailures:{status:current.status,lastTestSucceeded:current.lastTestSucceeded,lastTestEvidenceJson:current.lastTestEvidenceJson}},null,2));
console.log('Verified changed-schema rejection, five real failing Sandbox executions and stale-success rejection.');
