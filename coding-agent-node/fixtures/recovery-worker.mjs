import { createCodingRun } from '../src/runner.js';
let input=''; for await(const chunk of process.stdin) input+=chunk;
const {contract,checkpoint}=JSON.parse(input);
const operations=[];
const gateway={
  reserveModel(_c,m){operations.push(m.operationId);}, settleModel(){},
  preparePython: async()=>({fileName:'sales_20261022T000000000Z_abcdef012345_v1.py',scriptVersion:1,scriptPath:'/workspace/scripts/sales_20261022T000000000Z_abcdef012345_v1.py',workingDirectory:'tasks/'+['a'.repeat(16),'b'.repeat(16),'c'.repeat(16),'d'.repeat(24)].join('/')}),
  executePython(){operations.push('python');return {status:'SUCCEEDED',executorStopped:true,
    resultDatasetRef:'result:fixture',validationRecordRef:'validation:fixture',evidenceBundleRef:'bundle:fixture'};},
  checkpoint(_c,value){if(!checkpoint && value.modelOrdinal===1){
    process.stdout.write(JSON.stringify({checkpoint:value,operations}));process.exit(73);
  }},finish(){},
};
const model={async *stream(request){
  if(!checkpoint){yield {type:'tool-call-delta',toolCallId:'first',toolName:'python_execute',
    input:{fileName:'main.py',source:'# controlled execution fixture'}};
    yield {type:'finish',reason:'tool-calls'};
  }else{
    if(!JSON.stringify(request.messages).includes('result:fixture'))throw new Error('Lost tool result');
    yield {type:'text-delta',text:'Continued from archived tool result'};
    yield {type:'finish',reason:'stop'};
  }
}};
const result=await createCodingRun(contract,gateway,{model,checkpoint,contractHash:'a'.repeat(64)}).run();
process.stdout.write(JSON.stringify({result,operations}));
