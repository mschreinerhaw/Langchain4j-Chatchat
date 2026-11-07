/** Bounded, non-authoritative facts for the existing SDK prepareTurn hook. */
import {createHash} from 'node:crypto';

const count=value=>Number.isSafeInteger(value)&&value>=0?value:null;
const sha=value=>createHash('sha256').update(value).digest('hex');
function executionStatus(receipt){
  if(receipt?.resultAvailable===false)return 'UNAVAILABLE';
  if(receipt?.resultAvailable!==true||receipt.executorStopped!==true
      ||typeof receipt.stopProofRef!=='string'||!receipt.stopProofRef||!Number.isInteger(receipt.exitCode))return 'UNVERIFIED';
  return receipt.exitCode===0?'PASSED':'FAILED';
}

export function nativeVerificationFact(taskId,currentManifestHash,receipt,matchesDeclaration){
  const recognized=Boolean(receipt&&matchesDeclaration(receipt));
  const executedVersionStatus=recognized?executionStatus(receipt):'NOT_VERIFIED';
  const status=!recognized?'NOT_VERIFIED':receipt.manifestHash!==currentManifestHash?'STALE':executedVersionStatus;
  const fact={schemaVersion:'runtime_os.coding.verification.v1',taskId,
    codeManifestHash:recognized?receipt.manifestHash:null,currentCodeManifestHash:currentManifestHash,
    executionOperationId:recognized?receipt.operationId:null,
    commandHash:recognized?receipt.commandHash:null,verificationPolicy:recognized?receipt.verificationPolicy:null,
    declaredCommandSha256:recognized?receipt.declaredCommandSha256:null,
    exitCode:recognized?receipt.exitCode:null,resultAvailable:recognized?receipt.resultAvailable:null,
    executorStopped:recognized?receipt.executorStopped:null,stopProofRef:recognized?receipt.stopProofRef:null,
    verificationStatus:status,executedVersionStatus};
  if(recognized&&['FAILED','UNAVAILABLE'].includes(status)){
    fact.diagnostics={scope:'HOST_RETURNED_VISIBLE_TEXT_NOT_FULL_ARTIFACT',resultArtifactSha256:receipt.resultArtifactSha256??null};
    for(const name of ['stdout','stderr'])if(typeof receipt[name]==='string'){
      const points=Array.from(receipt[name]);
      fact.diagnostics[name+'Tail']=points.slice(-1024).join('');
      fact.diagnostics[name+'VisibleSha256']=sha(receipt[name]);
      fact.diagnostics[name+'TailTruncated']=points.length>1024;
    }
  }
  return fact;
}

export function nativeResourceFacts(contract,snapshot){
  const maximum=count(contract.budget.maxCodeExecutions),charged=count(snapshot?.chargedUsage?.codeExecutions);
  return{remainingModelCalls:count(snapshot?.remainingModelCalls),remainingTokens:count(snapshot?.remainingTokens),
    remainingMillis:count(snapshot?.remainingMillis),maxCodeExecutions:maximum,chargedCodeExecutions:charged,
    remainingCodeExecutions:maximum===null||charged===null?null:Math.max(0,maximum-charged),
    codeExecutionUnit:'ONE_NORMALIZED_COMMAND_PER_SANDBOX'};
}
