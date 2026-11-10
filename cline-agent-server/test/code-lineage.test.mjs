import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {nativeProposal} from '../src/business/code-lineage.mjs';
import {prepareProgram} from '../../coding-agent-node/src/code-repair.js';

const history=source=>({currentSource:source,versions:[{version:1,codeHash:createHash('sha256').update(source).digest('hex')}]});
function replay(before,after){
 const {proposal}=nativeProposal(history(before),after);
 assert.equal(prepareProgram(proposal,before),after);
 return proposal.repair.edits;
}
test('distant native edits preserve a large unchanged program within bounded Runtime patches',()=>{
 const body=Array.from({length:1200},(_,i)=>`value_${i} = ${i}\n`).join('');
 const edits=replay('first = 1\n'+body+'last = 1\n','first = 2\n'+body+'last = 2\n');
 assert.equal(edits.length,2);
 assert(edits.every(e=>e.oldText.length<=16000&&e.newText.length<=16000));
});
test('native delta replay preserves insertion, deletion, repeated anchors and Unicode bytes',()=>{
 for(const [before,after] of [['x=0\ny=0\n','x=1\ny=0\n'],['a\nb\n','a\nnew\nb\n'],['a\nb\n','b\n'],['a=0\nb=0\nc=0\n','a=1\nb=0\nc=1\n'],['文字😀\nvalue=1\n','文字😁\nvalue=2\n']])replay(before,after);
});
test('an indivisible oversized rewrite still fails the existing Runtime limit',()=>{
 const before='old',after='x'.repeat(16001);
 assert.throws(()=>nativeProposal(history(before),after),/REPAIR_SEGMENT_LIMIT/);
});

test('large insertions, deletions and rewrites replay through bounded unique intermediate anchors',()=>{
 const body=Array.from({length:1800},(_,i)=>`field_${i} = "value_${i}😀"\r\n`).join('');
 for(const [before,after] of [
  ['header=1\nfooter=1\n','header=1\n'+body+'footer=1\n'],
  ['header=1\n'+body+'footer=1\n','header=1\nfooter=1\n'],
  ['old_header\n'+body+'old_footer\n','new_header\n'+body.replaceAll('value_','changed_')+'new_footer\n'],
 ]){
  const edits=replay(before,after);
  assert(edits.length<=32);
  assert(edits.every(e=>e.oldText.length>0&&e.oldText.length<=16000&&e.newText.length<=16000));
 }
});

test('consecutive native repairs bind the latest version and source hash',()=>{
 const before='first=1\n'+Array.from({length:1400},(_,i)=>`item_${i}=${i}\n`).join('')+'last=1\n';
 const second=before.replace('first=1','first=2').replace('last=1','last=2');
 replay(before,second);
 const h=history(second);h.versions[0].version=7;
 const third=second.replace('item_700=700','item_700=701');
 const {proposal}=nativeProposal(h,third);
 assert.equal(proposal.repair.parentCodeVersion,7);
 assert.equal(proposal.repair.baseCodeSha256,h.versions[0].codeHash);
 assert.equal(prepareProgram(proposal,second),third);
});

test('large repeated inserted code retains its unique unchanged boundary anchor',()=>{
 const before='unique_header = 1\nunique_footer = 1\n';
 const after='unique_header = 1\n'+'repeated_call()\n'.repeat(4000)+'unique_footer = 1\n';
 const edits=replay(before,after);
 assert(edits.length>1&&edits.length<=32);
 assert(edits.every(e=>e.oldText.length<=16000&&e.newText.length<=16000));
});
