export const PURPOSE_FOLDERS=[
  {id:'data',name:'原始数据',description:'数据文件与输入快照'},
  {id:'scripts',name:'脚本',description:'Python、SQL 与生成代码'},
  {id:'documents',name:'参考文档',description:'规则、说明、图片与知识资料'},
  {id:'history',name:'分析记录',description:'分析报告与上下文'},
  {id:'artifacts',name:'分析产物',description:'分析生成的文件'},
  {id:'logs',name:'运行日志',description:'任务过程与诊断记录'}
];
export const entryKey=file=>`${file.source}:${file.sourceId}`;
export const isUserManagedAsset=file=>file.kind==='DOCUMENT'&&!file.taskId&&!file.runId
  &&(file.sourceRef==='upload'||file.sourceRef?.startsWith('upload:'));
export function defaultPurpose(file){
  if(file.source==='python-data')return 'data';
  if(file.source==='python-script'||file.kind==='CODE'||/\.(py|sql)$/i.test(file.name))return 'scripts';
  if(file.kind==='CONTEXT')return 'history';
  if(file.kind==='LOG'||/\.log$/i.test(file.name))return 'logs';
  if(file.kind==='ARTIFACT')return 'artifacts';
  if(file.kind==='DATASET'||/\.(csv|xls|xlsx|json|jsonl|parquet|orc|zip)$/i.test(file.name))return 'data';
  return 'documents';
}
export function userSpaceEntries(assets=[],data=[],scripts=[],locations=[]){
  const assigned=new Map(locations.map(item=>[`${item.source}:${item.sourceId}`,item.folderId]));
  return [
    ...assets.filter(isUserManagedAsset).map(file=>({source:'asset',sourceId:file.id,name:file.fileName,kind:file.kind,size:file.size,state:file.state,
      updatedAt:file.createdAt,taskId:file.taskId,sha256:file.sha256,contentType:file.contentType,original:file})),
    ...data.filter(file=>file.status!=='DELETED'&&!file.sourceAssetId).map(file=>({source:'python-data',sourceId:file.id,name:file.fileName,kind:'DOCUMENT',size:file.fileSize,
      state:file.status,updatedAt:file.updatedAt||file.createdAt,original:file})),
    ...scripts.map(file=>({source:'python-script',sourceId:file.id,name:file.fileName,kind:'CODE',
      size:new TextEncoder().encode(file.sourceCode||'').length,state:file.status,updatedAt:file.updatedAt||file.createdAt,version:file.currentVersion,original:file}))
  ].map(file=>({...file,key:entryKey(file),folderId:assigned.get(entryKey(file))||defaultPurpose(file)}));
}
export function folderTrail(id,folders){
  const result=[],seen=new Set();let current=folders.find(folder=>folder.id===id);
  while(current&&!seen.has(current.id)){seen.add(current.id);result.unshift(current);current=folders.find(folder=>folder.id===current.parentId);}
  return result;
}
export const canAnalyze=file=>file.source==='asset'&&file.state==='AVAILABLE'&&['DOCUMENT','DATASET','ARTIFACT','CONTEXT'].includes(file.kind)&&!file.name.toLowerCase().endsWith('.py');
export const canDelete=file=>file.source!=='asset'||!file.taskId;
export function validateAnalysisFiles(files){
  const sources=files.filter(file=>file.kind!=='CONTEXT'),history=files.filter(file=>file.kind==='CONTEXT');
  if(!sources.length||sources.length>8||history.length>4||files.some(file=>!canAnalyze(file)))throw new Error('请选择 1–8 个用户文件，可另外引用最多 4 份分析记录');
  if(files.reduce((sum,file)=>sum+file.size,0)>16*1024*1024)throw new Error('本次分析原件合计不能超过 16 MiB');
  return {fileIds:sources.map(file=>file.sourceId),contextIds:history.map(file=>file.sourceId)};
}
