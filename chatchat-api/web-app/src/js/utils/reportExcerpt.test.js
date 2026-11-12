import {expect,it} from 'vitest';
import {reportExcerpt} from './reportExcerpt';
it('skips real and flattened table headers and uses actual prose',()=>{
  expect(reportExcerpt('| 项目 | 内容 || --- | --- |','# 报告\n\n| 项目 | 内容 |\n| --- | --- |\n| A | B |\n\n实际分析结论。')).toBe('实际分析结论。');
});
it('does not invent a summary for a table-only report',()=>{
  expect(reportExcerpt('| 项目 | 内容 || --- | --- |','| A | B |\n| --- | --- |\n| 1 | 2 |')).toBe('');
});
it('extracts plain inline text without exposing markup or image paths',()=>{
  expect(reportExcerpt('**结论**见[原文](https://example.org)。\n\n![图](chart.png)')).toBe('结论见原文。');
  expect(reportExcerpt('```python\nprint(1)\n```\n\n结论 `123`。')).toBe('结论 123。');
});
