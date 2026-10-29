import {expect,it} from 'vitest';
import {sqlAssistantParts,highlightAssistantSql} from './sqlAssistantContent';
it('keeps SQL bytes as code while preserving surrounding Markdown and other language examples',()=>{
  const source="说明\n\n```sql\nSELECT '<img src=x onerror=bad()>' AS v;\n```\n\n结论\n\n```python\nprint(1)\n```";
  const parts=sqlAssistantParts(source);expect(parts.map(x=>x.type)).toEqual(['markdown','sql','markdown']);
  expect(parts[1].content).toBe("SELECT '<img src=x onerror=bad()>' AS v;");expect(parts[2].content).toContain('```python');
  expect(highlightAssistantSql(parts[1].content,null)).toContain('&lt;img');
  expect(highlightAssistantSql(parts[1].content,null)).not.toContain('<img');
});
it('uses the already loaded SQL highlighter and falls back to escaped text on failure',()=>{
  const seen=[];const value=highlightAssistantSql('SELECT 1;',{getLanguage:()=>true,highlight:(text,options)=>{seen.push({text,options});return{value:'<span class="hljs-keyword">SELECT</span> 1;'};}});
  expect(value).toContain('hljs-keyword');expect(seen[0]).toMatchObject({text:'SELECT 1;',options:{language:'sql'}});
  expect(highlightAssistantSql('<unsafe>',{getLanguage:()=>true,highlight(){throw Error('Unavailable');}})).toBe('&lt;unsafe&gt;');
});
