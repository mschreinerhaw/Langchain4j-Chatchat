import {it,expect} from 'vitest';
import {questionIdeas} from './questionIdeas';
import {chartDisplayTitle} from './chartDisplayTitle';
it('keeps the original question and guides missing scope without inventing a customer, period or findings',()=>{
 const draft='查询客户000123在2026-01-31的资产';const ideas=questionIdeas({draft,agent:{name:'金融指标分析'}});
 expect(ideas).toHaveLength(4);expect(ideas.every(i=>i.prompt.startsWith(draft))).toBe(true);
 expect(ideas.find(i=>i.title==='查异常与勾稽').prompt).toContain('区分算术差异和口径未确认');
 expect(ideas[0].prompt).toContain('先确认分析对象');
});
it('uses the active analysis for follow-up and offers generic starters without an Agent',()=>{
 expect(questionIdeas({context:{hasResult:true,title:'资产结构'}})[0].prompt).toContain('沿用已确认的对象、期间和口径');
 expect(questionIdeas()[0].prompt).not.toMatch(/0702|2026|资产合计/);
 expect(questionIdeas({agent:{name:'运营分析'}})[2].title).toBe('查异常与一致性');
});
it('relabels technical chart titles without changing metrics or the archived value',()=>{
 const archived={title:'逐日盈亏 (Plotly)'};
 expect(chartDisplayTitle(archived.title)).toBe('逐日盈亏 （动态）');expect(archived.title).toBe('逐日盈亏 (Plotly)');
 expect(chartDisplayTitle('资产（plotly）')).toBe('资产（动态）');expect(chartDisplayTitle('利润 12,000 元')).toBe('利润 12,000 元');
});
