import { describe, expect, it } from 'vitest';
import { clampSqlPanel, restoreSqlLayout, sqlAssistantAnswer, sqlConversationPrompt } from './sqlIdeState';
import { sqlTaskPresentation } from './sqlTaskPresentation';

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

  it('restores only supported layout settings before first render', () => {
    expect(restoreSqlLayout({ explorerWidth: 300, assistantWidth: 480, bottomHeight: 220, bottomTab:'validation', assistant:false }))
      .toMatchObject({ explorerWidth:300, assistantWidth:480, bottomHeight:220, bottomTab:'validation', assistant:false });
    expect(restoreSqlLayout({explorerWidth:9999,assistantWidth:-20,bottomHeight:'invalid',bottomTab:'bad'}))
      .toMatchObject({explorerWidth:520,assistantWidth:180,bottomHeight:180,bottomTab:'problems'});
    expect(restoreSqlLayout(null).assistant).toBe(true);
  });

  it('shows preparation before a task exists without inventing completion percentages', () => {
    const progress = sqlTaskPresentation(null, 'PREPARING', 10000, 14000);
    expect(progress).toMatchObject({title:'正在准备工程上下文',running:true,terminal:false,elapsed:4,elapsedLabel:'00:04'});
    expect(progress.detail).not.toContain('计划通过前不会开始探索');
    expect(progress).not.toHaveProperty('percentage');
    expect(sqlTaskPresentation(null).title).toBe('准备就绪');
    expect(sqlTaskPresentation({generation:{id:'prepared'}}).title).toBe('任务尚未提交');
  });

  it('uses typed runtime events and task states without interpreting log text as progress', () => {
    const result = {task:{status:'RUNNING'},events:[{sequence:1,type:'RUNTIME_OBSERVATION',payload:JSON.stringify({payload:{metadata:{eventKind:'MODEL_INFERENCE',eventState:'STARTED'}}})}]};
    expect(sqlTaskPresentation(result)).toMatchObject({title:'正在分析工程',detail:'模型分析中',running:true});
    expect(sqlTaskPresentation({...result,task:{status:'WAIT_TOOL'}})).toMatchObject({title:'正在执行工具',detail:'正在等待工具执行结果。'});
    expect(sqlTaskPresentation({task:{status:'RUNNING'},events:[{type:'LOG',payload:'finished successfully'}]}).terminal).toBe(false);
    expect(sqlAssistantAnswer({...result,task:{status:'WAIT_MODEL'}})).toContain('模型分析中');
  });

  it('freezes elapsed time and closes active runtime steps when the task finishes', () => {
    const result = {generation:{applied:false},task:{status:'SUCCESS',createTime:'2026-10-14T01:00:00Z',finishedAt:'2026-10-14T01:01:03Z'},
      files:[{path:'sql/a.sql'}],events:[{type:'RUNTIME_OBSERVATION',payload:{metadata:{eventKind:'MODEL_INFERENCE',eventState:'STARTED'}}}]};
    const progress=sqlTaskPresentation(result,'',0,Date.parse('2026-10-14T02:00:00Z'));
    expect(progress).toMatchObject({title:'分析已完成',detail:'1 个文件待确认保存',elapsedLabel:'01:03',running:false,terminal:true});
    expect(progress.steps[0].status).toBe('done');
    expect(sqlTaskPresentation({...result,task:{...result.task,status:'CANCELLED'}}).steps[0].status).toBe('cancelled');
  });

  it('reports failed and invalid outputs without treating them as successful file generation', () => {
    expect(sqlTaskPresentation({task:{status:'FAILED',errorMessage:'模型不可用'}})).toMatchObject({title:'分析失败',detail:'模型不可用',running:false});
    expect(sqlTaskPresentation({task:{status:'SUCCESS'},outputError:'输出格式无效'})).toMatchObject({title:'生成结果需要检查',detail:'输出格式无效'});
    expect(sqlTaskPresentation({task:{status:'RUNNING'},events:[{type:'RUNTIME_OBSERVATION',payload:'broken json'}]}).steps).toEqual([]);
  });
});
