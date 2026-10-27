import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { executionWorkbench } from '../src/js/utils/runtimeObservationPresentation.js';
const root=new URL('../../../docs/runtime-workbench-20261027/',import.meta.url);
const cases=[['normal-task','done'],['historical-timeout','error'],['historical-failure','error'],
  ['historical-repair','done'],['historical-data-failure','error'],['historical-model-failure','error']];
const results=[];
for(const [label,expected] of cases) {
  const fixture=JSON.parse(await readFile(new URL(label+'-events.json',root),'utf8'));
  const before=JSON.stringify(fixture.steps);
  const result=executionWorkbench(fixture.steps,fixture);
  assert.equal(result.status,expected,label);
  assert.equal(result.terminal,true,label);
  assert.equal(result.steps.length,5,label);
  assert(!result.allActivities.some(op=>['active','waiting','retrying'].includes(op.state)),label);
  const duplicate=executionWorkbench([...fixture.steps,...fixture.steps].reverse(),fixture);
  assert.equal(duplicate.completedOperations,result.completedOperations,label);
  assert.equal(JSON.stringify(fixture.steps),before,label);
  results.push({label,taskId:fixture.taskId,source:'persisted real backend events',
    sourceDeployment:label==='normal-task'?'workbench-r1':'historical',eventCount:fixture.steps.length,
    parentCanonicalState:fixture.canonicalState,displayStatus:result.status,
    completedPhases:result.completed,completedOperations:result.completedOperations,
    replayDeduplicated:true,terminal:true});
}
await writeFile(new URL('replay-verification.json',root),JSON.stringify(results,null,2));
console.log(JSON.stringify(results));
