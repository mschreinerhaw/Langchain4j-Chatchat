import { describe, it, expect } from 'vitest';
import { beginStudioExchange, studioAssistantTurn, updateStudioTurn } from './studioConversation';

describe('Studio conversation lifecycle', () => {
  it('keeps user requests and their replies in separate ordered exchanges', () => {
    const messages=[];
    const first=beginStudioExchange(messages,'解释文件','explain','a.sql');
    updateStudioTurn(studioAssistantTurn(messages,first),'ready','解释完成');
    const second=beginStudioExchange(messages,'修复文件','fix','a.sql');
    expect(messages.map(turn=>turn.role)).toEqual(['user','assistant','user','assistant']);
    expect(studioAssistantTurn(messages,first)).toMatchObject({text:'解释完成',action:'explain'});
    expect(studioAssistantTurn(messages,first).activity.at(-1).title).toBe('已收到本轮结果');
    expect(studioAssistantTurn(messages,second)).toMatchObject({status:'planning',action:'fix'});
    expect(studioAssistantTurn(messages,second).activity).toHaveLength(1);
  });
  it('does not invent progress or finish an active stage on repeated polling', () => {
    const messages=[];const id=beginStudioExchange(messages,'生成','generate');const turn=studioAssistantTurn(messages,id);
    updateStudioTurn(turn,'running','等待模型');updateStudioTurn(turn,'running','仍在等待模型');
    expect(turn.activity).toHaveLength(2);
    expect(turn.activity.filter(entry=>entry.status==='active')).toHaveLength(1);
    expect(turn.activity.some(entry=>entry.title.includes('工具执行完成'))).toBe(false);
  });
  it('retains diagnostics after a prepared SQL generation replaces the local message ID', () => {
    const messages=[];const id=beginStudioExchange(messages,'生成','generate');const turn=studioAssistantTurn(messages,id);
    turn.id='assistant:generation';turn.generationId='generation';
    updateStudioTurn(studioAssistantTurn(messages,id),'failed','提交失败：request-123');
    expect(turn).toMatchObject({status:'failed',text:'提交失败：request-123'});
    expect(turn.activity[0].status).toBe('error');
  });
});
