import {describe,it,expect} from 'vitest';
import {sqlTaskPresentation,sqlExplorationPhase} from './sqlTaskPresentation';
describe('Cline SQL business progress',()=>{
  it.each([['FINDING_TABLES','正在查找业务表'],['CONFIRMING_FIELDS','正在确认字段关联'],['VALIDATING_SQL','正在校验 SQL']])('shows %s as a business action',(phase,title)=>{
    const events=[{payload:JSON.stringify({sqlProgressPhase:phase,tool:'workspace_metadata'})}];
    expect(sqlExplorationPhase(events)).toBe(title);
    expect(sqlTaskPresentation({task:{status:'RUNNING',taskId:'task'},events}).title).toBe(title);
  });
  it('does not replace the terminal outcome with an old exploration event',()=>{
    const result=sqlTaskPresentation({task:{status:'SUCCESS',taskId:'task'},events:[{payload:{sqlProgressPhase:'FINDING_TABLES'}}]});
    expect(result.title).toBe('分析已完成');expect(result.running).toBe(false);
  });
});
