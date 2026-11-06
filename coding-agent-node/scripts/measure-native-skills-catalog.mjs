import { performance } from 'node:perf_hooks';
import { writeFile, readFile } from 'node:fs/promises';
import { prepareNativeSkills } from '../src/native-skills.js';
import { sha256 } from '../src/skill-distribution.js';

// Synthetic metadata cost probe. No Provider, model call, execution or production access.
const sdk = JSON.parse(await readFile(new URL('../node_modules/@cline/core/package.json', import.meta.url), 'utf8'));
const results=[];
for (const count of [0,123,500]) {
  const cards=Array.from({length:count},(_,i)=>({id:`cost-${i}`,name:`cost-method-${i}`,
    description:`Synthetic methodology ${i} for catalog size measurement; not a recommendation.`,version:'frozen'}));
  const contract={taskId:`catalog-cost-${count}`,scope:{tenantId:'synthetic',userId:'catalog-probe',runId:`catalog-${count}`},runtimeContext:{skills:{skills:cards}}};
  const events=[],started=performance.now();
  const runtime=await prepareNativeSkills(contract,{readSkill(_contract,id){
    const card=cards.find(c=>c.id===id);
    if(!card)throw new Error('Probe attempted an unauthorized package');
    const instructions=`---\nname: ${card.name}\ndescription: ${card.description}\n---\nSYNTHETIC_BODY_NOT_LOADED\n`;
    return {skillId:id,version:card.version,nativeOriginal:true,instructions,contentSha256:sha256(instructions),resources:[]};
  }},{sessionId:'catalog-cost',observe:event=>events.push(event)});
  try {
    const preparationMs=performance.now()-started;
    const tools=runtime.tools.map(({name,description,inputSchema})=>({name,description,inputSchema}));
    const serialized=JSON.stringify(tools),schemaBytes=Buffer.byteLength(serialized);
    const purpose=tools.find(t=>t.name==='skills').inputSchema.properties.skill.description;
    const metadataStart=purpose.indexOf('\n[');
    const metadataBytes=metadataStart<0?0:Buffer.byteLength(purpose.slice(metadataStart+1));
    if(runtime.records.length!==15+count || serialized.includes('SYNTHETIC_BODY_NOT_LOADED')
      || events.some(e=>e.type==='SKILLS_LOADED'))throw new Error('Catalog completeness or lazy-loading invariant failed');
    results.push({dynamicSkills:count,totalSkills:runtime.records.length,preparationMs,schemaBytes,
      metadataProjectionBytes:metadataBytes,estimatedSchemaTokens:Math.ceil(schemaBytes/4),
      initialBodyLoads:0,providerUsage:'UNKNOWN',schemaSha256:sha256(serialized)});
  } finally {await runtime.dispose();}
}
const output=JSON.stringify({sdkVersion:sdk.version,scope:'Synthetic public SDK catalog and tool-schema measurement; estimated tokens are not Provider usage; one warm-process run, no latency or business performance claim.',results},null,2)+'\n';
if(process.argv[2])await writeFile(process.argv[2],output);else process.stdout.write(output);
