// @vitest-environment jsdom
import { describe, it, expect, vi, afterEach } from 'vitest';
import { dirtySqlFile, mergeSqlFiles, restoreSqlDrafts, sqlFileTree, assertSqlApplySafe, mergeAppliedSqlFile, terminalSqlTask } from './sqlStudioState';
import { chooseWorkspace } from './workspaceContext';
import { apiRequest } from '../../services/api';
import { submitSqlGeneration } from '../../services/sqlStudioApi';
const file = { id:'f1', path:'sql/customer.sql', content:'SELECT 1;', version:1, editable:true };
afterEach(() => vi.unstubAllGlobals());
describe('SQL engineering drafts and concurrency', () => {
  it('keeps different tabs and file tree directories independent', () => {
    const incoming=[file,{ ...file,id:'f2',path:'sql/orders.sql',content:'SELECT 2;' },{ ...file,id:'m1',path:'mapping/a.csv' }];
    const merged=mergeSqlFiles(incoming,[{ ...file,draft:'user edit',draftBaseVersion:1 }]);
    expect(merged[0].draft).toBe('user edit');expect(merged[1].draft).toBe('SELECT 2;');
    expect(sqlFileTree(merged).map(dir=>dir.children.length)).toEqual([2,1]);
  });
  it('refresh preserves unsaved content and marks remote revision conflict', () => {
    const merged=mergeSqlFiles([{ ...file,content:'agent generation',version:2 }],[{ ...file,draft:'user edit',draftBaseVersion:1 }]);
    expect(merged[0]).toMatchObject({ content:'agent generation',draft:'user edit',draftBaseVersion:1,conflict:true });
  });
  it('restores only server-authorized editable file IDs with revision history', () => {
    const loaded=restoreSqlDrafts(mergeSqlFiles([file,{ ...file,id:'binary',editable:false }],[]),{drafts:[
      {id:'f1',content:'restored',baseVersion:0},{id:'foreign',content:'private',baseVersion:1},{id:'binary',content:'bad',baseVersion:1}]});
    expect(loaded[0]).toMatchObject({ draft:'restored',conflict:true });expect(loaded[1].draft).toBe(file.content);expect(loaded).toHaveLength(2);
  });
  it('blocks applying outputs over unsaved edits case-insensitively', () => {
    expect(()=>assertSqlApplySafe([{path:'SQL/CUSTOMER.SQL'}],[{ ...file,draft:'unsaved' }])).toThrow('不会覆盖');
    expect(()=>assertSqlApplySafe([{path:'sql/new.sql'}],[{ ...file,draft:'unsaved' }])).not.toThrow();
  });
  it('retains edits typed during confirmed apply and forces a later version comparison', () => {
    const merged=mergeAppliedSqlFile({ ...file,content:'generated',version:2 },{ ...file,draft:'typed while saving',draftBaseVersion:1 });
    expect(merged).toMatchObject({ content:'generated',draft:'typed while saving',draftBaseVersion:1,conflict:true });expect(dirtySqlFile(merged)).toBe(true);
    expect(mergeAppliedSqlFile({ ...file,version:2 },{ ...file,draft:file.content })).toMatchObject({ draftBaseVersion:2,conflict:false });
  });
  it('does not mark waiting confirmations as finished and accepts new SQL legacy route', () => {
    expect(terminalSqlTask('WAIT_CONFIRMATION')).toBe(false);expect(terminalSqlTask('RUNNING')).toBe(false);expect(terminalSqlTask('SUCCESS')).toBe(true);
    expect(chooseWorkspace([{workspaceId:'zhice',defaultWorkspace:true},{workspaceId:'data-science'}],{legacyView:'sqlStudio'}).workspaceId).toBe('data-science');
  });
});
describe('SQL uses the existing authenticated API transport',()=>{
  function transport() {
    vi.stubGlobal('localStorage',{getItem:()=>JSON.stringify({token:'same-identity'})});
    const fetch=vi.fn().mockResolvedValue({ok:true,json:async()=>({code:200,data:{taskId:'task'}})});vi.stubGlobal('fetch',fetch);return fetch;
  }
  it('lets the browser set multipart boundary without removing authentication',async()=>{
    const fetch=transport();const form=new FormData();form.append('file',new Blob(['mapping']),'a.txt');
    await apiRequest('/data-science/sql/projects/p/uploads',{method:'POST',body:form});
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer same-identity');expect(fetch.mock.calls[0][1].headers['Content-Type']).toBeUndefined();
  });
  it('submits through original Agent API then binds the persisted generation',async()=>{
    const fetch=transport();await submitSqlGeneration('p',{generationId:'g',taskRequest:{query:'frozen context',idempotencyKey:'sql-generation:g'}});
    expect(fetch.mock.calls.map(call=>call[0])).toEqual(['/api/v1/agent/tasks','/api/v1/data-science/sql/projects/p/generations/g/task']);
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toEqual({taskId:'task'});
  });
  it('uses the same authenticated transport for the server-selected Coding workspace entry',async()=>{
    const fetch=transport();await submitSqlGeneration('p',{generationId:'g',taskRequest:{toolInput:{codingWorkspaceProfile:'SQL_WORKSPACE_V2'}}});
    expect(fetch.mock.calls.map(call=>call[0])).toEqual(['/api/v1/data-science/sql/projects/p/generations/g/coding']);
    expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer same-identity');
  });
});
