/** Baseline constructor capability probe. No Agent run or model request is made. */
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createCodingRun} from '../baseline-dist/runner.js';

const original=JSON.parse(await readFile('/probe/contract.json','utf8'));
let modelRequests=0,portRequests=0;
const gateway=new Proxy({},{get(){return async()=>{portRequests++;throw Error('External ports disabled in capability probe');};}});
const model={async*stream(){modelRequests++;throw Error('No model request permitted in capability probe');}};
const cases=[{name:'unchanged-generic-contract',contract:structuredClone(original),differences:[]},
  {name:'existing-workspace-tools-retaining-code-execution',contract:structuredClone(original),
    differences:['Use existing workspace_read/workspace_propose','Use Python Studio result schema; preserve CODE_EXECUTION and original objective/budget/data refs']},
  {name:'existing-analysis-tools-without-added-business-data',contract:structuredClone(original),
    differences:['Use v3 JSON_LINES and required rows.jsonl','Use existing python_execute/code_read','Preserve empty authorized business data refs; no fabricated input']}];
cases[1].contract.authorization.allowedTools=['workspace_read','workspace_propose'];
cases[1].contract.outputContract.resultSchemaRef='python-studio.output.v1';
cases[2].contract.schemaVersion='runtime_os.coding.v3';
cases[2].contract.authorization.allowedTools=['python_execute','code_read'];
cases[2].contract.outputContract.resultFormat='JSON_LINES';
cases[2].contract.outputContract.requiredFiles=['rows.jsonl'];
const results=[];
for(const value of cases){
  let accepted=false,error;
  try{createCodingRun(value.contract,gateway,{model,contractHash:createHash('sha256').update(JSON.stringify(value.contract)).digest('hex')});accepted=true;}
  catch(failure){error=String(failure.message).slice(0,512);}
  results.push({case:value.name,acceptedWithoutExecuting:accepted,error,differences:value.differences,
    originalBudgetUnchanged:JSON.stringify(value.contract.budget)===JSON.stringify(original.budget),
    originalObjectiveUnchanged:JSON.stringify(value.contract.objective)===JSON.stringify(original.objective),
    authorizedBusinessDataRefs:value.contract.authorization.allowedDataRefs.length});
}
const sdk={};
for(const name of ['sdk','agents','core','llms','shared'])sdk[name]=JSON.parse(await readFile('/srv/coding-agent/node_modules/@cline/'+name+'/package.json','utf8')).version;
console.log(JSON.stringify({scope:'BASELINE_CONSTRUCTOR_CAPABILITY_ONLY_NOT_A_PERFORMANCE_TASK',baselineCommit:'e9eda1b9afd86bb2cecb9ade833f3b3023b38929',
  nodeVersion:process.version,sdk,modelRequests,portRequests,results}));
if(modelRequests||portRequests)throw Error('Capability probe crossed its read-only boundary');
process.exit(0);
