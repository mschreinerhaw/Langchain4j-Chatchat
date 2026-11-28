import test from 'node:test';
import assert from 'node:assert/strict';
import {productionSource,reviewCategories,reviewDiff} from '../../scripts/check-business-hardcoding.mjs';

test('review check flags added production literals by location without emitting their values',()=>{
  const path='product/src/main/java/Example.java';
  const diff=[
    '+++ b/'+path,
    '@@ -9,1 +9,3 @@',
    '-var accountNo = "11112222";',
    '+var accountNo = "99990000";',
    '+var host = "http://192.168.14.15:1000";',
    '+if (tool.equals("Docker")) {}',
    '+++ b/product/src/test/java/ExampleTest.java',
    '@@ -0,0 +1 @@',
    '+var modelAlias = "qwen-test-model";'
  ].join('\n');
  assert.deepEqual(reviewDiff(diff),[
    {path,line:9,category:'account-literal'},
    {path,line:10,category:'private-network-literal'},
    {path,line:11,category:'scenario-conditional'}
  ]);
  assert.deepEqual(reviewCategories('var runtime = "http://127.0.0.1:18329/model/v1";'),[]);
  assert.deepEqual(reviewCategories('const host = "10.16.25.30";'),['private-network-literal']);
  assert.deepEqual(reviewCategories('if (type.equals("REPORT")) {}'),[]);
  assert.equal(productionSource('app/src/tool.fixtures.test.ts'),false);
});

test('a literal added after an unchanged field declaration retains its review context',()=>{
  const path='product/src/main/java/Report.java';
  const diff=[
    '+++ b/'+path,
    '@@ -6,2 +6,2 @@',
    ' String modelAlias =',
    '-  settings.defaultModel();',
    '+  "llama-review-fixture";',
    '@@ -15,1 +15,3 @@',
    '+long accountNumber = 60001234567L;',
    '+String reportBasename = "fixed-customer-report.html";',
    '+long testBudget = 30;',
    ' var protocol = "output/report";'
  ].join('\n');
  assert.deepEqual(reviewDiff(diff),[
    {path,line:7,category:'bound-identity-literal'},
    {path,line:15,category:'account-literal'},
    {path,line:16,category:'fixed-report-name'},
    {path,line:17,category:'test-budget-literal'}
  ]);
  assert.equal(productionSource('cline-agent-server/monitor/backend.mjs'),true);
  assert.deepEqual(reviewCategories('const reportFilePath = "output/report/generated.html";'),['fixed-report-name']);
  assert.deepEqual(reviewCategories('const samplingSeed = 60001234567;', 'const accountId = request.accountId;'),[]);
});
