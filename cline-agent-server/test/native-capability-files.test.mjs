import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,writeFile,mkdir,symlink,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {stageNativeSkills,stageNativeDownload,runtimeMcpReply} from '../src/native-capability-files.mjs';
const sha=b=>createHash('sha256').update(b).digest('hex');
async function root(t){const p=await mkdtemp(join(tmpdir(),'capability-'));t.after(()=>rm(p,{recursive:true,force:true}));return p;}
const card={id:'agent-skill',name:'python-data-analysis',description:'Custom authorized methodology',version:'v1',files:[{path:'SKILL.md',content:'---\nname: original-name\ndescription: Custom methodology\ndisable-model-invocation: true\n---\nRead references/check.txt',encoding:'utf8'},{path:'references/check.txt',content:'data quality checklist',encoding:'utf8'}]};
test('native task directories preserve image Skills, namespace Agent collisions, metadata and resources',async t=>{
 const p=await root(t),events=[];await stageNativeSkills(p,[card],(type,data)=>events.push({type,...data}));
 const folders=await readdir(join(p,'.agents/skills'));assert(folders.some(n=>n.startsWith('runtime-image-')));
 const name=folders.find(n=>n.startsWith('runtime-agent-'));
 const text=await readFile(join(p,'.agents/skills',name,'SKILL.md'),'utf8');assert(text.includes('disable-model-invocation: true'));assert(text.includes('name: '+name));assert(text.includes('Read references/check.txt'));
 assert.equal(await readFile(join(p,'.agents/skills',name,'references/check.txt'),'utf8'),'data quality checklist');assert(events.some(e=>e.skillId===card.id&&e.version==='v1'));
});
test('package traversal, changed source hash and symlink ancestry are rejected',async t=>{
 for(const files of [[{path:'../escape',content:'bad'}],[{path:'SKILL.md',content:'changed',sha256:'0'.repeat(64)}]]){
  const p=await root(t);await assert.rejects(stageNativeSkills(p,[{...card,files}]));
 }
 if(process.platform==='win32')return;
 const p=await root(t),outside=await root(t);await symlink(outside,join(p,'.agents'));await assert.rejects(stageNativeSkills(p),/symlink/);assert.deepEqual(await readdir(outside),[]);
});
const bytes=Buffer.from('date,value\n2026-01-01,42\n');const receipt={status:'OK',url:'https://example.org/data.csv',content_type:'text/csv',size:bytes.length,sha256:sha(bytes),encoding:'base64',content:bytes.toString('base64')};
test('verified downloads expose readable file paths and metadata without binary model content',async t=>{
 const p=await root(t),value=await stageNativeDownload(p,receipt);assert(!('content' in value));assert.equal(value.path,'/workspace/downloads/'+sha(bytes)+'.csv');assert.deepEqual(await readFile(join(p,value.path.slice('/workspace/'.length))),bytes);
 assert.deepEqual(await stageNativeDownload(p,receipt),value);await assert.rejects(stageNativeDownload(p,{...receipt,size:1}),/integrity/);
 await writeFile(join(p,value.path.slice('/workspace/'.length)),'changed');await assert.rejects(stageNativeDownload(p,receipt),/changed/);
});
test('failed downloads remain explicit and cannot create a file',async t=>{
 const p=await root(t),failure={status:'ERROR',errorCode:'HTTP_TIMEOUT'};assert.equal(await stageNativeDownload(p,failure),failure);assert.deepEqual(await readdir(p),[]);
 if(process.platform==='win32')return;
 const outside=await root(t);await symlink(outside,join(p,'downloads'));await assert.rejects(stageNativeDownload(p,receipt),/symlink/);assert.deepEqual(await readdir(outside),[]);
});

test('HTTP success without an actual MCP result cannot be reported as tool success',()=>{
 const reply={jsonrpc:'2.0',id:1,result:{content:[{type:'text',text:'verified receipt'}]}};
 assert.deepEqual(runtimeMcpReply(Buffer.from(JSON.stringify(reply)),200),reply);
 for(const value of [{code:500,message:'backend failure'},null,{jsonrpc:'2.0',id:1,error:{code:-32000,message:'failed'}}])assert.throws(()=>runtimeMcpReply(Buffer.from(JSON.stringify(value)),200),{status:502});
 assert.throws(()=>runtimeMcpReply(Buffer.from(JSON.stringify(reply)),403),{status:403});
});
