// @vitest-environment jsdom
import {describe,it,expect,vi,afterEach} from 'vitest';
import {checkedPlotlyFigure,plotlyFrameDocument,readReportArtifact,reviewArchivedVisualReport,plotlyDataTable} from './pythonVisualReport';
vi.mock('../../services/api',()=>({getStoredAuthSession:()=>({token:'scoped-token'})}));
vi.mock('./workspaceContext',()=>({workspaceHeaders:()=>({'X-Workspace-Id':'test'})}));
afterEach(()=>vi.unstubAllGlobals());
describe('Python chart isolation and file authorization',()=>{
 it('retains exact validated series values in a bounded table for an unsupported renderer',()=>{
   expect(plotlyDataTable({data:[{type:'unavailable-type',name:'Measured',labels:['A','B'],values:[1.25,2.5]}]}).rows)
     .toEqual([['Measured','A',1.25],['Measured','B',2.5]]);
   const table=plotlyDataTable({data:[{y:Array.from({length:201},(_,i)=>i)}]});expect(table.rows).toHaveLength(200);expect(table.truncated).toBe(true);
   expect(()=>plotlyDataTable({data:[{y:['<script>bad()</script>']}]})).toThrow();
 });
 it('revalidates only the same owner-bound archived task, operation and result hashes',async()=>{
   const execution={taskId:'task',operationId:'op',codeSha256:'code-hash',resultSha256:'result-hash',resultDatasetRef:'runtime-evidence:result',codeArtifact:{fileId:'file_1'}};
   const reviewed={schemaVersion:'python_visual_report.v1',status:'PARTIAL',execution};
   const fetch=vi.fn().mockResolvedValueOnce({ok:true,json:async()=>({code:200,data:{taskId:'task',runId:'run',sha256:'code-hash'}})})
     .mockResolvedValueOnce({ok:true,json:async()=>({code:200,data:reviewed})});vi.stubGlobal('fetch',fetch);
   expect(await reviewArchivedVisualReport({execution})).toEqual(reviewed);
   expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer scoped-token');
   expect(fetch.mock.calls[1][0]).toContain('/runs/run/coding/task/visual-report?resultRef=runtime-evidence%3Aresult');
   fetch.mockResolvedValueOnce({ok:true,json:async()=>({data:{taskId:'other-task',runId:'run',sha256:'code-hash'}})});
   await expect(reviewArchivedVisualReport({execution})).rejects.toThrow('不匹配');expect(fetch).toHaveBeenCalledTimes(3);
   fetch.mockResolvedValueOnce({ok:true,json:async()=>({data:{taskId:'task',runId:'run',sha256:'code-hash'}})})
     .mockResolvedValueOnce({ok:true,json:async()=>({data:{...reviewed,execution:{...execution,resultSha256:'changed'}}})});
   await expect(reviewArchivedVisualReport({execution})).rejects.toThrow('执行身份不匹配');
 });
 it('keeps figure numbers unchanged and uses an isolated, offline renderer document',()=>{
   const figure={data:[{type:'scatter',x:[1,2],y:[2,4],hovertemplate:'%{y}<extra></extra>'}],layout:{title:{text:'Actual results'}}};
   expect(checkedPlotlyFigure(figure).data[0].y).toEqual([2,4]);
   const document=plotlyFrameDocument('/assets/plotly.js','https://runtime.example/chat');
   expect(document).toContain("connect-src 'none'");expect(document).toContain("default-src 'none'");expect(document).toContain("event.source!==parent");expect(document).not.toContain('scoped-token');
   expect(()=>plotlyFrameDocument('https://other.example/plotly.js','https://runtime.example')).toThrow();
 });
 it.each([
   {data:[{type:'scatter',x:['<script>alert(1)</script>']}]},
   {data:[{type:'image',source:'https://other.example/x'}]},
   {data:[{type:'scatter',url:'https://other.example'}]},
   JSON.parse('{"data":[{"type":"scatter","__proto__":{"polluted":true}}]}'),
   {data:[{type:'scatter',y:[NaN]}]},
   {data:[{type:'scatter',y:{bdata:'AAAAAAAAAA==',dtype:'f8'}}]}
 ])('rejects untrusted plot configurations',value=>expect(()=>checkedPlotlyFigure(value)).toThrow());
 it('reads only opaque file IDs with authorization and verifies declared size',async()=>{
   const fetch=vi.fn().mockResolvedValue({ok:true,arrayBuffer:async()=>new Uint8Array([1,2,3]).buffer});vi.stubGlobal('fetch',fetch);
   await readReportArtifact({fileId:'file_1',size:3});expect(fetch.mock.calls[0][1].headers.Authorization).toBe('Bearer scoped-token');
   expect(fetch.mock.calls[0][0]).toBe('/api/v1/interactions/user-assets/file_1/content');
   await expect(readReportArtifact({fileId:'../other',size:3})).rejects.toThrow();
   await expect(readReportArtifact({fileId:'file_1',size:2})).rejects.toThrow();
   fetch.mockResolvedValue({ok:false,status:403});await expect(readReportArtifact({fileId:'file_1',size:3})).rejects.toThrow('无权访问');
 });
});
