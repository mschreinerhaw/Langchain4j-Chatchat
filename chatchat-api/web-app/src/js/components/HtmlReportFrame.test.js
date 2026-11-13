// @vitest-environment jsdom
import {it,expect,vi} from 'vitest';
import {createApp,h,nextTick} from 'vue';
import HtmlReportFrame from '../../components/HtmlReportFrame.vue';
vi.mock('../utils/htmlReport',()=>({loadHtmlReport:vi.fn(async()=>'<html><body>Report</body></html>')}));
it('sizes the report from its own sandbox and rejects foreign or invalid height messages',async()=>{
 const host=document.createElement('div');document.body.append(host);
 const app=createApp({render:()=>h(HtmlReportFrame,{report:{entryPath:'report.html'}})});
 try{
  app.mount(host);await Promise.resolve();await nextTick();
  const frame=host.querySelector('iframe');expect(frame).not.toBeNull();
  const send=(source,height)=>window.dispatchEvent(new MessageEvent('message',{source,data:{type:'runtime-html-report-size',height}}));
  send(window,2400);await nextTick();expect(frame.style.height).toBe('480px');
  send(frame.contentWindow,2400);await nextTick();expect(frame.style.height).toBe('2400px');
  for(const invalid of [-1,Infinity,'9999',200001])send(frame.contentWindow,invalid);
  await nextTick();expect(frame.style.height).toBe('2400px');
  send(frame.contentWindow,800);await nextTick();expect(frame.style.height).toBe('800px');
  expect(host.querySelector('header').textContent).not.toContain('分析报告');
  expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
 }finally{app.unmount();host.remove()}
});
