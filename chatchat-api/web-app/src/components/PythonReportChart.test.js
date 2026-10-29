// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect,vi,afterEach,beforeEach} from 'vitest';
const read=vi.hoisted(()=>vi.fn());
vi.mock('../js/utils/pythonVisualReport',()=>({readReportArtifact:read,checkedPlotlyFigure:v=>v,plotlyFrameDocument:()=>'',plotlyDataTable:()=>null}));
import Chart from './PythonReportChart.vue';
let app,host;
beforeEach(()=>{vi.stubGlobal('IntersectionObserver',undefined);vi.stubGlobal('URL',{createObjectURL:vi.fn(()=> 'blob:verified-new-chart'),revokeObjectURL:vi.fn()});read.mockReset();});
afterEach(()=>{app?.unmount();host?.remove();vi.unstubAllGlobals();});
const settle=async()=>{for(let i=0;i<8;i++)await nextTick();};
it('cancels stale bytes and reloads a completed streaming artifact descriptor even when its file ID stays the same',async()=>{
 let first;read.mockReturnValueOnce(new Promise(done=>first=done)).mockResolvedValueOnce(new Uint8Array([1,2,3]).buffer);
 const chart=reactive({id:'trend',title:'Trend',kind:'png',artifact:{fileId:'asset',size:1,mediaType:'image/png'}});
 host=document.createElement('div');app=createApp({render:()=>h(Chart,{chart})});app.mount(host);await settle();
 chart.artifact.size=3;await settle();expect(read).toHaveBeenCalledTimes(2);expect(read.mock.calls[0][1].aborted).toBe(true);
 first(new Uint8Array([9]).buffer);await settle();expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
 expect(host.querySelector('img').getAttribute('src')).toBe('blob:verified-new-chart');
});
it('shows a local rejected-chart placeholder without requesting an absent or unsafe artifact',async()=>{
 host=document.createElement('div');app=createApp({render:()=>h(Chart,{chart:{id:'bad',title:'Rejected',kind:'unavailable',renderError:'CHART_ARTIFACT_REJECTED'}})});app.mount(host);await settle();
 expect(read).not.toHaveBeenCalled();expect(host.querySelector('[role=alert]').textContent).toContain('此图表产物暂不可用');expect(host.querySelector('img,iframe')).toBeNull();
});
it('shows an explicit empty chart state instead of a blank interactive frame',async()=>{
 read.mockResolvedValue(new TextEncoder().encode(JSON.stringify({data:[{type:'scatter',x:[],y:[]}]})).buffer);
 host=document.createElement('div');app=createApp({render:()=>h(Chart,{chart:{id:'empty',title:'Empty',kind:'plotly',artifact:{fileId:'empty',size:42}}})});app.mount(host);await settle();
 expect(host.textContent).toContain('此图表暂无可绘制数据');expect(host.querySelector('iframe')).toBeNull();expect(host.querySelector('[role=alert]')).toBeNull();
});
