import { describe, expect, it } from 'vitest';
import { clampSqlPanel, sqlAssistantAnswer, sqlConversationPrompt } from './sqlIdeState';

describe('SQL IDE state', () => {
  it('keeps the current request and selected source inside the server prompt limit', () => {
    const text = sqlConversationPrompt('请修改目标表字段', [{ role: 'user', text: '先分析 Mapping' }], { path: 'sql/a.sql', text: 'SELECT a' });
    expect(text).toContain('先分析 Mapping');
    expect(text).toContain('sql/a.sql');
    expect(text).toContain('请修改目标表字段');
    expect(sqlConversationPrompt('x'.repeat(5000), [], null).length).toBeLessThanOrEqual(4000);
  });

  it('summarizes proposed files without hiding validation notes', () => {
    expect(sqlAssistantAnswer({ rawAnswer: '{"notes":"需要确认主键"}', files: [{ path: 'sql/a.sql' }], task: { status: 'SUCCESS' } }))
      .toContain('需要确认主键\n建议文件：sql/a.sql');
    expect(clampSqlPanel(50, 180, 500)).toBe(180);
  });
});
