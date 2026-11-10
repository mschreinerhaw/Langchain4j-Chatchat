import {test} from 'node:test';
import assert from 'node:assert/strict';
import {primaryJsonlResult,requirePythonResults} from '../src/contract-results.js';
test('canonical sorting keeps JSONL as the dataset and admits declared analysis artifacts',()=>{
 const files=['analysis.py','chart.png','report.md','rows.jsonl'];
 assert.equal(requirePythonResults(files,true),'rows.jsonl');
 assert.throws(()=>requirePythonResults(files,false));
 assert.equal(requirePythonResults(['output.jsonl'],false),'output.jsonl');
});
test('missing, ambiguous or unsupported result targets remain denied',()=>{
 for(const files of [['report.md'],['a.jsonl','b.jsonl']])assert.throws(()=>primaryJsonlResult(files));
 for(const file of ['../report.md','run.sh','report.html'])assert.throws(()=>requirePythonResults(['rows.jsonl',file],true));
});
