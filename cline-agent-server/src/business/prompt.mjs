// Transport the frozen objective once. This module contains no coding or repair policy.
export function promptContext(contract,delivery){
 const {instruction,...objective}=contract.objective??{};
 const {skills,inputFiles,...runtimeContext}=contract.runtimeContext??{};
 if(runtimeContext.analysisInstructions?.[0]==='runtime_os.native_analysis_context.v1'){
  const visible=Object.fromEntries(['pythonEnvironment','codingLifecycle','analysisResultMode','internetAvailability','knowledgeExploration']
   .filter(k=>runtimeContext[k]!==undefined).map(k=>[k,runtimeContext[k]]));
  return {objective,outputContract:{...contract.outputContract,...delivery.context?.outputContract},runtimeContext:visible,inputSnapshotRef:contract.inputSnapshotRef,
   executionLimits:{taskBudget:contract.budget,modelContext:delivery.modelBudget?.modelContext},
   workspacePaths:{nativeSourceRoot:'/poc/project',pythonSourceFileArgument:'Relative to /poc/project; assemble the complete program here before python_execute.',
    sandboxInputRoot:'/data/input',sandboxResultRoot:'/workspace/output/results',
    boundary:'Native file editing cannot write /workspace or /data/input; those paths exist only in Managed Python. The official editor limits each edit text to 6000 UTF-16 code units. Use smaller native edits when required; a partial source fragment is not a completed program.'},
   ...(delivery.context?.fileLayout?{inputLayout:delivery.context.fileLayout}:{}),
   ...(delivery.context?.inputManifest?{tabularTransport:{
    columns:delivery.context.inputManifest.columns,
    hashes:{manifestFileSha256:delivery.context.sha256,logicalContentSha256:delivery.context.inputManifest.logicalContentSha256,
     meaning:'manifestFileSha256 (also inputLayout.expectedManifestSha256) verifies the raw manifest file bytes. manifest.logicalContentSha256 identifies decoded logical data, not the manifest file. These are different hash domains; never compare one to the other or remove a failed integrity check. Use the installed transport reader for verification.'},
    physicalColumns:'Physical Parquet/CSV columns use storageName and separate presence flags, not the logical name. Do not index a raw dataframe by logical names before decoding this mapping.',
    installedReader:{module:'runtime_input',function:'load_rows',
     positionalArguments:['inputLayout.manifestPath','inputLayout.expectedManifestSha256'],
     returns:'Iterator of dictionaries keyed by logical column name; JSON columns are already decoded. For batched datasets each dictionary has datasetReference and record (the original business dictionary). Consume the iterator fully to verify checksums and counts.'}}}:{}),
   analysisContext:{inputVersions:delivery.context?.inputVersions??[],analysisInstructions:runtimeContext.analysisInstructions},
   sourceCodeFiles:delivery.files.filter(file=>file.nativeEditablePath),
   files:delivery.files,contextFile:'runtime-inputs.json',
   inputOrientation:'Read context.inputVersions for dataset roles, exact field declarations and row counts, and context.analysisPreview for bounded authorized format samples. Preview rows are not the complete analytical evidence. Raw printed Sandbox stdout/stderr is archived but not returned to the model; inspect these delivered declarations instead of repeated print-only probes. Calculate the report from the complete immutable files.',
   contextContents:['context.outputContract.rowSchema','context.inputVersions','context.analysisPreview','context.nativeFileSchemas','context.fileLayout','runtimeContext.analysisInstructions','runtimeContext']};
 }
 return {objective,outputContract:contract.outputContract,runtimeContext,inputSnapshotRef:contract.inputSnapshotRef,files:delivery.files,inputs:delivery.context};
}
