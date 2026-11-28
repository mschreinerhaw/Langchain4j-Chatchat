import {createHash,randomUUID} from 'node:crypto';
import {createReadStream,constants} from 'node:fs';
import {lstat,realpath,writeFile,rename,open,opendir,rm} from 'node:fs/promises';
import {join,resolve,relative,isAbsolute} from 'node:path';
import {reviewProfessionalReports} from './professional-report-review.mjs';

export function deliveryPath(value){
 if(typeof value!=='string'||!value||value.length>768||value.includes('\\')||value.includes(':')||/[\x00-\x1f]/.test(value)||isAbsolute(value)||value.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('Invalid delivery path');
 if(['input','logs','evidence','.git'].includes(value.split('/')[0])||value==='delivery_manifest.json')throw Error('Reserved delivery path');
 return value;
}
export async function fileDigest(file){const hash=createHash('sha256');let size=0;for await(const chunk of createReadStream(file)){hash.update(chunk);size+=chunk.length;}return {size,sha256:hash.digest('hex')};}
const artifactTypes=['SOURCE','REPORT','CHART','RESULT','TEST','AUXILIARY'];
const extensions=values=>Array.isArray(values)&&values.length>0&&values.length<=24
 &&new Set(values).size===values.length&&values.every(x=>typeof x==='string'&&/^[a-z0-9]{1,12}$/.test(x));
export function validDeliveryRules(rules){
 if(!Array.isArray(rules)||!rules.length||rules.length>artifactTypes.length)return false;
 const dirs=new Set(),types=new Set();
 return rules.every(rule=>{
  if(!rule||typeof rule!=='object'||Object.keys(rule).sort().join(',')!=='directory,formats,htmlCompanions,maxCount,minCount,type'
    ||typeof rule.directory!=='string'||!/^output\/[a-z0-9][a-z0-9_-]{0,49}$/.test(rule.directory)
    ||dirs.has(rule.directory)||!artifactTypes.includes(rule.type)||types.has(rule.type)||!extensions(rule.formats)
    ||!Number.isInteger(rule.minCount)||!Number.isInteger(rule.maxCount)||rule.minCount<1
    ||rule.minCount>rule.maxCount||rule.maxCount>1000)return false;
  const companions=rule.htmlCompanions;
  if(!companions||typeof companions!=='object'||Array.isArray(companions))return false;
  const used=new Set(rule.formats);
  for(const [type,formats] of Object.entries(companions)){
   if(rule.type!=='REPORT'||!rule.formats.includes('html')||!['CHART','AUXILIARY'].includes(type)
      ||!extensions(formats)||formats.some(ext=>used.has(ext)))return false;
   for(const ext of formats)used.add(ext);
  }
  dirs.add(rule.directory);types.add(rule.type);
  return true;
 });
}
function legacyRules(mode){
 const visual=mode==='visual';
 return [
  {directory:'output/report',type:'REPORT',formats:visual?['html','md']:['md'],minCount:1,maxCount:1,
   htmlCompanions:visual?{CHART:['png','svg'],AUXILIARY:['css','js','json','csv','txt','jpg','jpeg','webp','gif','woff','woff2']}:{}},
  {directory:'output/result',type:'RESULT',formats:['json'],minCount:1,maxCount:1,htmlCompanions:{}}
 ];
}
export const deliveryRulesFor=contract=>contract.deliveryRules??(contract.analysisDelivery?legacyRules(contract.analysisDelivery):null);
export const discoverAnalysisDelivery=(workspace,mode)=>discoverContractDelivery(workspace,legacyRules(mode));

export async function discoverContractDelivery(workspace,rules){
 if(!validDeliveryRules(rules))throw Error('Invalid host delivery rules');
 const root=await realpath(workspace),files=[];
 let entries=0;
 async function scan(path,rule,inventory,depth){
  if(++entries>1000||depth>16)throw Error('Delivery inventory is too large');
  const full=join(root,deliveryPath(path)),stat=await lstat(full);
  if(stat.isSymbolicLink())throw Error('Symlink delivery denied');
  if(stat.isDirectory()){
   for await(const entry of await opendir(full))await scan(path+'/'+entry.name,rule,inventory,depth+1);
   return;
  }
  if(!stat.isFile()||!stat.size)throw Error('Delivery requires nonempty regular files');
  const ext=/\.([a-z0-9]+)$/i.exec(path)?.[1].toLowerCase();
  if(rule.formats.includes(ext)){inventory.primary.push({path,type:rule.type});return;}
  const companion=Object.entries(rule.htmlCompanions).find(([,values])=>values.includes(ext));
  if(!companion)throw Error('Unsupported file format in '+rule.directory);
  inventory.companions.push({path,type:companion[0]});
 }
 const output=await lstat(join(root,'output')).catch(error=>{
  if(error.code==='ENOENT')throw Error('Expected a delivery output directory');
  throw error;
 });
 if(!output.isDirectory()||output.isSymbolicLink())throw Error('Delivery output must be a regular directory');
 for(const rule of rules){
  const path=rule.directory;
  const info=await lstat(join(root,path)).catch(error=>{if(error.code==='ENOENT')return null;throw error;});
  if(!info?.isDirectory()||info.isSymbolicLink())throw Error('Expected a delivery directory at '+path);
  const inventory={primary:[],companions:[]};
  await scan(path,rule,inventory,0);
  if(inventory.primary.length<rule.minCount||inventory.primary.length>rule.maxCount)
   throw Error(rule.minCount===1&&rule.maxCount===1?'Deliver exactly one '+rule.type+' file in '+path+'/'
    :'Deliver '+rule.minCount+' to '+rule.maxCount+' '+rule.type+' files in '+path+'/');
  if(inventory.companions.length&&!inventory.primary.some(a=>/\.html$/i.test(a.path)))
   throw Error('Report companion files require an HTML report');
  files.push(...inventory.primary,...inventory.companions);
 }
 return files.sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0);
}
export async function readDeliverySelection(workspace){
 const root=await realpath(workspace),file=join(root,'delivery_manifest.json'),limit=512*1024;
 const stat=await lstat(file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>limit)throw Error('Delivery selection must be a regular JSON file of at most 512 KiB');
 const handle=await open(file,constants.O_RDONLY|constants.O_NOFOLLOW);let raw;
 try{
  const buffer=Buffer.alloc(limit+1);let used=0;
  while(used<buffer.length){const {bytesRead}=await handle.read(buffer,used,buffer.length-used,used);if(!bytesRead)break;used+=bytesRead;}
  if(used>limit)throw Error('Delivery selection exceeds 512 KiB');raw=buffer.subarray(0,used);
 }finally{await handle.close();}
 const selectionSha256=createHash('sha256').update(raw).digest('hex');
 try{
  const proposal=JSON.parse(raw.toString('utf8'));
  if(!['coding_node_delivery_selection.v1','coding_node_delivery.v1'].includes(proposal?.schemaVersion))throw Error('Expected coding_node_delivery_selection.v1 in delivery_manifest.json');
  if(!Array.isArray(proposal.artifacts)||!proposal.artifacts.length||proposal.artifacts.length>1000)throw Error('Declare between 1 and 1000 delivery artifacts');
  const seen=new Set();return proposal.artifacts.map(item=>{
   const path=deliveryPath(item?.path);if(seen.has(path))throw Error('Duplicate delivery path');seen.add(path);
   if(!['SOURCE','REPORT','CHART','RESULT','TEST','AUXILIARY'].includes(item.type))throw Error('Invalid business artifact type');
   // The declaration selects paths/types only. Host bindings, real hashes and
   // completion receipts are ALWAYS recomputed by buildDeliveryManifest.
   return {path,type:item.type};
  });
 }catch(error){error.selectionSha256=selectionSha256;throw error;}
}
export async function hashDeliveryArtifacts(workspace,files){
 try{
 const root=await realpath(workspace),seen=new Set(),artifacts=[];
 if(!Array.isArray(files)||files.length===0||files.length>1000)throw Error('Declare delivery files');
 for(const item of files){
  const path=deliveryPath(item.path);
  if(seen.has(path))throw Error('Duplicate delivery path');seen.add(path);
  if(!['SOURCE','REPORT','CHART','RESULT','TEST','AUXILIARY'].includes(item.type))throw Error('Invalid business artifact type');
  let candidate=root;
  for(const part of path.split('/')){candidate=join(candidate,part);if((await lstat(candidate)).isSymbolicLink())throw Error('Symlink delivery denied');}
  const actual=await realpath(candidate),rel=relative(root,actual);
  if(rel.startsWith('..')||isAbsolute(rel)||!(await lstat(actual)).isFile())throw Error('Delivery file outside workspace');
  const before=await lstat(actual),digest=await fileDigest(actual),after=await lstat(actual);
  if(before.size!==after.size||before.mtimeMs!==after.mtimeMs||before.ino!==after.ino||digest.size!==after.size)throw Error('Delivery changed during hashing');
  artifacts.push({path,type:item.type,...digest});
 }
 if(process.env.CLINE_PROFESSIONAL_REPORTS==='true')await reviewProfessionalReports(root,artifacts);
 return artifacts;
 }catch(error){error.deliveryContentError=true;throw error;}
}
export async function buildDeliveryManifest({workspace,binding,session,files}){
 for(const key of ['taskId','runId','tenantId','inputSnapshotVersion','deliveryVersion'])if(typeof binding?.[key]!=='string'||!binding[key])throw Error('Missing trusted delivery binding: '+key);
 if(!['turn_completed','sealed'].includes(session.status))throw Error('Native turn has not completed');
 const artifacts=await hashDeliveryArtifacts(workspace,files);
 const receipts=[];
 for(const event of (session.journal.readAfter?.()??session.journal.events))
  if(event.epoch===session.epoch&&(!session.taskId||event.taskId===session.taskId)&&['tool.completed','tool.failed','turn.completed'].includes(event.eventType))
   receipts.push({eventId:event.eventId,eventType:event.eventType,sessionId:session.id,epoch:event.epoch});
 if(!receipts.some(e=>e.eventType==='turn.completed'))throw Error('Missing native completion receipt');
 return {schemaVersion:'coding_node_delivery.v1',...Object.fromEntries(['taskId','runId','tenantId','inputSnapshotVersion','deliveryVersion'].map(k=>[k,binding[k]])),sessionId:session.id,epoch:session.epoch,executionStatus:'NATIVE_TURN_COMPLETED',acceptanceStatus:'PENDING_RUNTIME_VALIDATION',artifacts,nativeReceipts:receipts};
}
export async function writeDeliveryManifest(workspace,manifest){
 const root=await realpath(workspace),path=join(root,'delivery_manifest.json'),temp=join(root,'.delivery-manifest-'+randomUUID()+'.tmp');
 try{
  await writeFile(temp,JSON.stringify(manifest,null,2),{flag:'wx',mode:0o600,flush:true});
  await rename(temp,path);
  if(process.platform!=='win32'){
   const dir=await open(root,'r');
   try{await dir.sync();}finally{await dir.close();}
  }
 }finally{await rm(temp,{force:true});}
 return manifest;
}
