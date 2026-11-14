import test from 'node:test';import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';import {tmpdir} from 'node:os';import {join} from 'node:path';
import {commandEvidence,executionRecords,executionContent} from '../src/command-evidence.mjs';
const id='12345678-1234-1234-1234-123456789abc';
test('source snapshot failure retains actual process completion and stream bytes',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'capture-'));try{
  const s={dir,workspace:join(dir,'missing-workspace')},emit=()=>{};
  commandEvidence(s,{type:'started',recordId:id,pid:123,command:{executable:'python3',args:['main.py']},cwd:s.workspace,startedAt:'2026-11-15T00:00:00Z'},emit);
  commandEvidence(s,{type:'data',recordId:id,stream:'stderr',data:Buffer.from('actual fixture failure')},emit);
  commandEvidence(s,{type:'completed',recordId:id,exitCode:1},emit);
  const [r]=executionRecords(s);assert.equal(r.status,'FAILED');assert.equal(r.sourceCaptureTruncated,true);assert.equal(r.sourceCaptureError.code,'ENOENT');assert.deepEqual(r.sourceSnapshots,[]);
  assert.equal(Buffer.from(executionContent(s,{recordId:id,stream:'stderr'}).data,'base64').toString(),'actual fixture failure');
  assert.equal(JSON.parse(await readFile(join(dir,'execution-records',id,'record.json'),'utf8')).exitCode,1);
 }finally{await rm(dir,{recursive:true,force:true});}
});
test('historical incomplete metadata is explicit and does not prevent listing intact records',async()=>{
 const dir=await mkdtemp(join(tmpdir(),'orphan-'));try{
  const record=join(dir,'execution-records',id);await mkdir(record,{recursive:true});await writeFile(join(record,'stdout.txt'),'preserved bytes');
  const [r]=executionRecords({dir});assert.equal(r.status,'EVIDENCE_INCOMPLETE');assert.equal(r.command.executable,'UNKNOWN');assert.equal(r.startedAt,undefined);assert.deepEqual(r.sourceSnapshots,[]);
  assert.equal(Buffer.from(executionContent({dir},{recordId:id,stream:'stdout'}).data,'base64').toString(),'preserved bytes');
 }finally{await rm(dir,{recursive:true,force:true});}
});
