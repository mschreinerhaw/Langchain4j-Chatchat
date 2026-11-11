// Component fault injection around ACTUAL Silo requests; no model or Core.
// Synthetic partial bodies/503 are explicitly marked. Signed-URL 403 are real.
import {readFile,copyFile,mkdir,access,readdir,writeFile} from 'node:fs/promises';
import {join} from 'node:path';
import {downloadInput,uploadParts} from '/opt/runtime-os/coding-node/service/src/silo-transfer.mjs';
const [root,planFile]=process.argv.slice(2),plan=JSON.parse(await readFile(planFile,'utf8'));
if(plan.schemaVersion!=='silo_transport_component_probe.v1'||plan.componentTestOnly!==true)throw Error('Explicit component-only plan required');
const signal=AbortSignal.timeout(120000),calls=[],checks=[];
async function exists(path){try{await access(path);return true;}catch{return false;}}
async function partialDownload(name,always){
 const workspace=join(root,name);let attempts=0;
 const fetcher=async(url,options)=>{
  attempts++;const response=await fetch(url,options),inject=always||attempts===1;
  calls.push({case:name,method:'GET',range:options.headers.Range,realSiloStatus:response.status,syntheticPartialBody:inject});
  if(!inject)return response;
  if(response.status!==206)throw Error('Positive real Silo range control failed');
  const bytes=new Uint8Array(await response.arrayBuffer());return new Response(bytes.slice(0,-1),{status:response.status,headers:response.headers});
 };
 let receipt,error;try{receipt=await downloadInput(workspace,plan.input,{signal,fetcher});}catch(e){error=e.message;}
 const visible=await exists(join(workspace,plan.input.path)),parent=join(workspace,'input/data');
 const temp=(await readdir(parent)).some(name=>name.includes('.download-'));
 const passed=always?!receipt&&attempts===3&&!visible&&!temp:Boolean(receipt)&&attempts===4&&visible&&!temp;
 checks.push({name,passed,attempts,targetVisible:visible,temporaryDownloadRemains:temp,error:error??null});
 if(!passed)throw Error('Partial-download component expectation failed: '+name);return workspace;
}
const successful=await partialDownload('incomplete-once-safe-retry',false);await partialDownload('incomplete-every-time-safe-failure',true);
const mismatchRoot=join(root,'wrong-sha'),wrong={...plan.input,sha256:'0'.repeat(64)};
let mismatchError;try{await downloadInput(mismatchRoot,wrong,{signal});}catch(e){mismatchError=e.message;}
const mismatchPassed=mismatchError==='Silo input SHA-256 mismatch'&&!await exists(join(mismatchRoot,plan.input.path));checks.push({name:'full-sha-mismatch-not-exposed',passed:mismatchPassed,error:mismatchError});if(!mismatchPassed)throw Error('Invalid hash input exposed');
await mkdir(join(successful,'output'));await copyFile(join(successful,plan.input.path),join(successful,plan.artifact.path));
let puts=0;
const uploadFetcher=async(url,options)=>{
 puts++;
 if(puts===1){calls.push({case:'upload-once-503-safe-retry',method:'PUT',syntheticStatus:503,forwardedToSilo:false});return new Response('Controlled transport fault',{status:503});}
 const response=await fetch(url,options);calls.push({case:'upload-once-503-safe-retry',method:'PUT',realSiloStatus:response.status,objectVersion:response.headers.get('x-amz-version-id')});return response;
};
const uploaded=await uploadParts(successful,plan.artifact,{parts:plan.parts},{signal,fetcher:uploadFetcher});
checks.push({name:'upload-once-503-safe-retry',passed:puts===4&&uploaded.parts.length===3,attempts:puts,syntheticFaultExplicit:true});
let denied=0,denialError;
try{await uploadParts(successful,plan.artifact,{parts:plan.parts},{signal,fetcher:async(url,options)=>{
 denied++;const bad=new URL(url);bad.searchParams.set('X-Amz-Signature','0'.repeat(64));const response=await fetch(bad,options);
 calls.push({case:'real-silo-denied-put-bounded-failure',method:'PUT',realSiloStatus:response.status,intentionallyInvalidSignature:true});return response;
}});}catch(e){denialError=e.message;}
checks.push({name:'real-silo-denied-put-bounded-failure',passed:denied===3&&denialError==='Silo part upload failed: HTTP 403',attempts:denied,error:denialError});
const cancelled=new AbortController();cancelled.abort(Error('Controlled task-transfer cancellation'));let cancellationError;
try{await uploadParts(successful,plan.artifact,{parts:plan.parts},{signal:cancelled.signal});}catch(e){cancellationError=e.message;}
checks.push({name:'cancelled-transfer-no-delivery-receipt',passed:cancellationError==='Controlled task-transfer cancellation',error:cancellationError});
const proof={schemaVersion:'silo_transport_live_probe.v1',componentTestOnly:true,actualSiloUsed:true,modelCalls:0,nativeReceiptsFabricated:false,businessTaskAcceptance:false,
 syntheticFaults:['One 206 response body truncated after real Silo GET','All 206 bodies truncated in permanent-failure sample','One pre-forward PUT HTTP 503'],calls,checks,passed:checks.every(c=>c.passed),uploaded};
await writeFile(join(root,'transport-component-probe.json'),JSON.stringify(proof,null,2),{flag:'wx',mode:0o400});
console.log(JSON.stringify(proof));if(!proof.passed)process.exitCode=1;
