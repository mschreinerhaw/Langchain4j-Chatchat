// Transport the frozen objective once. This module contains no coding or repair policy.
export function promptContext(contract,delivery){
 const {instruction,...objective}=contract.objective??{};
 const {skills,inputFiles,...runtimeContext}=contract.runtimeContext??{};
 return {objective,outputContract:contract.outputContract,runtimeContext,inputSnapshotRef:contract.inputSnapshotRef,files:delivery.files,inputs:delivery.context};
}
