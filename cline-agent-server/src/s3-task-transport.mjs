// Business-neutral transport. Only the signed Runtime grant authorizes objects;
// provider/storage credentials and capability URLs never enter the Core tree.
import {createHash} from 'node:crypto';
import {mkdir,readdir,chmod,chown,lstat,writeFile,readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {downloadInput,uploadParts,transferChunkBytes} from './silo-transfer.mjs';
import {hashDeliveryArtifacts} from './delivery-manifest.mjs';

const fail=(message,status=400)=>Object.assign(Error(message),{status});
export const jsonHash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
export function inputManifestHash(inputs){
 return jsonHash(inputs.map(({path,size,sha256,objectVersion,url})=>({path,size,sha256,objectVersion,objectPath:new URL(url).pathname})).sort((a,b)=>a.path<b.path?-1:a.path>b.path?1:0));
}
function prefix(value){
 if(typeof value!=='string'||!value.startsWith('/')||!value.endsWith('/')||/[\\%?#\x00-\x20]/.test(value)
  ||value.slice(1,-1).split('/').some(p=>!p||p==='.'||p==='..'))throw fail('Canonical S3 object prefix required');
 return value;
}
export function storageGrant(contract,config){
 const s=contract.s3;
 if(contract.version!==2||!config||!s||Object.keys(s).sort().join(',')!=='inputManifestSha256,inputPrefix,origin,outputPrefix')throw fail('Signed S3 task policy required',403);
 let origin;try{origin=new URL(s.origin);}catch{throw fail('Invalid S3 origin',403);}
 if(!['http:','https:'].includes(origin.protocol)||origin.origin!==s.origin||!config.allowedOrigins?.includes(s.origin)
  ||!/^([a-f0-9]{64})$/.test(s.inputManifestSha256))throw fail('S3 origin or input inventory not authorized',403);
 prefix(s.inputPrefix);prefix(s.outputPrefix);
 if(s.inputPrefix.startsWith(s.outputPrefix)||s.outputPrefix.startsWith(s.inputPrefix))throw fail('Separate S3 input/output object spaces required',403);
 if(config.scopeLayout){
  if(config.scopeLayout!=='tenant-task-v1'||typeof config.bucket!=='string'||!contract.deliveryBinding?.runId)throw fail('Unknown frozen S3 scope layout',403);
  const hash=value=>createHash('sha256').update(value).digest('hex');
  const root='/'+config.bucket+'/tenants/'+hash(contract.tenantId)+'/users/'+hash(contract.tenantId+'\0'+contract.userId)
   +'/tasks/coding-node/'+hash(contract.taskId+'\0'+contract.deliveryBinding.runId)+'/';
  if(!s.inputPrefix.startsWith(root)||!s.outputPrefix.startsWith(root))throw fail('S3 objects outside tenant/user/task/run authority',403);
  const suffix=s.inputPrefix.slice(root.length),match=/^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})\/input\/$/.exec(suffix);
  if(!match||s.outputPrefix!==root+match[1]+'/output/')throw fail('S3 input/output run fence differs',403);
 }
 return s;
}
export function objectCapability(value,policy,kind){
 let u;try{u=new URL(value);}catch{throw fail('Invalid S3 capability',403);}
 // Reject encoded separators/dot segments as well as alternate authorities.
 const path=value.slice(value.indexOf('://')+3).split('?')[0];
 if(u.origin!==policy.origin||u.username||u.password||u.hash||/[\\\x00-\x20]/.test(value)
  ||/%(?:2f|5c|2e|00|25)/i.test(u.pathname)||path.split('/').some(p=>p==='.'||p==='..')
  ||!u.pathname.startsWith(policy[kind+'Prefix'])||u.pathname===policy[kind+'Prefix'])throw fail('Object outside signed task scope',403);
 return u;
}
export function validateS3Inputs(contract,inputs,config){
 const policy=storageGrant(contract,config),seen=new Set();let total=0;
 if(!Array.isArray(inputs)||inputs.length>64)throw fail('At most 64 authorized input files required');
 for(const input of inputs){
  if(!input||Object.keys(input).some(k=>!['path','size','sha256','objectVersion','url'].includes(k))
   ||typeof input.path!=='string'||!input.path.startsWith('input/')||input.path.length>768||/[\\:\x00-\x1f]/.test(input.path)
   ||input.path.split('/').some(p=>!p||p==='.'||p==='..')||input.path==='input/manifest.json'||seen.has(input.path)
   ||!Number.isSafeInteger(input.size)||input.size<0||!/^([a-f0-9]{64})$/.test(input.sha256)
   ||typeof input.objectVersion!=='string'||!input.objectVersion||input.objectVersion==='null')throw fail('Invalid immutable input metadata');
  seen.add(input.path);total+=input.size;
  if(!Number.isSafeInteger(total)||total>(config.maxInputBytes??1073741824))throw fail('Input resource limit exceeded',413);
  const u=objectCapability(input.url,policy,'input');
  if(u.searchParams.get('versionId')!==input.objectVersion)throw fail('Immutable input version differs',403);
 }
 if(inputManifestHash(inputs)!==policy.inputManifestSha256)throw fail('Input inventory differs from signed snapshot',403);
 return policy;
}
export async function prepareS3Inputs(workspace,contract,inputs,config,{signal,uid=10001,fetcher}={}){
 validateS3Inputs(contract,inputs,config);
 const inputRoot=join(workspace,'input');await mkdir(inputRoot,{recursive:true});
 if((await readdir(inputRoot)).length)throw fail('Task input must start empty',409);
 const files=[];for(const input of inputs)files.push(await downloadInput(workspace,input,{signal,fetcher}));
 signal?.throwIfAborted();
 await writeFile(join(inputRoot,'manifest.json'),JSON.stringify({schemaVersion:'coding_node_input.v1',...contract.deliveryBinding,files},null,2),{flag:'wx',mode:0o444});
 async function protect(dir){
  for(const name of await readdir(dir)){const p=join(dir,name),stat=await lstat(p);if(stat.isSymbolicLink())throw fail('Input symlink forbidden');if(stat.isDirectory())await protect(p);else{await chown(p,uid,uid);await chmod(p,0o444);}}
  await chown(dir,uid,uid);await chmod(dir,0o555);
 }
 await protect(inputRoot);return files;
}
export async function verifyStoppedDelivery(workspace,manifest,stopProof,config){
 if(stopProof?.running!==false)throw fail('Executor stop must be confirmed before upload',409);
 const actual=await hashDeliveryArtifacts(workspace,manifest.artifacts);
 if(jsonHash(actual)!==jsonHash(manifest.artifacts))throw fail('Delivery changed before process stop',409);
 if(actual.reduce((n,a)=>n+a.size,0)>(config.maxOutputBytes??1073741824))throw fail('Output resource limit exceeded',413);
 return manifest;
}
export async function uploadS3Delivery(workspace,contract,manifest,plan,config,{signal,fetcher=fetch}={}){
 const policy=storageGrant(contract,config);
 if(!plan||Object.keys(plan).some(k=>!['manifestSha256','artifacts','manifestUrl','evidenceUrl'].includes(k))
  ||plan.manifestSha256!==jsonHash(manifest)||!Array.isArray(plan.artifacts)||plan.artifacts.length!==manifest.artifacts.length)throw fail('Upload plan differs from sealed delivery',409);
 const seen=new Set();
 // Authorize the ENTIRE plan before sending any byte, including manifest/evidence.
 for(const artifact of manifest.artifacts){
  const parts=plan.artifacts.filter(p=>p.path===artifact.path);
  if(parts.length!==1||!Array.isArray(parts[0].parts)||parts[0].parts.length!==Math.max(1,Math.ceil(artifact.size/transferChunkBytes)))throw fail('Invalid output part inventory');
  for(const part of parts[0].parts){const u=objectCapability(part.url,policy,'output');if(seen.has(u.pathname))throw fail('Output object reused across parts');seen.add(u.pathname);}
 }
 for(const name of ['manifestUrl','evidenceUrl']){
  const u=objectCapability(plan[name],policy,'output');if(seen.has(u.pathname))throw fail('Output metadata object reused');seen.add(u.pathname);
 }
 const receipts=[];
 for(const artifact of manifest.artifacts)receipts.push(await uploadParts(workspace,artifact,plan.artifacts.find(p=>p.path===artifact.path),{signal,fetcher}));
 async function put(url,data){
  let last;
  for(let i=0;i<3;i++){
   signal?.throwIfAborted();
   try{const res=await fetcher(url,{method:'PUT',body:data,redirect:'error',signal,headers:{'content-type':'application/json'}});await res.body?.cancel();
    const objectVersion=res.headers.get('x-amz-version-id');if(!res.ok||!objectVersion||objectVersion==='null')throw Error('S3 metadata upload or versioning failed');
    return {size:data.length,sha256:createHash('sha256').update(data).digest('hex'),objectVersion};
   }catch(e){last=e;signal?.throwIfAborted();if(i<2)await new Promise(r=>setTimeout(r,100*2**i));}
  }throw last;
 }
 const evidence=await readFile(join(workspace,'..','sealed-native-events.json'));
 const evidenceReceipt=await put(plan.evidenceUrl,evidence);
 const manifestReceipt=await put(plan.manifestUrl,Buffer.from(JSON.stringify(manifest)));
 signal?.throwIfAborted();return {schemaVersion:'coding_node_s3_receipt.v1',manifestSha256:jsonHash(manifest),artifacts:receipts,evidence:evidenceReceipt,manifest:manifestReceipt,acceptanceStatus:'PENDING_RUNTIME_VALIDATION'};
}
