// @vitest-environment jsdom
import {createApp,h,nextTick} from 'vue';import {afterEach,it,expect,vi} from 'vitest';
const operation=vi.hoisted(()=>vi.fn());vi.mock('../../services/sqlStudioApi',()=>({sqlMetadataOperation:operation}));
import Evidence from './SqlMetadataEvidence.vue';
let app,host;afterEach(()=>{app?.unmount();host?.remove();vi.clearAllMocks();});
async function mount(extra={}){host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(Evidence,{projectId:'project',evidence:{candidates:[{tableRef:'ref',tableName:'jobs',keyColumns:['status'],reason:'实际字段',discoveryId:'d',bindingId:'b',expectedSnapshotId:'s'}]},...extra})});app.mount(host);await nextTick();}
it('reads selected fields through the existing authorized operation instead of trusting the candidate card',async()=>{
  operation.mockResolvedValue({metadata:{status:'SUCCEEDED',snapshotId:'s',items:[{tableRef:'ref',tableName:'jobs',columns:[{name:'status',dataType:'varchar',comment:'Actual indexed comment'}]}]}});await mount();host.querySelector('button').click();await nextTick();await nextTick();
  expect(operation).toHaveBeenCalledWith('project','d','b',{operation:'DESCRIBE',tableName:'jobs',expectedSnapshotId:'s'});expect(host.querySelector('table').textContent).toContain('Actual indexed comment');
});
it('clears old fields when a fresh authorization or snapshot check fails',async()=>{
  operation.mockResolvedValueOnce({metadata:{status:'SUCCEEDED',snapshotId:'s',items:[{tableRef:'ref',tableName:'jobs',columns:[{name:'status'}]}]}}).mockRejectedValueOnce(new Error('Access denied'));
  await mount();host.querySelector('button').click();await nextTick();await nextTick();expect(host.querySelector('table')).not.toBeNull();host.querySelector('button').click();await nextTick();await nextTick();expect(host.querySelector('table')).toBeNull();expect(host.querySelector('[role="alert"]').textContent).toContain('Access denied');
});
it('does not render a different physical table returned under the same snapshot',async()=>{
  operation.mockResolvedValue({metadata:{status:'SUCCEEDED',snapshotId:'s',items:[{tableRef:'other',tableName:'jobs',columns:[{name:'private'}]}]}});
  await mount();host.querySelector('button').click();await nextTick();await nextTick();expect(host.querySelector('table')).toBeNull();expect(host.querySelector('[role=alert]')).not.toBeNull();
});
it('emits only a candidate reference on explicit user action; never loads or executes SQL automatically',async()=>{
  const reference=vi.fn();await mount({onReference:reference});
  [...host.querySelectorAll('button')].find(button=>button.textContent==='引用到工程 SQL').click();
  expect(reference).toHaveBeenCalledWith(expect.objectContaining({discoveryId:'d',bindingId:'b',expectedSnapshotId:'s',tableRef:'ref'}));expect(operation).not.toHaveBeenCalled();
});
it('shows only actual JDBC relationships, without elevating model suggestions into evidence',async()=>{
  operation.mockResolvedValue({metadata:{status:'SUCCEEDED',snapshotId:'s',items:[{tableRef:'ref',tableName:'jobs',columns:[],relationsStatus:'COLLECTED_VISIBLE',foreignKeys:[
    {source:'MODEL',column:'invented',referencedTable:'secret'},
    {source:'JDBC_IMPORTED_KEYS',column:'parent_id',referencedSchema:'public',referencedTable:'parents',referencedColumn:'id'}]}]}});
  await mount();host.querySelector('button').click();await nextTick();await nextTick();
  expect(host.textContent).toContain('public.parents.id');expect(host.textContent).not.toContain('secret');expect(host.textContent).toContain('只证明物理约束');
});
