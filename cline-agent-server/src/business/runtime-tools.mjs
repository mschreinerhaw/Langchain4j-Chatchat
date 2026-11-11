import {createHash} from 'node:crypto';
import {readFileSync,realpathSync} from 'node:fs';
import {resolve,sep} from 'node:path';
import {knowledgeTools} from '../../../coding-agent-node/src/knowledge-tools.js';
import {CodeHistory} from '../../../coding-agent-node/src/code-repair.js';
import {nativeProposal} from './code-lineage.mjs';

// An archived result can be reviewed even when business validation is INCONCLUSIVE.
// Missing fields are not a successful receipt; business completion is checked separately.
export const verifiedReceipt=r=>r?.status==='SUCCEEDED'&&r.executorStopped===true&&r.resultDatasetRef&&r.validationRecordRef&&r.evidenceBundleRef&&['PASSED','INCONCLUSIVE'].includes(r.validationStatus)&&r.resultAvailable===true&&r.executionFeedback?.repair?.action!=='STOP';
export function operationId(contract,toolCallId) {
 return 'cline-'+createHash('sha256').update([contract.scope.tenantId,contract.scope.userId,contract.scope.runId,contract.taskId,toolCallId].join('\0')).digest('hex');
}
const schema=(properties,required=[])=>({type:'object',properties,required,additionalProperties:false});
const string={type:'string'},integer={type:'integer'},object={type:'object'},strings={type:'array',items:string};
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
export function runtimeTools(run,gateway,workspace,emit) {
 const c=run.contract,allowed=new Set(c.authorization.allowedTools),tools=new Map();
 const history=c.runtimeContext?.codingLifecycle?new CodeHistory(c):null;
 const pythonReceipts=new Map();
 const pythonInFlight=new Map();
 const add=(name,description,inputSchema,execute)=>{if(allowed.has(name))tools.set(name,{name,description,inputSchema,execute});};
 const guard=()=>{if(run.cancelRequested||Date.now()>=Date.parse(c.budget.deadlineAt))throw Error('Runtime task stopped or deadline exceeded');};
 const trustedId=context=>{
  if(context?.sessionId!==run.nativeAgentSessionId||context?.epoch!==run.epoch||typeof context?.toolCallId!=='string'||!context.toolCallId)throw Error('Native operation context mismatch');
  return context.sessionId+':'+context.toolCallId;
 };
 const attachReport=async receipt=>{
  if(!verifiedReceipt(receipt)||!allowed.has('artifact_read'))return receipt;
  const artifact=await gateway.readArtifact(c,receipt.resultDatasetRef,run.abort.signal);
  await emit({type:'TOOL_FINISHED',tool:'artifact_read',status:'SUCCEEDED',resultDatasetRef:receipt.resultDatasetRef,source:'RUNTIME_REVIEW_DELIVERY'});
  if(artifact.reportReferenceValidation?.valid!==false)run.reviewDelivery={reference:receipt.resultDatasetRef,modelOrdinal:run.modelOrdinal};
  return {...receipt,reviewedArtifact:artifact};
 };
 for(const t of knowledgeTools(c,gateway,emit))tools.set(t.name,{...t,execute:(args,context)=>t.execute(args,{toolCallId:trustedId(context),signal:run.abort.signal})});
 // These existing governed reads are authorized by Runtime but absent from the
 // legacy helper's definition table. Register them here without changing SDK tools.
 for(const [name,description,properties,required] of [
  ['market_resolve_security','Resolve a security name/code using the authorized maintained directory. Candidates require verification against actual quote evidence; an empty directory match is not proof of absent observations.',{query:{type:'string',maxLength:200},exchange:{type:'string',maxLength:8},limit:{type:'integer',minimum:1,maximum:20}},['query']],
  ['market_get_day_snapshot','Read the complete authorized collected daily source snapshot using an exact discovered dataset, trade_date and source_id. Runtime delivers immutable calculationInput files. It is not a live quote and grants no new source access.',{dataset:{type:'string',maxLength:64},trade_date:{type:'string',format:'date'},source_id:{type:'integer',minimum:1}},['dataset','trade_date','source_id']]
 ])add(name,description,schema(properties,required),async(args,context)=>{
  const toolCallId=trustedId(context);await emit({type:'TOOL_STARTED',tool:name,toolCallId,stage:'KNOWLEDGE_EXPLORATION'});
  const result=await gateway.knowledge(c,name,args,toolCallId,run.abort.signal);
  await emit({type:'TOOL_FINISHED',tool:name,toolCallId,stage:'KNOWLEDGE_EXPLORATION',status:result?.status==='ERROR'?'FAILED':'SUCCEEDED',...(result?.errorCode?{diagnosticCode:result.errorCode}:{})});return result;
 });
 add('input_read','Read bounded authorized logical data using its EXACT delivered datasetReference, not a filesystem path. For staged manifest.json, schema.json or original CSV use native read_files with the nativePath from runtime-inputs.json. /data/input paths exist only inside Managed Python. limit is 1–16000 Unicode code points.',schema({datasetReference:string,offset:{type:'integer',minimum:0},limit:{type:'integer',minimum:1,maximum:16000}},['datasetReference','offset','limit']),(args,context)=>{
  if(args.datasetReference?.startsWith('/'))throw Error('input_read requires an authorized datasetReference, not a Sandbox or native file path. Read the delivered text with native read_files using its exact nativePath in runtime-inputs.json; use /data/input paths only in Managed Python.');
  return gateway.readInput(c,{...args,toolCallId:trustedId(context)},run.abort.signal);
 });
 add('metadata_read','Read an authorized metadata reference.',schema({reference:string},['reference']),args=>gateway.readMetadata(c,args.reference,run.abort.signal));
 add('artifact_read','Read the real archived result for semantic review. No synthetic output.',schema({reference:string},['reference']),async args=>{
  const receipt=await gateway.readArtifact(c,args.reference,run.abort.signal);
  if(args.reference===run.lastReceipt?.resultDatasetRef&&receipt.reportReferenceValidation?.valid!==false)run.reviewedResult=args.reference;
  return receipt;
 });
 add('code_read','Read the current submitted source and its latest actual Runtime receipt. No execution.',schema({}),()=>run.latestCode?{...run.latestCode,pendingOperations:[...run.activeOperations],executionReceipt:run.lastReceipt?.operationId===run.latestCode.operationId?run.lastReceipt:null}:{status:'NOT_SUBMITTED'});
 add('report_submit','Submit a text-only report with exact delivered evidenceReferences; never substitutes Python receipts.',schema({reportMarkdown:string,evidenceReferences:strings,limitations:strings,analysisStatus:{type:'string',enum:['COMPLETE','PARTIAL','INPUT_REQUIRED']},fieldProvenance:{type:'array',items:object}},['reportMarkdown','evidenceReferences','limitations']),async (report,context)=>{
  if(run.pythonStarted)throw Error('Python task must deliver and review actual Python output');
  run.lastOperation=null;run.lastReceipt=null;run.reviewedResult=null;run.reviewDelivery=null;
  const receipt=await gateway.submitReport(c,{toolCallId:trustedId(context),report},run.abort.signal);
  if(verifiedReceipt(receipt)&&receipt.executionMode==='TEXT_ONLY'&&receipt.pythonExecuted===false){run.lastReceipt=receipt;run.lastOperation=receipt.operationId;run.reviewedResult=null;}
  return attachReport(receipt);
 });
 add('sql_validate','Static SQL validation through existing Runtime policy, no database execution.',schema({sql:string,dialect:string},['sql','dialect']),(args,context)=>gateway.validateSql(c,{...args,operationId:operationId(c,'sql_validate\0'+trustedId(context)).replace(/^cline-/,'workspace-')},run.abort.signal));
 add('data_request','Request authorized missing data through an existing authorizedTemplateId; no arbitrary SQL.',schema({requestId:string,datasetRefs:strings,authorizedTemplateId:string,parameterBindings:object,requiredFields:strings,reason:string,expectedUsage:string},['requestId','datasetRefs','authorizedTemplateId','parameterBindings','requiredFields','reason','expectedUsage']),(args)=>{
  run.lastOperation=null;run.reviewedResult=null;run.reviewDelivery=null;
  const id='data-'+createHash('sha256').update(JSON.stringify({request:args.requestId,task:c.taskId})).digest('hex');
  return gateway.requestData(c,{...args,taskId:c.taskId,operationId:id},run.abort.signal);
 });
 add('python_execute','Execute the Python program you wrote in the native workspace through Runtime Managed Python. sourceFile is relative to this workspace. Inputs are mounted at their original /data/input paths. For Runtime tabular snapshots, installed runtime_input.load_rows(manifest_path, expected_manifest_sha256) yields logical dictionaries and decodes JSON/Decimal; batched rows contain datasetReference and record. Raw Parquet columns use manifest.columns[].storageName, not logical names. Raw printed stdout/stderr is archived, not returned to the model: print-only probes cannot reveal rows or schemas to you. Read context.inputVersions and context.analysisPreview in runtime-inputs.json for declared fields and bounded format samples; calculate against the complete immutable input. Write the required JSONL report under /workspace/output/results/ and auxiliary reports/charts there. On failure inspect real executionFeedback, edit your source and invoke again; no shell or network access.',schema({sourceFile:string},['sourceFile']),async (args,context)=>{
  guard();const toolCallId=trustedId(context),id=operationId(c,toolCallId);
  if(pythonReceipts.has(id))return pythonReceipts.get(id);
  if(run.activeOperations.size)throw Error('Previous Python operation unresolved; use code_read for its actual receipt and do not dispatch again');
  run.lastOperation=null;run.reviewedResult=null;run.reviewDelivery=null;
  if(run.lastReceipt?.executionFeedback?.repair?.action==='STOP')throw Error('Runtime repair policy requires STOP');
  const sourceFile=args.sourceFile.startsWith('/workspace/')?args.sourceFile.slice('/workspace/'.length):args.sourceFile;
  const base=realpathSync(workspace),file=realpathSync(resolve(base,sourceFile));if(!file.startsWith(base+sep))throw Error('Source outside native workspace');
  const bytes=readFileSync(file);if(bytes.length>256000)throw Error('Source exceeds Runtime boundary');
  const source=bytes.toString('utf8'),codeSha256=createHash('sha256').update(bytes).digest('hex');
  const {proposal,unchanged}=nativeProposal(history,source);
  const previous=run.lastReceipt;
  if(unchanged&&previous?.executorStopped===true&&previous.resultAvailable!==true
     &&previous.executionFeedback?.repair?.allowed===true
     &&(previous.codeHash??previous.executionFeedback.codeHash)===codeSha256)
   throw Error('UNCHANGED_REPAIR: Runtime requires correcting the last failed program before a new execution. Nothing was prepared or dispatched; edit the native source and execute the corrected complete program.');
  if(history&&history.propose(proposal,unchanged?source:undefined)!==source)throw Error('Native source differs from verified patch lineage');
  // prepare/execute/get reuse the Java durable operation journal and fencing.
  const script=await gateway.preparePython(c,{operationId:id,toolCallId},run.abort.signal);
  const version=history?.record(source,proposal,script,id);
  if(version&&unchanged)version.kind='REEXECUTE';
  await emit({type:'CODE_READY',stage:'CODE_READY',proposedOperationId:id,codeSha256,fileName:script.fileName,scriptPath:script.scriptPath,scriptVersion:script.scriptVersion,sourceBytes:bytes.length,...(version?{codeVersion:version.version,parentCodeVersion:version.parentVersion,parentCodeHash:version.parentHash,changeKind:version.kind}:{})});
  run.pythonStarted=true;run.lastOperation=null;run.reviewedResult=null;run.activeOperations.add(id);
  run.latestCode={operationId:id,fileName:script.fileName,nativeSourceFile:sourceFile,runtimeScript:script,source,codeSha256};
  await emit({type:'TOOL_STARTED',tool:'python_execute',operationId:id,codeSha256});
  let receipt=await gateway.executePython(c,{operationId:id,toolCallId,script,fileName:script.fileName,source,codeSha256},run.abort.signal);
  // Start is a dispatch acknowledgement. The existing get callback owns stopped
  // diagnostics, result validation and archival, even for an immediately stopped run.
  receipt=await gateway.getPython(c,id,run.abort.signal);
  while(!receipt.executorStopped){
   if(receipt.status==='UNKNOWN')throw Error('Python state UNKNOWN; reconciliation required');
   guard();await sleep(500);receipt=await gateway.getPython(c,id,run.abort.signal);
  }
  run.activeOperations.delete(id);run.lastReceipt=receipt;pythonReceipts.set(id,receipt);history?.receipt(receipt);run.codeLifecycle=history?.snapshot();
  if(verifiedReceipt(receipt))run.lastOperation=id;
  await emit({type:'TOOL_FINISHED',tool:'python_execute',operationId:id,status:receipt.status,executorStopped:receipt.executorStopped,resultDatasetRef:receipt.resultDatasetRef});
  const delivered=await attachReport(receipt);pythonReceipts.set(id,delivered);return delivered;
 });
 return {list:()=>[...tools.values()].map(({name,description,inputSchema})=>({name,description,inputSchema})),async call(name,args,context){
  guard();trustedId(context);const tool=tools.get(name);if(!tool)throw Error('Tool outside frozen Runtime authorization');
  if(name!=='python_execute')return tool.execute(args,context);
  const id=operationId(c,trustedId(context));
  if(pythonInFlight.has(id))return pythonInFlight.get(id);
  const pending=Promise.resolve().then(()=>tool.execute(args,context));pythonInFlight.set(id,pending);
  try{return await pending;}finally{pythonInFlight.delete(id);}
 }};
}
