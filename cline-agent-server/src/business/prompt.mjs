// Transport the frozen objective once. This module contains no coding or repair policy.
export function promptContext(contract,delivery){
 const {instruction,...objective}=contract.objective??{};
 const {skills,inputFiles,...runtimeContext}=contract.runtimeContext??{};
 if(runtimeContext.nativeAnalysisContract?.schemaVersion==='native-analysis-context.v1'){
  const visible=Object.fromEntries(['pythonEnvironment','codingLifecycle','analysisResultMode','internetAvailability','knowledgeExploration']
   .filter(k=>runtimeContext[k]!==undefined).map(k=>[k,runtimeContext[k]]));
  return {objective,outputContract:{...contract.outputContract,...delivery.context?.outputContract},runtimeContext:visible,inputSnapshotRef:contract.inputSnapshotRef,
   files:delivery.files,contextFile:'runtime-inputs.json',contextContents:['context.outputContract.rowSchema','context.inputVersions','context.nativeFileSchemas','context.fileLayout','runtimeContext.nativeAnalysisContract','runtimeContext']};
 }
 return {objective,outputContract:contract.outputContract,runtimeContext,inputSnapshotRef:contract.inputSnapshotRef,files:delivery.files,inputs:delivery.context};
}
