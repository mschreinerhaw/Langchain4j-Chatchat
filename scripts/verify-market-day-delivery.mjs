import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';
const origin=process.env.RUNTIME_TEST_ORIGIN||'http://192.168.195.221:18100';
const out=resolve(process.env.RUNTIME_TEST_OUTPUT||'target/market-day-delivery-live');await mkdir(out,{recursive:true});
let token;const result={passed:false,scope:'REAL_DEPLOYED_MARKET_TO_NATIVE',startedAt:new Date().toISOString()};
async function api(path,body){const response=await fetch(origin+'/api/v1'+path,{method:body!==undefined?'POST':'GET',headers:{'Content-Type':'application/json','X-Workspace-Id':'data-exploration',...(token?{Authorization:'Bearer '+token}:{})},body:body!==undefined?JSON.stringify(body):undefined});const envelope=await response.json();assert.equal(response.status,200,path+': HTTP '+response.status);assert.equal(envelope.code,200,path+': '+envelope.message);return envelope.data;}
try {
 assert.ok(process.env.RUNTIME_TEST_USER&&process.env.RUNTIME_TEST_PASSWORD,'Login via environment required');
 token=(await api('/enterprise/auth/login',{username:process.env.RUNTIME_TEST_USER,password:process.env.RUNTIME_TEST_PASSWORD})).token;
 const options=await api('/data-science/explorations/agents');assert.ok(options.some(x=>x.id==='financial-news-agent'));
 let space=await api('/data-science/explorations',{title:'完整本地行情供数回归'});
 space=await api(`/data-science/explorations/${space.id}/nodes`,{version:space.version,agentId:'financial-news-agent',fileIds:[],question:'分析雪龙集团股票，结合系统已有的本地行情数据进行分析。核对实际收到的数据日期、来源及行数，说明缺失数据，不要编造行情；通过 Python 读取实际交付的行情文件进行计算，给出简洁报告。本次无需联网。',reportPresentation:{styleProfile:'financial-cn',scenario:'financial-analysis'}});
 const nodeId=space.nodes.at(-1).id;result.workspaceId=space.id;result.nodeId=nodeId;
 await api(`/data-science/explorations/${space.id}/nodes/${nodeId}/execute`,{});
 const seen=new Map();let receipt;
 for(const deadline=Date.now()+1200000;Date.now()<deadline;){space=await api(`/data-science/explorations/${space.id}/nodes/${nodeId}/status`);receipt=space.nodes.find(x=>x.id===nodeId).receipt;for(const event of receipt.analysisEvents||[])seen.set(event.sequence,event);await writeFile(resolve(out,'progress.json'),JSON.stringify({workspaceId:space.id,nodeId,status:receipt.status,agentTaskId:receipt.agentTaskId,eventCount:seen.size}));if(receipt.terminal)break;await new Promise(r=>setTimeout(r,3000));}
 await writeFile(resolve(out,'receipt.json'),JSON.stringify(receipt,null,2));const events=[...seen.values()].sort((a,b)=>a.sequence-b.sequence);await writeFile(resolve(out,'events.json'),JSON.stringify(events,null,2));
 result.agentTaskId=receipt.agentTaskId;result.status=receipt.status;result.eventCount=events.length;
 const types=events.map(x=>x.payload?.metadata?.workerActivity?.normalizedType).filter(Boolean);result.nativeTypes=[...new Set(types)];
 assert.ok(!JSON.stringify(receipt).includes('DAY_SNAPSHOT_ROW_LIMIT'),'Daily row limit must be removed');
 assert.ok(types.includes('MODEL_REQUEST_STARTED'),'Task must actually reach Native Cline');
 assert.ok(types.includes('COMMAND_COMPLETED'),'Native Python/shell execution must produce real receipts');
 assert.ok(receipt.terminal&&receipt.resultAvailable,'Task must finish with registered delivery');result.passed=true;
} catch(error){result.error=error.message;throw error;} finally {result.finishedAt=new Date().toISOString();await writeFile(resolve(out,'verification.json'),JSON.stringify(result,null,2));}
