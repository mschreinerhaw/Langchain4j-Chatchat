// Reuse an actual, authenticated prior synthetic report to test the completed lifecycle.
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {resolve,dirname,sep} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
const out=resolve(process.env.RUNTIME_TEST_OUTPUT||'/tmp/professional-finalization');await mkdir(out,{recursive:true});
const base=process.env.RUNTIME_TEST_ORIGIN||'http://127.0.0.1:18180';let token;
async function api(path,body){const multipart=body instanceof FormData;const r=await fetch(base+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{...(multipart?{}:{'content-type':'application/json'}),...(token?{authorization:'Bearer '+token}:{})},body:body===undefined?undefined:multipart?body:JSON.stringify(body)});if(!r.ok)throw Error(path+': HTTP '+r.status);const e=await r.json();if(e.code!==200)throw Error(e.message);return e.data;}
token=(await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD})).token;
if(process.argv.includes('--cancel')){
 const task=JSON.parse(await readFile(out+'/task.json','utf8'));const state=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/cancel`,{});await writeFile(out+'/cancel.json',JSON.stringify(state,null,2));console.log('Acceptance task cancellation requested');
}else if(process.argv.includes('--submit')){
 const file=await readFile(process.env.REPORT_SOURCE_ZIP);const form=new FormData();form.append('file',new Blob([file],{type:'application/zip'}),'operations-SYNTHETIC-report.zip');const input=await api('/interactions/user-assets',form);
 const request={requestId:'professional-final-'+randomUUID(),fileIds:[input.id],modelName:'qwen3.7-plus',reportMode:'visual',artifactTypes:['REPORT','RESULT'],instruction:'The supplied ZIP is an actual authenticated Silo readback of an earlier SYNTHETIC operations report, not real customer data. Its source CSV, source_data.json and report_model.json contain the original observations; reuse them. Its quality record honestly reported missing local ECharts and pending rendering. Complete the report using the currently advertised report-designer and evidence-reviewer guidance and their reusable preparation/finalization helpers. Safely extract into your authorized output folder. Reuse the supplied model and actual source evidence, adapt layout if useful, and compute the total from actual CSV observations in Python. Deliver RESULT JSON with caseName operations-finalized, dataStatus SYNTHETIC_TEST_ONLY and total. Use shared pinned assets instead of recreating CSS or downloading public packages. Complete actual desktop/mobile/PDF validation through the existing frontend-design transport, repair technical pending checks where the advertised tools can resolve them, and preserve the real quality state. Deliver report.html and its evidence, manifest, chart data, quality result and PDF through the existing delivery selection. Runtime performs Silo archival. No customer APIs or shared configuration changes are needed.'};
 await writeFile(out+'/request.json',JSON.stringify(request,null,2));const task=await api('/coding/tasks',request);await writeFile(out+'/task.json',JSON.stringify(task,null,2));console.log(JSON.stringify({taskId:task.taskId,runId:task.runId}));
}else{
 const task=JSON.parse(await readFile(out+'/task.json','utf8'));const detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`);await writeFile(out+'/detail.json',JSON.stringify(detail,null,2));console.log(JSON.stringify({status:detail.outcome.status,node:detail.nodeId,executions:detail.executions?.length}));
 if(detail.outcome.status==='COMPLETED'){
  const delivery=await api(`/coding/tasks/runs/${task.runId}/${task.taskId}/delivery`);await writeFile(out+'/delivery.json',JSON.stringify(delivery,null,2));
  const entry=delivery.visualReport?.htmlReport;if(!entry)throw Error('No actual HTML projection');
  const bundle=await api(`/interactions/user-assets/${entry.entryFileId}/report-bundle`);await writeFile(out+'/bundle.json',JSON.stringify(bundle,null,2));
  const files=new Map();for(const f of bundle.files){const bytes=Buffer.from(f.content,'base64');if(bytes.length!==f.size||createHash('sha256').update(bytes).digest('hex')!==f.sha256)throw Error('Archived byte hash/size differs');files.set(f.path,bytes);}
  const named=name=>[...files].find(([path])=>path.split('/').at(-1)===name);
  const primary=named('report.html'),quality=named('quality_report.json'),manifest=named('report_manifest.json');
  if(!primary||!quality||!manifest||!named('evidence.json'))throw Error('Professional companion missing');
  // The existing HTML viewer bundle deliberately excludes standalone PDF assets.
  const pdf=(delivery.artifacts??[]).find(f=>f.fileName==='report.pdf');if(!pdf)throw Error('Archived print PDF missing');
  const pdfResponse=await fetch(base+'/api/v1/interactions/user-assets/'+pdf.id+'/content',{headers:{authorization:'Bearer '+token}});if(!pdfResponse.ok)throw Error('Authenticated PDF readback failed');
  const pdfBytes=Buffer.from(await pdfResponse.arrayBuffer());if(pdfBytes.length!==pdf.size||createHash('sha256').update(pdfBytes).digest('hex')!==pdf.sha256||pdfBytes.subarray(0,5).toString()!=='%PDF-')throw Error('Archived PDF integrity differs');await writeFile(out+'/report.pdf',pdfBytes);
  const q=JSON.parse(quality[1]),m=JSON.parse(manifest[1]);if(q.status!=='PASSED'||q.errors.length||q.pending.length)throw Error('Final technical checks did not pass');
  if(q.browserCheckedHtmlSha256!==createHash('sha256').update(primary[1]).digest('hex'))throw Error('Final browser hash differs');
  if(!files.has(bundle.report.entryPath)||!files.get(bundle.report.entryPath).equals(primary[1]))throw Error('Existing viewer entry differs from report.html');
  let totalVerified=false;for(const [path,bytes] of files)if(path.endsWith('.json')){try{const v=JSON.parse(bytes);if(v.total===330&&v.dataStatus==='SYNTHETIC_TEST_ONLY')totalVerified=true;}catch{}}
  if(!totalVerified)throw Error('Actual computed total not verified');
  const archive=resolve(out,'archived'),inventory=[];
  for(const item of delivery.artifacts??[]){
   if(!item.sourceRef?.startsWith('coding-node:'))continue;
   const path=item.sourceRef.split(':').slice(2).join(':'),target=resolve(archive,path);
   if(!target.startsWith(archive+sep)||path.split('/').includes('..'))throw Error('Unsafe archived artifact path');
   let bytes=files.get(path);
   if(!bytes){const response=await fetch(base+'/api/v1/interactions/user-assets/'+item.id+'/content',{headers:{authorization:'Bearer '+token}});if(!response.ok)throw Error('Artifact readback failed');bytes=Buffer.from(await response.arrayBuffer());}
   if(bytes.length!==item.size||createHash('sha256').update(bytes).digest('hex')!==item.sha256)throw Error('Full artifact readback digest differs');
   await mkdir(dirname(target),{recursive:true});await writeFile(target,bytes);inventory.push({path,id:item.id,sha256:item.sha256,size:item.size});
  }
  await writeFile(out+'/archive-inventory.json',JSON.stringify(inventory,null,2));
  await writeFile(out+'/verification.json',JSON.stringify({status:'PASSED',taskId:task.taskId,runId:task.runId,nodeId:detail.nodeId,totalVerified,qualityStatus:q.status,resourceVersion:m.resources.version,viewerEntry:bundle.report.entryPath,pdf:{id:pdf.id,sha256:pdf.sha256,size:pdf.size},files:bundle.files.map(({content,...f})=>f)},null,2));console.log('PROFESSIONAL_LIFECYCLE_READBACK_PASSED');
 }
}
