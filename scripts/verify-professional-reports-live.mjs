// Uses the existing authenticated task/upload/delivery APIs; no storage changes.
import {mkdir,writeFile,readFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
const out=resolve(process.env.RUNTIME_TEST_OUTPUT||'/tmp/professional-live');await mkdir(out,{recursive:true});
const base=process.env.RUNTIME_TEST_ORIGIN||'http://127.0.0.1:18180';let token;
const caseFilter=process.argv.includes('--case')?process.argv[process.argv.indexOf('--case')+1]:null;
async function api(path,body){const multipart=body instanceof FormData;const response=await fetch(base+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{...(multipart?{}:{'content-type':'application/json'}),...(token?{authorization:'Bearer '+token}:{})},body:body===undefined?undefined:multipart?body:JSON.stringify(body)});if(!response.ok)throw Error(path+': HTTP '+response.status);const result=await response.json();if(result.code!==200)throw Error(path+': '+result.message);return result.data;}
token=(await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD})).token;
if(process.argv.includes('--cancel-case')){
 const name=process.argv[process.argv.indexOf('--cancel-case')+1];if(!['client-assets','operations'].includes(name))throw Error('Known acceptance case required');
 const task=JSON.parse(await readFile(out+'/'+name+'-task.json','utf8'));const result=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/cancel`,{});await writeFile(out+'/'+name+'-cancel.json',JSON.stringify(result,null,2));console.log(JSON.stringify({name,cancellationRequested:true}));
}else if(process.argv.includes('--submit')){
 const cases=[['client-assets','category,amount\ncash,20\nstock,30\nfund,50\n','Analyze client asset allocation and concentration; cash flow history and risk suitability metadata are unavailable.'],['operations','month,revenue\n2026-01,100\n2026-02,120\n2026-03,110\n','Analyze operating revenue evolution; costs, prior-year data and seasonality are unavailable.']];
 for(const [name,csv,objective]of cases.filter(c=>!caseFilter||c[0]===caseFilter)){
  const form=new FormData();form.append('file',new Blob([csv],{type:'text/csv'}),name+'-SYNTHETIC.csv');const input=await api('/interactions/user-assets',form);
  const request={requestId:'professional-'+name+'-'+randomUUID(),fileIds:[input.id],modelName:process.env.RUNTIME_TEST_MODEL||'qwen3.7-plus',reportMode:'visual',artifactTypes:['REPORT','RESULT'],instruction:'This is an explicitly SYNTHETIC professional-report acceptance task, never production customer data. '+objective+' Use Python to calculate from the uploaded CSV. Choose applicable platform professional report Skills autonomously when advertised in native discovery; record missing capability rather than claiming to load an absent Skill. Deliver a professional responsive offline HTML report, evidence mappings, manifest, charts with data and actual automated quality results through the existing delivery selection. Prefer shared report resources and ECharts through the existing frontend-design task-bound resource catalog/preview transport. Explain sample limitations. Do not invent absent values. If professional-report helpers are available, reuse their code and retain actual calculations/source snapshots and original cell bindings. Do not claim a passed render check without a real browser receipt. Deliver RESULT JSON with caseName, dataStatus SYNTHETIC_TEST_ONLY and actual computed total. The expected totals are not supplied: independently compute them. Keep no-report and shared configurations unchanged.'};
  await writeFile(out+'/'+name+'-request.json',JSON.stringify(request,null,2));const task=await api('/coding/tasks',request);await writeFile(out+'/'+name+'-task.json',JSON.stringify(task,null,2));console.log(JSON.stringify({name,taskId:task.taskId,runId:task.runId}));
 }
}else{
 for(const [name,expected]of [['client-assets',100],['operations',330]].filter(c=>!caseFilter||c[0]===caseFilter)){
  const task=JSON.parse(await readFile(out+'/'+name+'-task.json','utf8'));const detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`);await writeFile(out+'/'+name+'-detail.json',JSON.stringify(detail,null,2));
  console.log(JSON.stringify({name,status:detail.outcome.status,reason:detail.outcome.reasonCode}));
  if(detail.outcome.status==='COMPLETED'){
   const delivery=await api(`/coding/tasks/runs/${task.runId}/${task.taskId}/delivery`);await writeFile(out+'/'+name+'-delivery.json',JSON.stringify(delivery,null,2));
   const report=delivery.visualReport?.htmlReport;if(!report)throw Error('No archived HTML projection');
   const bundle=await api(`/interactions/user-assets/${report.entryFileId}/report-bundle`);await writeFile(out+'/'+name+'-bundle.json',JSON.stringify(bundle,null,2));
   let totalVerified=false;
   for(const file of bundle.files){const bytes=Buffer.from(file.content,'base64');if(createHash('sha256').update(bytes).digest('hex')!==file.sha256)throw Error('Authenticated readback hash mismatch');if(file.path.endsWith('.json')){try{const data=JSON.parse(bytes.toString());if(data.total===expected)totalVerified=true;}catch{}}}
   await writeFile(out+'/'+name+'-readback.json',JSON.stringify({totalVerified,expected,files:bundle.files.map(({content,...file})=>file)},null,2));if(!totalVerified)throw Error('Computed total not verified in returned bundle');
  }
 }
}
