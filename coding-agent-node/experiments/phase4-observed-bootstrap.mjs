/** Isolated acceptance observer. Never imported by the product server.
 * Forwards the original model request unchanged; logs only bounded fact identities.
 */
import {createHash} from 'node:crypto';
import {createCodingServer,httpGateway} from '../dist/server.js';
import {verifyClinePackages} from '../dist/sdk-packages.js';
const chunks=[];
for await(const chunk of process.stdin)chunks.push(chunk);
const {secret}=JSON.parse(Buffer.concat(chunks).toString());chunks.length=0;
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const fields=(value,names)=>Object.fromEntries(names.filter(name=>value?.[name]!==undefined).map(name=>[name,value[name]]));
const gatewayFactory=(...args)=>{
 const gateway=httpGateway(...args);
 let faultArmed=process.env.RUNTIME_ACCEPTANCE_FAULT==='AFTER_PASSED_FACTS';
 return {...gateway,async model(contract,request,signal){
  let verified=null;
  try{
   const text=request.messages.flatMap(m=>m.content).filter(p=>p.type==='text'&&p.text.startsWith('Latest Runtime execution facts,')).at(-1)?.text;
   const facts=text?JSON.parse(text.slice(text.indexOf('{'))):null;
   if(facts?.verification?.verificationStatus==='PASSED')verified=facts.verification;
   console.log(JSON.stringify({acceptanceObservation:'PHASE4_MODEL_REQUEST_FACTS',taskId:contract.taskId,
    operationId:request.operationId,sdkCheckpointOrdinal:request.sdkCheckpointOrdinal,
    requestSha256:hash(request),toolSchemaSha256:hash(request.tools),disclosedTools:request.tools.map(t=>t.name),
    currentCodeManifestHash:facts?.codeManifestHash??null,
    verification:fields(facts?.verification,['schemaVersion','taskId','codeManifestHash','currentCodeManifestHash','executionOperationId',
     'commandHash','verificationPolicy','declaredCommandSha256','exitCode','resultAvailable','executorStopped','stopProofRef','verificationStatus','executedVersionStatus']),
    budget:fields(facts?.budget,['remainingModelCalls','remainingTokens','remainingMillis','maxCodeExecutions','chargedCodeExecutions','remainingCodeExecutions','codeExecutionUnit']),
    independentAcceptanceStatus:facts?.independentAcceptanceStatus??null}));
  }catch{console.log(JSON.stringify({acceptanceObservation:'PHASE4_OBSERVATION_UNAVAILABLE',taskId:contract.taskId,operationId:request.operationId}));}
  if(faultArmed&&verified){
   faultArmed=false;
   console.log(JSON.stringify({acceptanceObservation:'PHASE4_VERIFIED_BEFORE_MODEL',taskId:contract.taskId,
    operationId:request.operationId,codeManifestHash:verified.codeManifestHash,executionOperationId:verified.executionOperationId}));
   // The controller kills this isolated Worker. No model response is fabricated
   // and the pending request has not entered the Java model admission boundary.
   await new Promise(()=>{});
  }
  return gateway.model(contract,request,signal);
 }};
};
const server=createCodingServer({secret,callbackUrl:'http://127.0.0.1:8080',nativeCodingEnabled:true,
 contextOptimizationEnabled:true,sdkPackages:await verifyClinePackages(),gatewayFactory});
server.listen(3100,'0.0.0.0',()=>console.log('PHASE4_OBSERVED_WORKER_READY'));
for(const name of ['SIGTERM','SIGINT'])process.once(name,()=>void server.shutdown());
