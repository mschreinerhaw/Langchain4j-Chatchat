// Runtime decides whether repair is authorized. Native transport only enforces its actual stopped receipt.
export const runtimeRepairStopped=receipt=>receipt?.executorStopped===true&&receipt?.resultAvailable!==true
 &&receipt?.executionFeedback?.repair?.action==='STOP';

// Enforce the existing Runtime response-repair grant before another model call.
// The official Core still owns every repair; this adapter never creates a retry.
export function admitModelResponseRepair(run) {
 const feedback=run.modelResponseFeedback;
 if(!feedback)return;
 if((run.modelResponseRepairs??0)>=feedback.maxRepairAttempts){
  run.modelResponseRepairLimitReached=true;
  throw Error('MODEL_RESPONSE_REPAIR_LIMIT_REACHED: '+feedback.diagnosticCode);
 }
 run.modelResponseRepairs=(run.modelResponseRepairs??0)+1;
}
export function observeModelResponse(run,answer) {
 const f=answer.modelResponseFeedback;
 if(answer.error&&f?.schemaVersion==='runtime_os.coding.model_feedback.v1'&&f.repairAllowed===true
  &&['MODEL_OUTPUT_TRUNCATED','MODEL_TOOL_ARGUMENTS_INVALID'].includes(f.diagnosticCode)
  &&Number.isInteger(f.maxRepairAttempts)&&f.maxRepairAttempts>=1&&f.maxRepairAttempts<=8){
  run.modelResponseFeedback=f;
 }else{
  // A grant belongs to the current receipt. An unrelated failure cannot inherit
  // the preceding truncation's permission or consume its remaining repairs.
  run.modelResponseFeedback=null;
  if(!answer.error)run.modelResponseRepairs=0;
 }
}
