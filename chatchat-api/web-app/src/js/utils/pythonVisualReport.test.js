// @vitest-environment jsdom
import {describe,it,expect,vi,afterEach} from 'vitest';
import {checkedPlotlyFigure,plotlyFrameDocument,readReportArtifact} from './pythonVisualReport';
vi.mock('../../services/api',()=>({getStoredAuthSession:()=>({token:'scoped-token'})}));
vi.mock('./workspaceContext',()=>({workspaceHeaders:()=>({'X-Workspace-Id':'test'})}));
afterEach(()=>vi.unstubAllGlobals());
describe('Python chart isolation and file authorization',()=>{
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
