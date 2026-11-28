import {createHash} from 'node:crypto';
import {createReadStream,constants} from 'node:fs';
import {lstat,realpath,writeFile,rename,open,opendir} from 'node:fs/promises';
import {join,resolve,relative,isAbsolute} from 'node:path';
import {reviewProfessionalReports} from './professional-report-review.mjs';

export function deliveryPath(value){
 if(typeof value!=='string'||!value||value.length>768||value.includes('\\')||value.includes(':')||/[\x00-\x1f]/.test(value)||isAbsolute(value)||value.split('/').some(p=>!p||p==='.'||p==='..'))throw Error('Invalid delivery path');
 if(['input','logs','evidence','.git'].includes(value.split('/')[0])||value==='delivery_manifest.json')throw Error('Reserved delivery path');
 return value;
}
export async function fileDigest(file){const hash=createHash('sha256');let size=0;for await(const chunk of createReadStream(file)){hash.update(chunk);size+=chunk.length;}return {size,sha256:hash.digest('hex')};}
export const ANALYSIS_REPORT_DIR='output/report';
export const ANALYSIS_RESULT_DIR='output/result';
export async function discoverAnalysisDelivery(workspace,mode){
 const root=await realpath(workspace),files=[];
 const htmlAssets=/\.(?:css|js|json|csv|txt|png|svg|jpe?g|webp|gif|woff2?)$/i;
 const reportRoot=ANALYSIS_REPORT_DIR,resultRoot=ANALYSIS_RESULT_DIR;
 const reportFormat=mode==='visual'?/\.(?:html|md)$/i:/\.md$/i;
 let entries=0;
 async function scan(path,role,depth){
  if(++entries>1000||depth>16)throw Error('Analysis delivery inventory is too large');
  const full=join(root,deliveryPath(path)),stat=await lstat(full);
  if(stat.isSymbolicLink())throw Error('Symlink delivery denied');
  if(stat.isDirectory()){
   for await(const entry of await opendir(full))await scan(path+'/'+entry.name,role,depth+1);
   return;
  }
  if(!stat.isFile()||!stat.size)throw Error('Analysis delivery requires nonempty regular files');
  let type;
  if(role==='result'&&/\.json$/i.test(path))type='RESULT';
  if(role==='report'){
   if(reportFormat.test(path))type='REPORT';
   else if(mode==='visual'&&htmlAssets.test(path))type=/\.(?:png|svg)$/i.test(path)?'CHART':'AUXILIARY';
  }
  if(!type)throw Error('Unsupported file format in '+(role==='result'?resultRoot:reportRoot));
  files.push({path,type});
 }
 try{
  const output=await lstat(join(root,'output'));
  if(!output.isDirectory()||output.isSymbolicLink())throw Error('Analysis delivery output must be a regular directory');
  for(const [path,role] of [[reportRoot,'report'],[resultRoot,'result']]){
   const info=await lstat(join(root,path));
   if(!info.isDirectory()||info.isSymbolicLink())throw Error('Expected a delivery directory at '+path);
   await scan(path,role,0);
  }
 }catch(error){
  if(error.code==='ENOENT')throw Error('Put the finished report in '+reportRoot+'/ and the structured JSON result in '+resultRoot+'/');
  throw error;
 }
 if(files.filter(a=>a.type==='REPORT').length!==1||files.filter(a=>a.type==='RESULT').length!==1)
  throw Error('Deliver exactly one report in '+reportRoot+'/ and one structured JSON result in '+resultRoot+'/');
 if(files.some(a=>!['REPORT','RESULT'].includes(a.type))&&!files.some(a=>a.type==='REPORT'&&/\.html$/i.test(a.path)))
  throw Error('Report companion files require an HTML report');
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
 const receipts=session.journal.events.filter(e=>e.epoch===session.epoch&&(!session.taskId||e.taskId===session.taskId)&&['tool.completed','tool.failed','turn.completed'].includes(e.eventType)).map(e=>({eventId:e.eventId,eventType:e.eventType,sessionId:session.id,epoch:e.epoch}));
 if(!receipts.some(e=>e.eventType==='turn.completed'))throw Error('Missing native completion receipt');
 return {schemaVersion:'coding_node_delivery.v1',...Object.fromEntries(['taskId','runId','tenantId','inputSnapshotVersion','deliveryVersion'].map(k=>[k,binding[k]])),sessionId:session.id,epoch:session.epoch,executionStatus:'NATIVE_TURN_COMPLETED',acceptanceStatus:'PENDING_RUNTIME_VALIDATION',artifacts,nativeReceipts:receipts};
}
export async function writeDeliveryManifest(workspace,manifest){
 const root=await realpath(workspace),path=join(root,'delivery_manifest.json'),temp=join(root,'.delivery-manifest-'+manifest.epoch+'.tmp');
 await writeFile(temp,JSON.stringify(manifest,null,2),{flag:'wx',mode:0o600});await rename(temp,path);
 return manifest;
}
