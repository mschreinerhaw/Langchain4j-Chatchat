/** Raw vendor-package compatibility gate, never a model-choice or package-execution test.
 * Input: {packages:[{publisher,repo,commit,prefix,name,files:[{path,gitBlobSha,size,content}]}]}.
 * Fetch original UTF-8 files at pinned commits; keep the input outside Git.
 */
import { readFile, writeFile } from 'node:fs/promises';
import { createHash, randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import assert from 'node:assert/strict';
import { prepareNativeSkills } from '../src/native-skills.js';
import { sha256 } from '../src/skill-distribution.js';

const [input,output]=process.argv.slice(2);
if(!input||!output)throw Error('Usage: audit-native-skill-packages.mjs originals-private.json proof.json');
const {packages}=JSON.parse(await readFile(input,'utf8'));
const source=new Map();
for(const pkg of packages){
  assert.match(pkg.commit,/^[a-f0-9]{40}$/);
  pkg.id='vendor-fixture:'+pkg.repo;
  for(const file of pkg.files){
    const bytes=Buffer.from(file.content,'utf8');assert.equal(bytes.length,file.size);
    const blob=createHash('sha1').update(Buffer.from('blob '+bytes.length+'\0')).update(bytes).digest('hex');
    assert.equal(blob,file.gitBlobSha,'Connector must preserve the exact original Git blob');
    file.sha256=sha256(bytes);
  }
  source.set(pkg.id,pkg);
}
const task=randomUUID(),events=[];
const contract={taskId:task,scope:{tenantId:'vendor-format-fixture',userId:'fixture-owner',runId:task},
  runtimeContext:{skills:{skills:packages.map(p=>({id:p.id,name:p.name,description:'Original vendor package compatibility fixture',
    version:p.commit,nativeOriginal:true}))}}};
const runtime=await prepareNativeSkills(contract,{readSkill(_contract,id,path){
  const p=source.get(id),file=p.files.find(f=>f.path===(path||'SKILL.md'));assert(file);
  return path?{skillId:id,version:p.commit,content:file.content,encoding:'utf-8',sha256:file.sha256}
    :{skillId:id,version:p.commit,nativeOriginal:true,instructions:file.content,contentSha256:file.sha256,
      resources:p.files.filter(f=>f.path!=='SKILL.md').map(f=>({path:f.path,sha256:f.sha256,size:f.size}))};
}},{sessionId:'vendor-format-audit',observe:e=>events.push(e)});
const proof=[];
try{
  for(const p of packages){
    const id=events.find(e=>e.type==='SKILLS_DISCOVERED').skills.find(s=>s.skillId===p.id).id;
    const record=runtime.records.find(r=>r.id===id);assert.equal(record.item.name,p.name);
    for(const f of p.files)assert.equal(sha256(await readFile(join(dirname(record.filePath),f.path))),f.sha256);
    const context=()=>({toolCallId:randomUUID(),signal:new AbortController().signal});
    const body=await runtime.tools.find(t=>t.name==='skills').execute({skill:id},context());
    assert.equal(typeof body,'string');assert(body.includes(record.item.instructions));assert(body.includes('<skill-root>'));
    const reference=p.files.find(f=>f.path!=='SKILL.md'&&f.path.endsWith('.md'));assert(reference);
    const result=await runtime.tools.find(t=>t.name==='read_files').execute({files:[{path:join(dirname(record.filePath),reference.path)}]},context());
    assert(result.every(r=>r.success));
    assert(JSON.stringify(result).includes(reference.content.split('\n').find(l=>l.startsWith('# '))));
    proof.push({publisher:p.publisher,repository:p.repo,commit:p.commit,directory:p.prefix,name:p.name,nativeId:id,
      allOriginalBytesVerified:true,gitBlobsVerified:true,nativeBodyIncludesSDKParsedInstructions:true,
      nativeReferenceLoaded:reference.path,sameNameNamespaces:runtime.records.filter(r=>r.item.name===p.name).map(r=>r.id),
      files:p.files.map(({path,gitBlobSha,size,sha256})=>({path,gitBlobSha,size,sha256}))});
  }
  await writeFile(output,JSON.stringify({scope:'Original package bytes, official discovery/body/reference APIs; no provider or upstream script execution',
    packages:proof,nativeDiscovered:runtime.records.length},null,2)+'\n');
  console.log(JSON.stringify({packages:proof.map(p=>({publisher:p.publisher,name:p.name,files:p.files.length,nativeId:p.nativeId})),nativeDiscovered:runtime.records.length}));
}finally{await runtime.dispose();}
