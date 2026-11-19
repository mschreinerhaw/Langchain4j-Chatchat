
import assert from 'node:assert/strict';
import test from 'node:test';
import {admitStudioModel,settleStudioModel} from '../src/studio-budget.mjs';

test('Studio reserves the signed model rounds and meters Responses and streaming chat',()=>{
 const record={contract:{studioBudget:{modelCalls:2,modelTokens:1000}}};
 const chat={messages:[{role:'user',content:'check'}],stream:true};
 admitStudioModel(record,chat,false);
 assert.equal(chat.stream_options.include_usage,true);
 assert.ok(chat.max_tokens>0&&chat.max_tokens<1000);
 assert.throws(()=>admitStudioModel(record,{},true),/reconciliation/);
 settleStudioModel(record,{httpStatus:200,usage:{totalTokens:900}});
 const response={input:'small',max_output_tokens:1000};
 admitStudioModel(record,response,true);
 assert.ok(response.max_output_tokens<100);
 settleStudioModel(record,{httpStatus:200,usage:{totalTokens:20}});
 assert.throws(()=>admitStudioModel(record,{},true),/call budget/);
});

test('unknown actual usage prevents another finite-token Studio request',()=>{
 const record={contract:{studioBudget:{modelCalls:-1,modelTokens:1000}}};
 admitStudioModel(record,{messages:[]},false);
 settleStudioModel(record,{httpStatus:200});
 assert.throws(()=>admitStudioModel(record,{},false),/reconciliation/);
 const analysis={contract:{}},body={messages:[]};
 admitStudioModel(analysis,body,false);
 assert.deepEqual(body,{messages:[]});
});
