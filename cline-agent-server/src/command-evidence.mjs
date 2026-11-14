// Trusted Host evidence. Core can write its project/Core cache, not this tree.
import {mkdirSync,readdirSync,lstatSync,readFileSync,writeFileSync,openSync,writeSync,closeSync,existsSync,renameSync} from 'node:fs';
import {join,relative,resolve} from 'node:path';
import {createHash} from 'node:crypto';
const hash=data=>createHash('sha256').update(data).digest('hex');
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function persistRecord(dir,record){writeFileSync(join(dir,'record.tmp'),JSON.stringify(record),{mode:0o600});renameSync(join(dir,'record.tmp'),join(dir,'record.json'));}
function readRecord(dir,id){
 try{return JSON.parse(readFileSync(join(dir,'record.json'),'utf8'));}
 catch(error){
  if(error.code!=='ENOENT')throw error;
  const record={recordId:id,status:'EVIDENCE_INCOMPLETE',command:{executable:'UNKNOWN',args:[]},sourceSnapshots:[],sourceCaptureTruncated:true,evidenceError:'Host process metadata was not persisted; execution identity/outcome must not be inferred',source:'HOST_EVIDENCE_METADATA_INCOMPLETE'};
  for(const stream of ['stdout','stderr']){const file=join(dir,stream+'.txt');if(existsSync(file)){if(lstatSync(file).isSymbolicLink())throw Error('Invalid execution evidence path');const bytes=readFileSync(file);record[stream]={size:bytes.length,sha256:hash(bytes)};}}
  return record;
 }
}
export function commandEvidence(session,event,emit){
 if(!event||!uuid.test(event.recordId))throw Error('Invalid observed process identity');
 const root=join(session.dir,'execution-records'),dir=join(root,event.recordId);session.processRecords??=new Map();
 if(event.type==='started'){
  if(session.processRecords.has(event.recordId))throw Error('Duplicate observed process');
  mkdirSync(dir,{recursive:true,mode:0o700});
  for(const stream of ['stdout','stderr'])writeFileSync(join(dir,stream+'.txt'),'',{mode:0o600,flag:'wx'});
  const sources=[];let count=0,total=0,truncated=false;
  const record={recordId:event.recordId,pid:event.pid,command:event.command,cwd:event.cwd,startedAt:event.startedAt,
   status:'RUNNING',sourceSnapshots:sources,sourceCaptureTruncated:false,
   sourceSemantics:'Workspace files at process-start observation; not a claim that every module was loaded',source:'HOST_CHILD_PROCESS_OBSERVATION'};
  session.processRecords.set(event.recordId,record);persistRecord(dir,record);
  function walk(path){
   if(count>=512||total>=16*1024*1024){truncated=true;return;}
   for(const entry of readdirSync(path,{withFileTypes:true})){
    const file=join(path,entry.name),name=relative(session.workspace,file);
    if(entry.isSymbolicLink()||['input','.git','node_modules','__pycache__','.venv','venv'].includes(entry.name))continue;
    if(entry.isDirectory())walk(file);
    else if(entry.isFile()&&/\.(py|sh|sql|js|ts)$/i.test(entry.name)){
     const before=lstatSync(file);if(before.size>2*1024*1024||total+before.size>16*1024*1024){truncated=true;continue;}
     const bytes=readFileSync(file),after=lstatSync(file);if(before.size!==after.size||before.mtimeMs!==after.mtimeMs||before.ino!==after.ino){truncated=true;continue;}
     const digest=hash(bytes);writeFileSync(join(dir,'source-'+digest),bytes,{mode:0o600});count++;total+=bytes.length;
     sources.push({path:name,size:bytes.length,sha256:digest,capture:'PROCESS_START_WORKSPACE_SNAPSHOT'});
    }
   }
  }
  const commandText=[event.command?.executable,...(event.command?.args??[])].join(' ');
  try{if(/(?:^|[\s/'"])(?:python(?:3(?:\.11)?)?|pytest|node)(?:[\s'"]|$)/i.test(commandText))walk(session.workspace);}
  catch(error){truncated=true;record.sourceCaptureError={code:error.code??'SOURCE_CAPTURE_FAILED',message:error.message};}
  record.sourceCaptureTruncated=truncated;persistRecord(dir,record);emit('command.started',record);return;
 }
 const record=session.processRecords.get(event.recordId);if(!record)throw Error('Observed process start is missing');
 if(event.type==='data'){
  if(!['stdout','stderr'].includes(event.stream)||!Buffer.isBuffer(event.data)||event.data.length>1024*1024)throw Error('Invalid observed process bytes');
  const fd=openSync(join(dir,event.stream+'.txt'),'a',0o600);try{writeSync(fd,event.data);}finally{closeSync(fd);}return;
 }
 if(event.type==='error'){record.processError=event.error;emit('command.error',{recordId:record.recordId,error:event.error});}
 if(event.type==='completed'){
  Object.assign(record,event,{status:event.exitCode===0?'SUCCEEDED':'FAILED'});delete record.type;
  for(const stream of ['stdout','stderr']){const bytes=readFileSync(join(dir,stream+'.txt'));record[stream]={size:bytes.length,sha256:hash(bytes)};}
  persistRecord(dir,record);emit('command.completed',record);
 }
}
export function executionRecords(session){
 const root=join(session.dir,'execution-records');if(!existsSync(root))return [];
 return readdirSync(root).filter(id=>uuid.test(id)).map(id=>readRecord(join(root,id),id));
}
export function executionContent(session,{recordId,stream,path,offset=0,limit=8*1024*1024}){
 if(!uuid.test(recordId)||!['stdout','stderr','source'].includes(stream)||!Number.isSafeInteger(offset)||offset<0||!Number.isSafeInteger(limit)||limit<1||limit>8*1024*1024)throw Error('Invalid execution content request');
 const dir=join(session.dir,'execution-records',recordId),record=readRecord(dir,recordId);
 const source=stream==='source'?record.sourceSnapshots.find(file=>file.path===path):null;
 if(stream==='source'&&!source)throw Error('Source is not in observed execution snapshot');
 const file=join(dir,stream==='source'?'source-'+source.sha256:stream+'.txt');
 if(lstatSync(file).isSymbolicLink())throw Error('Invalid execution evidence path');
 const data=readFileSync(file),bytes=data.subarray(offset,Math.min(data.length,offset+limit));
 return {recordId,stream,path:source?.path??null,offset,size:bytes.length,totalSize:data.length,sha256:hash(bytes),encoding:'base64',data:bytes.toString('base64')};
}
