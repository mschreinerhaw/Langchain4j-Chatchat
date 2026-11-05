import {it,expect,vi,afterEach} from 'vitest';
const request=vi.hoisted(()=>vi.fn());vi.mock('./api',()=>({apiRequest:request}));
import {explorationDatasets,generateExplorationIdeas,stopExploration} from './explorationApi';
afterEach(()=>request.mockReset());
it('stops the exact node with its originating workspace without supplying a client task ID',async()=>{
 const workspaceContext={workspaceId:'data-exploration'};
 await stopExploration('project/id','node/id',workspaceContext);
 expect(request).toHaveBeenCalledWith('/data-science/explorations/project%2Fid/nodes/node%2Fid/stop',
   {method:'POST',body:'{}',workspaceContext});
});
it('sends ideas to the backend with the originating workspace and cancellation signal',async()=>{
 const controller=new AbortController(),workspaceContext={workspaceId:'data-exploration'};
 const body={draft:'核对资产',agentId:'authorized',fileIds:['owned-file']};
 await generateExplorationIdeas(body,{signal:controller.signal,workspaceContext});
 expect(request).toHaveBeenCalledWith('/data-science/explorations/ideas',{method:'POST',body:JSON.stringify(body),signal:controller.signal,workspaceContext});
});
it('shows business data files while excluding chart JSON and internal transport snapshots',async()=>{
  const file=(id,kind,fileName)=>({id,kind,fileName,state:'AVAILABLE'});
  request.mockResolvedValue([file('customer','DOCUMENT','customer-assets.json'),file('export','ARTIFACT','statistics.csv'),file('published','DATASET','account-data.parquet'),
    file('chart','ARTIFACT','composition.json'),file('manifest','DATASET','manifest.json'),file('schema','DATASET','schema.json'),file('transport','DATASET','part-00000.parquet'),file('result','DATASET','rows.jsonl'),file('native','DATASET','original-0.xlsx')]);
  expect((await explorationDatasets()).map(f=>f.id)).toEqual(['customer','export','published']);
});
it('continues paging when an entire page consists of internal files',async()=>{
  request.mockResolvedValueOnce(Array.from({length:100},(_,i)=>({id:i,kind:'DATASET',fileName:'manifest.json',state:'AVAILABLE'})))
    .mockResolvedValueOnce([{id:'customer',kind:'DOCUMENT',fileName:'customer.csv',state:'AVAILABLE'}]);
  expect((await explorationDatasets()).map(f=>f.id)).toEqual(['customer']);expect(request.mock.calls[1][0]).toContain('offset=100');
});
