/** Real Studio API workflow. SQL comes from the model, never from this harness. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const [command,name]=process.argv.slice(2);
const {STUDIO_ORIGIN:origin,STUDIO_AUTH_FILE:authFile,STUDIO_SCENARIO:scenarioFile,STUDIO_OUTPUT:output}=process.env;
assert.ok(origin&&authFile&&scenarioFile&&output,'Set STUDIO_ORIGIN, STUDIO_AUTH_FILE, STUDIO_SCENARIO and STUDIO_OUTPUT');
const auth=JSON.parse(fs.readFileSync(authFile)),scenario=JSON.parse(fs.readFileSync(scenarioFile));
fs.mkdirSync(output,{recursive:true});const file=path.join(output,'state.json');
const state=fs.existsSync(file)?JSON.parse(fs.readFileSync(file)):{};
const save=(name,value)=>fs.writeFileSync(path.join(output,name+'.json'),JSON.stringify(value,null,2));
const persist=()=>save('state',state);
async function api(route,method='GET',body){
 const response=await fetch(origin+'/api/v1'+route,{method,headers:{Authorization:'Bearer '+auth.token,'X-Workspace-Id':'data-science','Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await response.json();assert.ok(response.ok&&result.code===200,`${method} ${route}: ${response.status}/${result.code}: ${result.message}`);return result.data;
}
if(command==='setup'){
 assert.ok(!state.project,'Existing project: reuse subsequent steps instead of duplicating setup');
 state.project=await api('/data-science/sql/projects','POST',{name:scenario.projectName,dialect:scenario.dialect});persist();
 const base='/data-science/sql/projects/'+state.project.id;
 const context=await api(base+'/metadata/contexts','POST',{purpose:'QUERY',targetDialect:scenario.dialect,bindings:[{serviceId:scenario.serviceId,assetRef:scenario.datasourceId,role:'SOURCE',database:scenario.database}]});save('context',context);
 const metadata=await api(base+'/metadata/contexts/'+context.discoveryId+'/operations','POST',{bindingId:context.bindings[0].bindingId,arguments:{operation:'LIST_TABLES',database:scenario.database,pageSize:100,includeColumns:true}});save('metadata',metadata);
 const selected=metadata.metadata.items.filter(t=>scenario.tables.includes(t.tableName));assert.equal(selected.length,scenario.tables.length);
 state.mapping=await api(base+'/files','PUT',{path:'mapping/source.json',kind:'MAPPING',expectedVersion:0,content:JSON.stringify({source:'REGISTERED_METADATA',database:scenario.database,tables:selected})});persist();
}else{
 assert.ok(state.project&&state.mapping,'Run setup first');
 const definition=scenario.templates[name];assert.ok(definition,'Select a configured template name');
 const base='/data-science/sql/projects/'+state.project.id;
 if(command==='generate'){
  assert.ok(!state[name],'Generation already exists; inspect status without submitting it again');
  const generated=await api(base+'/generations','POST',{prompt:definition.prompt,dialect:scenario.dialect,mappingIds:[state.mapping.id],referenceIds:[],action:'generate'});
  state[name]={generationId:generated.generationId,prompt:definition.prompt};persist();
  await api(base+'/generations/'+generated.generationId+'/coding','POST',{});
 }else{
  const item=state[name];assert.ok(item?.generationId,'Run generate first');
  if(command==='status'||command==='apply'){
   const result=await api(base+'/generations/'+item.generationId);save(name+'-generation',result);
   if(command==='apply'){
    assert.ok(!result.outputError&&result.files?.some(f=>f.path===definition.path),'No validated model SQL yet');
    assert.ok(!item.file,'Already applied');await api(base+'/generations/'+item.generationId+'/apply','POST',{paths:[definition.path]});
    item.file=(await api(base+'/files')).find(f=>f.path===definition.path);persist();
   }
  }else{
   assert.ok(item.file,'Run apply first');const route=base+'/files/'+item.file.id+'/template-publications';
   if(command==='draft'){
    assert.ok(!item.row,'Draft already exists');item.operationId??=crypto.randomUUID();persist();
    item.row=await api(route,'POST',{operationId:item.operationId,expectedSourceVersion:item.file.version,title:definition.title,description:definition.description,category:definition.category||'business_analysis',datasourceId:scenario.datasourceId,databaseType:scenario.dialect,parameterSchema:definition.parameterSchema});persist();save(name+'-draft',item.row);
    console.log('Approve this exact template code through the existing datasource authorization UI/API: '+item.row.code);
   }else if(command==='test'||command==='publish'){
    assert.ok(item.row,'Run draft and approve its code first');if(command==='publish')assert.equal(item.row.testEvidence?.success,true,'Real successful trial required');
    const testId=command==='test'?crypto.randomUUID():item.row.testEvidence.testId;
    const request={expectedSourceVersion:item.file.version,expectedVersion:item.row.expectedVersion,testId,parameters:definition.trialParameters,expectedActiveReleaseId:''};
    save(name+'-'+command+'-'+testId+'-request',request);
    item.row=await api(route+'/'+item.row.id+'/'+command,'POST',request);persist();save(name+'-'+command+'-'+testId,item.row);
    assert.equal(item.row.testEvidence?.success,true,'Real database execution failed: retain this receipt');
    if(command==='publish')assert.equal(item.row.publication?.state,'RUNNING');
   }else throw Error('Commands: setup, generate, status, apply, draft, test, publish');
  }
 }
}

