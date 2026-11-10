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
 assert.throws(()=>prepareProgram(nativeProposal(history(before),after).proposal,before),/REPAIR_SEGMENT_LIMIT/);
});
