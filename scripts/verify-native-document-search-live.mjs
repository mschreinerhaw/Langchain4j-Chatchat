/** Real model + existing document_search + Agent ACL + Silo delivery acceptance. */
import assert from 'node:assert/strict';
import {mkdir,readFile,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
const origin=process.env.RUNTIME_TEST_ORIGIN||'http://192.168.195.221:18100',out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/native-document-search-live');await mkdir(out,{recursive:true});
let auth,task;const result={passed:false,scope:'REAL_NATIVE_DOCUMENT_SEARCH_AGENT_ACL_DELIVERY',startedAt:new Date().toISOString()};
async function api(path,body,raw=false){const r=await fetch(origin+'/api/v1'+path,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(auth?{Authorization:'Bearer '+auth.token}:{})},body:body===undefined?undefined:JSON.stringify(body)});if(r.status!==200&&r.status!==202){const error=new Error(path+': HTTP '+r.status);error.status=r.status;throw error;}if(raw)return Buffer.from(await r.arrayBuffer());const e=await r.json();assert.equal(e.code,200,e.message);return e.data;}
try{
 const fixture=JSON.parse(await readFile(process.env.RUNTIME_TEST_DOCUMENT_FIXTURE,'utf8'));
 auth=await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD});
 // Authentication readiness does not imply that the dynamic MCP catalog has registered.
 let ready=false;for(const deadline=Date.now()+180000;Date.now()<deadline;){const catalog=await api('/agents/workshop');if(catalog.registeredMcpTools?.some(t=>t.localToolName==='mcp_chatchat_mcp_server_document_search')){ready=true;break;}await new Promise(r=>setTimeout(r,2000));}assert.ok(ready,'Authorized MCP catalog must be ready before starting a model task');
 const submission={requestId:'doc-search-'+randomUUID(),fileIds:[],agentId:fixture.agent.id,modelName:fixture.agent.modelName,artifactTypes:['REPORT','RESULT'],instruction:'According to the assigned SYNTHETIC Aster guide '+fixture.nonce+', who owns maintenance approval, how many calendar days in advance must a request arrive, and what approval reference code is specified? Cite supporting document sources and distinguish any missing facts. Use only authorized internal documents; Internet information cannot answer this internal question. Deliver a concise Markdown answer and a JSON result with the answer and citations. Choose the needed tools yourself.'};
 for(const deadline=Date.now()+180000;;){try{task=await api('/coding/tasks',submission);break;}catch(error){if(error.status!==503||Date.now()>=deadline)throw error;result.admissionRetries=(result.admissionRetries||0)+1;await new Promise(r=>setTimeout(r,3000));}}
 result.taskId=task.taskId;result.runId=task.runId;await writeFile(resolve(out,'task.json'),JSON.stringify(task,null,2));let detail;
 for(const deadline=Date.now()+900000;Date.now()<deadline;){detail=await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}`);await writeFile(resolve(out,'progress.json'),JSON.stringify({taskId:task.taskId,runId:task.runId,status:detail.outcome.status,summary:detail.outcome.summary}));if(!['CREATED','RUNNING'].includes(detail.outcome.status))break;await new Promise(r=>setTimeout(r,2000));}
 await writeFile(resolve(out,'detail.json'),JSON.stringify(detail,null,2));result.status=detail.outcome.status;
 result.modelReceipts=(detail.modelReceipts||[]).map(({eventType,requestId,providerRequestId,model,status,usage,durationMs,httpStatus})=>({eventType,requestId,providerRequestId,model,status,usage,durationMs,httpStatus}));
 const files=[];
 for(let offset=0;;offset+=100){const all=await api('/interactions/user-assets?personalOnly=false&taskId='+encodeURIComponent(task.taskId)+'&limit=100&offset='+offset);const page=Array.isArray(all)?all:all.items||all.files||[];files.push(...page);if(page.length<100)break;}
 const deliveries=[],receipts=[];
 for(const file of files){if(!/\.md$|\.json$/i.test(file.fileName)||file.size>2000000)continue;if(!['ARTIFACT','CODE'].includes(file.kind)&&!file.fileName.includes('document-search-receipt'))continue;
  const bytes=await api('/interactions/user-assets/'+file.id+'/content',undefined,true);assert.equal(createHash('sha256').update(bytes).digest('hex'),file.sha256);const name=deliveries.length+'-'+file.fileName;await writeFile(resolve(out,name),bytes);deliveries.push({name,sha256:file.sha256,size:bytes.length,kind:file.kind});if(file.fileName.includes('document-search-receipt'))receipts.push(JSON.parse(bytes.toString()));
 }
 const texts=await Promise.all(deliveries.filter(f=>f.kind==='ARTIFACT').map(f=>readFile(resolve(out,f.name),'utf8'))),answer=texts.join('\n');
 assert.ok(['COMPLETED','PARTIAL'].includes(detail.outcome.status)&&detail.outcome.resultDatasetRef,'Need an actual registered delivery');
 assert.ok(receipts.length>0,'Need actual document_search receipts, not an asserted model statement');
 const evidence=receipts.flatMap(r=>r.results||[]);assert.ok(evidence.length>0);assert.ok(evidence.every(r=>r.fileId===fixture.documents[0].docId),'Only the Agent-assigned document may be returned');
 assert.ok(!JSON.stringify(receipts).includes(fixture.expected.forbidden),'Unassigned document canary must never be disclosed');assert.ok(!answer.includes(fixture.expected.forbidden));
 assert.ok(answer.includes(fixture.expected.team)&&answer.includes(fixture.expected.reference)&&/\b7\b/.test(answer),'Check independent source facts');
 assert.ok(receipts.some(r=>r.citations?.length>0),'Need source citations');
 Object.assign(result,{passed:true,artifacts:deliveries,documentSearchReceiptCount:receipts.length,returnedDocumentIds:[...new Set(evidence.map(r=>r.fileId))],unassignedDocumentExcluded:true,independentSourceFactsVerified:true});
}catch(error){result.error=error.message;throw error;}finally{result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));if(!result.passed&&task&&auth)try{await api(`/coding-monitor/tasks/${task.runId}/${task.taskId}/cancel`,{});}catch{}}
