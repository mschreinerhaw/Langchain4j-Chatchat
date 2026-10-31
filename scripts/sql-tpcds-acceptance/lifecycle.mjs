/** Run against this acceptance run's own monthly template only. */
import fs from 'node:fs';import path from 'node:path';import assert from 'node:assert/strict';import crypto from 'node:crypto';
const origin=process.env.TPCDS_ORIGIN,authFile=process.env.TPCDS_AUTH_FILE,out=path.resolve(process.env.TPCDS_OUTPUT||'target/tpcds-live-acceptance');
assert.ok(origin&&authFile,'Set TPCDS_ORIGIN and TPCDS_AUTH_FILE');
const auth=JSON.parse(fs.readFileSync(authFile)),state=JSON.parse(fs.readFileSync(path.join(out,'state.json')));
const save=(name,value)=>fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(value,null,2));
const root='/data-science/sql/projects/'+state.project.id+'/files/'+state.monthly.file.id+'/template-publications';
async function api(route,method='GET',body){
 const r=await fetch(origin+'/api/v1'+route,{method,headers:{Authorization:'Bearer '+auth.token,'Content-Type':'application/json','X-Workspace-Id':'data-science'},body:body===undefined?undefined:JSON.stringify(body)});
 const j=await r.json();if(!r.ok||j.code!==200)throw Error(JSON.stringify({route,status:r.status,code:j.code,message:j.message}));return j.data;
}
const op=process.argv[2],parameters={start_date:'1999-01-01',end_date:'1999-12-31'};
assert.ok(['revise','test','publish','offline','rollback'].includes(op),'Use revise, test, publish, offline or rollback. Do not change releases while an Agent run is in flight.');
if(op==='revise'){
 assert.ok(!state.monthlyRevision,'Revision already saved; resume with test.');
 const schema=structuredClone(state.monthly.row.parameterSchema);schema.properties.min_quantity.default=50;
 state.monthlyRevision=await api(root,'POST',{operationId:crypto.randomUUID(),groupId:state.monthly.row.groupId,expectedSourceVersion:state.monthly.file.version,title:'TPCDS monthly quantity >= 50',description:'Acceptance revision: default quantity threshold 50; same model-generated SQL.',category:state.monthly.row.category,datasourceId:state.source.id,databaseType:'mysql',parameterSchema:schema});
 console.log('Authorize this exact new revision code through existing datasource management: '+state.monthlyRevision.code);
}
if(['test','publish'].includes(op)){
 assert.ok(state.monthlyRevision,'Create a revision first.');
 const current=(await api(root)).find(r=>r.id===state.monthlyRevision.id);assert.ok(current);
 const head=(await api(root)).find(r=>r.active);
 state.monthlyRevision=await api(root+'/'+current.id+'/'+op,'POST',{expectedVersion:current.expectedVersion,expectedSourceVersion:state.monthly.file.version,testId:crypto.randomUUID(),parameters,expectedActiveReleaseId:head?.publication?.id||''});
 assert.equal(state.monthlyRevision.testEvidence?.success,true);
 if(op==='publish')assert.equal(state.monthlyRevision.publication.toolName,state.monthly.row.publication.toolName,'MCP identity must remain stable');
 save('revision-'+op,state.monthlyRevision);
}
if(op==='offline'){
 const current=(await api(root)).find(r=>r.id===state.monthlyRevision?.id);assert.ok(current?.active,'The acceptance revision must be current');
 const release=await api('/tool-publications/capabilities/publications/'+current.publication.id+'/offline','POST',{});
 const row=(await api(root)).find(r=>r.id===current.id);assert.equal(row.active,false);
 save('offline',{release,row});state.monthlyRevision=row;
 console.log('Release and SQL definition withdrawn. Verify actual Agent rejection before rollback.');
}
if(op==='rollback'){
 const all=await api(root);assert.ok(!all.some(r=>r.active),'Offline the acceptance revision before restoring this original revision.');
 const original=all.find(r=>r.id===state.monthly.row.id);assert.ok(original);
 const row=await api(root+'/'+original.id+'/rollback','POST',{expectedVersion:original.expectedVersion,expectedActiveReleaseId:'',testId:crypto.randomUUID(),parameters});
 assert.equal(row.active,true);assert.equal(row.testEvidence.success,true);assert.equal(row.publication.toolName,state.monthly.row.publication.toolName);
 state.monthly.row=row;save('rollback',row);
 console.log('Original contract restored after real trial. Verify actual Agent rows independently.');
}
save('state',state);
