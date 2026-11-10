// Transport the frozen objective once. This module contains no coding or repair policy.
export function promptContext(contract,delivery){
 const {instruction,...objective}=contract.objective??{};
 const {skills,inputFiles,...runtimeContext}=contract.runtimeContext??{};
 if(runtimeContext.analysisInstructions?.[0]==='runtime_os.native_analysis_context.v1'){
  const visible=Object.fromEntries(['pythonEnvironment','codingLifecycle','analysisResultMode','internetAvailability','knowledgeExploration']
   .filter(k=>runtimeContext[k]!==undefined).map(k=>[k,runtimeContext[k]]));
  return {objective,outputContract:{...contract.outputContract,...delivery.context?.outputContract},runtimeContext:visible,inputSnapshotRef:contract.inputSnapshotRef,
   executionLimits:{taskBudget:contract.budget,modelContext:delivery.modelBudget?.modelContext},
   ...(delivery.context?.fileLayout?{inputLayout:delivery.context.fileLayout}:{}),
   analysisContext:{inputVersions:delivery.context?.inputVersions??[],analysisInstructions:runtimeContext.analysisInstructions},
   sourceCodeFiles:delivery.files.filter(file=>file.nativeEditablePath),
   files:delivery.files,contextFile:'runtime-inputs.json',
   inputOrientation:'Read context.inputVersions for dataset roles, exact field declarations and row counts, and context.analysisPreview for bounded authorized format samples. Preview rows are not the complete analytical evidence. Raw printed Sandbox stdout/stderr is archived but not returned to the model; inspect these delivered declarations instead of repeated print-only probes. Calculate the report from the complete immutable files.',
   contextContents:['context.outputContract.rowSchema','context.inputVersions','context.analysisPreview','context.nativeFileSchemas','context.fileLayout','runtimeContext.analysisInstructions','runtimeContext']};
 }
 return {objective,outputContract:contract.outputContract,runtimeContext,inputSnapshotRef:contract.inputSnapshotRef,files:delivery.files,inputs:delivery.context};
}
