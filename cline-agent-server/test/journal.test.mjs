import assert from 'node:assert/strict';
import { appendFileSync, mkdtempSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { Journal } from '../src/journal.mjs';

test('Journal seeks historical cursors and pages an external writer without collecting history', t => {
 const root=mkdtempSync(join(tmpdir(),'journal-offset-test-')),file=join(root,'events.jsonl');
 t.after(()=>rmSync(root,{recursive:true,force:true}));
 const reader=new Journal(file),writer=new Journal(file);
 assert.deepEqual(reader.page('0',2),{events:[],hasMore:false});
 const payloads=Array.from({length:349},(_,i)=>({text:i%30===0?'规'.repeat(24591):String(i).repeat(i%37+1)}));
 const appended=writer.appendBatch(payloads);
 for(const offset of [348,0,128,5,90,347,128,349,350,12,33,200]){
  const expected=appended.slice(offset,offset+7);
  const page=reader.page(String(offset),7);
  assert.deepEqual(page.events,expected);
  assert.equal(page.hasMore,appended.length>offset+7);
 }
 assert.deepEqual(reader.page('349',3),{events:[],hasMore:false});
 const next=writer.appendBatch([{text:'新'},{text:'event'}]);
 assert.deepEqual(reader.page('349',3),{events:next,hasMore:false});
 assert.deepEqual([...reader.readAfter('347')],[...appended.slice(347),...next]);
 assert.equal(writer.lastEventId,'351');
 assert.throws(()=>reader.page('not-a-cursor',2),/cursor/);
});

test('Journal leaves a concurrent partial record unread and resumes cleanly on the next append', t => {
 const root=mkdtempSync(join(tmpdir(),'journal-tail-test-')),file=join(root,'events.jsonl');
 t.after(()=>rmSync(root,{recursive:true,force:true}));
 const writer=new Journal(file);
 writer.append({text:'first'});
 const committed=statSync(file).size;
 appendFileSync(file,'{"eventId":"2","text":"partial');
 const reader=new Journal(file);
 assert.equal(reader.lastEventId,'1');
 assert.equal(reader.page('1',1).events.length,0);
 assert.ok(statSync(file).size>committed);
 const second=writer.append({text:'second'});
 assert.equal(second.eventId,'2');
 assert.deepEqual(reader.page('1',1),{events:[second],hasMore:false});
 renameSync(file,file+'.old');
 writeFileSync(file,'{"eventId":"1","text":"replacement"}\n');
 assert.deepEqual(reader.page('0',1).events,[{eventId:'1',text:'replacement'}]);
});
