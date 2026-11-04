// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';
import {it,expect,vi,afterEach} from 'vitest';
import KnowledgeCitationDialog from './KnowledgeCitationDialog.vue';
const {apiRequest}=vi.hoisted(()=>({apiRequest:vi.fn()}));
vi.mock('../services/api.js',()=>({apiRequest}));
let app,host;
afterEach(()=>{app?.unmount();app=null;host?.remove();vi.resetAllMocks();});
const settle=async()=>{for(let i=0;i<8;i++)await nextTick();};
it('shows independent publication and collection time with the actual original link',async()=>{
 apiRequest.mockResolvedValue({title:'Policy',version:'v1',resource_id:'financial_news',published_at:'2026-11-02T01:00:00Z',collected_at:'2026-11-03T02:00:00Z',source_url:'https://example.org/policy',text:'Original policy article.'});
 host=document.createElement('div');app=createApp({render:()=>h(KnowledgeCitationDialog,{citationId:'a'.repeat(64)})});app.mount(host);await settle();
 expect(host.textContent).toContain('发布时间：2026-11-02T01:00:00Z');expect(host.textContent).toContain('采集时间：2026-11-03T02:00:00Z');
 expect(host.querySelector('a').href).toBe('https://example.org/policy');expect(host.querySelector('pre').textContent).toBe('Original policy article.');
});
it('renders collected market observations as a table and never labels them real time',async()=>{
 apiRequest.mockResolvedValue({title:'Market',resource_id:'financial_market',data_as_of:'2026-11-02',collected_at:'2026-11-03',text:'Original rows',source_metadata:{items:[{security_code:'600001',price:10,observation_date:'2026-11-02'}]}});
 host=document.createElement('div');app=createApp({render:()=>h(KnowledgeCitationDialog,{citationId:'a'.repeat(64)})});app.mount(host);await settle();
 expect(host.textContent).toContain('不代表实时行情');expect(host.textContent).toContain('数据截止：2026-11-02');expect(host.querySelector('table').textContent).toContain('600001');
});
it('clears prior source text while revalidating another citation and rejects unsafe source links',async()=>{
 apiRequest.mockResolvedValueOnce({title:'First',text:'Old text',source_url:'javascript:alert(1)'}).mockRejectedValueOnce(new Error('Revoked'));
 const props=reactive({citationId:'a'.repeat(64)});host=document.createElement('div');app=createApp({render:()=>h(KnowledgeCitationDialog,props)});app.mount(host);await settle();
 expect(host.querySelector('a')).toBeNull();props.citationId='b'.repeat(64);await settle();
 expect(host.textContent).not.toContain('Old text');expect(host.querySelector('[role=alert]')).toBeTruthy();
});
