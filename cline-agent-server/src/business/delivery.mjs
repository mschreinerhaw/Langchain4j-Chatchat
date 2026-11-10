import {mkdirSync,writeFileSync,chownSync,lstatSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';
import {taskSkillPackages,imageSkillBytes,safeResourcePath} from '../../../coding-agent-node/src/skill-distribution.js';
export async function deliver(run,gateway,workspace) {
 const c=run.contract,context=await gateway.readInputContext(c,run.abort.signal),inventory=[];
 for(const file of context.files??[]) {
  if(!/^[a-zA-Z0-9_.-]+$/.test(file.fileId)||!file.fileName||/[\\/]/.test(file.fileName))throw Error('Unsafe input descriptor');
  const chunks=[];let offset=0,descriptor;
  do {
   descriptor=await gateway.nativeInputFile(c,file.fileId,offset,run.abort.signal);
   if(descriptor.fileId!==file.fileId||descriptor.fileName!==file.fileName||descriptor.encoding!=='base64'
      ||!Number.isSafeInteger(descriptor.size)||descriptor.size<0||descriptor.size!==file.size)
    throw Error('INPUT_DESCRIPTOR_MISMATCH: identity, encoding or size differs from the frozen input inventory');
   const bytes=Buffer.from(descriptor.content,'base64');
   if(descriptor.offset!==offset||descriptor.nextOffset!==offset+bytes.length||descriptor.sha256!==file.sha256)throw Error('Input range binding mismatch');
   if(bytes.length===0&&descriptor.nextOffset<descriptor.size)throw Error('INPUT_TRANSFER_STALLED: this is not an empty dataset');
   chunks.push(bytes);offset=descriptor.nextOffset;
  }while(offset<descriptor.size);
  const bytes=Buffer.concat(chunks);if(bytes.length!==descriptor.size||createHash('sha256').update(bytes).digest('hex')!==file.sha256)throw Error('Input delivery Hash mismatch');
  // Task-local read aliases avoid asking the model to recopy opaque 64-character
  // IDs. Cryptographic identity and the authoritative Sandbox virtualPath stay
  // in the complete descriptor; these aliases grant no additional input access.
  const directory='input-'+(inventory.length+1);
  const rel=join('inputs',directory,file.fileName);mkdirSync(join(workspace,'inputs',directory),{recursive:true});writeFileSync(join(workspace,rel),bytes,{mode:0o400});
  let nativeEditablePath;
  if(/\.py$/i.test(file.fileName)&&c.authorization?.allowedSideEffects?.includes('CODE_EXECUTION')){
   nativeEditablePath='source-input-'+(inventory.length+1)+'.py';
   writeFileSync(join(workspace,nativeEditablePath),bytes,{mode:0o600,flag:'wx'});
  }
  inventory.push({...file,nativePath:rel,...(nativeEditablePath?{nativeEditablePath}:{} )});
 }
 const skills=[];
 for(const pkg of taskSkillPackages(c.runtimeContext)) {
  let source,resources=pkg.resources??[],nativeOriginal=pkg.builtin;
  if(pkg.builtin)source=imageSkillBytes(pkg);
  else {
   const receipt=await gateway.readSkill(c,pkg.card.id,'',run.abort.signal);
   if(receipt.skillId!==pkg.card.id||receipt.version!==pkg.card.version||typeof receipt.instructions!=='string')throw Error('Authorized Skill version mismatch');
   source=Buffer.from(receipt.instructions);if(createHash('sha256').update(source).digest('hex')!==receipt.contentSha256)throw Error('Skill source Hash mismatch');
   resources=receipt.resources??[];nativeOriginal=receipt.nativeOriginal===true;
  }
  const name=nativeOriginal?pkg.card.name:'method-'+createHash('sha256').update(pkg.card.id).digest('hex').slice(0,16);
  if(!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name))throw Error('Unsafe Skill identity');
  for(const path of ['SKILL.md',...resources.map(r=>r.path)]) {
   safeResourcePath(path);let bytes;
   if(path==='SKILL.md')bytes=source;
   else if(pkg.builtin)bytes=imageSkillBytes(pkg,path);
   else {
    const receipt=await gateway.readSkill(c,pkg.card.id,path,run.abort.signal),expected=resources.find(r=>r.path===path);
    if(receipt.skillId!==pkg.card.id||receipt.version!==pkg.card.version)throw Error('Skill resource version mismatch');
    bytes=Buffer.from(receipt.content,receipt.encoding==='base64'?'base64':'utf8');
    if(createHash('sha256').update(bytes).digest('hex')!==expected.sha256)throw Error('Skill resource Hash mismatch');
   }
   const originalHash=createHash('sha256').update(bytes).digest('hex');
   if(path==='SKILL.md'&&(!nativeOriginal||!bytes.toString().startsWith('---')))bytes=Buffer.from(`---\nname: ${name}\ndescription: ${JSON.stringify(pkg.card.description??name)}\n---\n\n`+bytes.toString());
   const target=join(workspace,'.cline','skills',name,path);mkdirSync(join(target,'..'),{recursive:true});writeFileSync(target,bytes,{mode:0o400});skills.push({skillId:pkg.card.id,version:pkg.card.version,name,path,originalHash,deliveredHash:createHash('sha256').update(bytes).digest('hex'),loaded:false});
  }
 }
 const {skills:stagedSkills,...runtimeContext}=c.runtimeContext??{};
 const delivery={context,files:inventory,skills,outputContract:c.outputContract,runtimeContext};writeFileSync(join(workspace,'runtime-inputs.json'),JSON.stringify(delivery,null,2),{mode:0o400});
 return delivery;
}
export function ownTree(path) {
 const s=lstatSync(path);if(s.isSymbolicLink())throw Error('Delivery symlink forbidden');chownSync(path,1000,1000);
 if(s.isDirectory())for(const name of readdirSync(path))ownTree(join(path,name));
}
