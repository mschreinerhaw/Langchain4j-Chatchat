import {it,expect} from 'vitest';
import {boundMessagesForPersistence} from './conversationPersistenceBudget';

it('retains literal code provenance when a restored Studio history is saved again',()=>{
  const source="SELECT 'keep : literal' FROM records WHERE day >= :start_date ;";
  const context={taskOrigin:'studio-coding',codingTaskProtocol:'runtime_os.coding.v2',codingResultFormat:'WORKSPACE_FILES'};
  const [saved]=boundMessagesForPersistence([{id:'message',role:'assistant',content:source,
    taskId:'coding-task',status:'completed',memoryContext:context}]);
  expect(saved.content).toBe(source);
  expect(saved.memoryContext).toEqual(context);
  expect(saved.taskId).toBe('coding-task');
});
