import {describe,it,expect} from 'vitest';
import {pythonProposalSource,sqlGenerationAction,studioReadOnlyAction,pythonAssistantText} from './studioAi';
describe('Studio code proposals',()=>{
  it.each(['审查当前代码，只给审查结果，不要修改代码','解释当前代码的逻辑','Review this SQL without modifying code','检查当前脚本的问题'])('routes read-only requests to an answer: %s',prompt=>{
    expect(studioReadOnlyAction('generate',prompt)).toBe('explain');
    expect(studioReadOnlyAction('explore',prompt)).toBe('explain');
  });
  it.each(['帮我写个支持清算对账和业务审计脚本','审查并修复当前代码','生成测试','优化 SQL'])('keeps explicit source changes in generation: %s',prompt=>{
    expect(studioReadOnlyAction('generate',prompt)).toBe('generate');
  });
  it('keeps database explanations in metadata discovery and preserves explicit edit modes',()=>{
    expect(studioReadOnlyAction('explore','解释这张表的用途')).toBe('explore');
    expect(studioReadOnlyAction('fix','审查代码')).toBe('fix');
  });
  it('preserves review prose even when the proposal has no code changes',()=>{
    expect(pythonAssistantText({action:'generate',notes:'发现空列表会导致除零',code:'unchanged'},'main.py')).toBe('发现空列表会导致除零');
    expect(pythonAssistantText({action:'explain',code:'## 审查结果\n发现除零风险'},'main.py')).toContain('## 审查结果');
  });
  it('reviews exactly the selected replacement while retaining surrounding code',()=>{
    expect(pythonProposalSource({source:'a = 1\nb = 2\nc = 3',selectionOffsets:{start:6,end:11}},
      {action:'fix',code:'b = 20',replaceSelection:true})).toBe('a = 1\nb = 20\nc = 3');
  });
  it('uses the request cursor for continuation rather than the later editor cursor',()=>{
    expect(pythonProposalSource({source:'first\nlast',cursorOffset:6},{action:'continue',code:'middle\n'})).toBe('first\nmiddle\nlast');
  });
  it('reviews test generation as a separate new file',()=>{
    expect(pythonProposalSource({source:'existing()'},{action:'test',code:'test_existing()'})).toBe('test_existing()');
  });
  it('restores the operation from historical SQL requests, preserving legacy defaults',()=>{
    expect(sqlGenerationAction({generation:{prepared:{taskRequest:{toolInput:{sqlStudioAction:'explain'}}}}})).toBe('explain');
    expect(sqlGenerationAction({generation:{}})).toBe('generate');
  });
});
