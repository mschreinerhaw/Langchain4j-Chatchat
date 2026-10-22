// @vitest-environment jsdom
import {createApp,h,nextTick,reactive} from 'vue';import {it,expect,afterEach,vi} from 'vitest';
import PythonVisualReport from './PythonVisualReport.vue';
vi.mock('./PythonReportChart.vue',()=>({default:{props:['chart'],emits:['status'],template:'<div class="chart-stub" @click="$emit(\'status\',{id:chart.id,error:\'render failed\'})">{{chart.title}}</div>'}}));
let app,host;afterEach(()=>{app?.unmount();host?.remove();});
it('hides chart-analysis prose, full text and source controls in historical reports while retaining charts and business sections',async()=>{
 host=document.createElement('div');document.body.append(host);
 const report={status:'SUCCESS',markdown:'Private duplicate body',sections:[{title:'图表分析',narrative:'Redundant chart prose',chartIds:['chart']},{title:'业务结论',narrative:'Business findings'}],charts:[{id:'chart',title:'Retained chart'}],execution:{codeArtifact:{fileId:'archived-source'},status:'SUCCEEDED'}};
 app=createApp({render:()=>h(PythonVisualReport,{report,fallbackMarkdown:'Duplicate fallback'})});app.mount(host);await nextTick();
 for(const text of ['图表分析','完整分析正文','分析代码与执行状态','Private duplicate body','Duplicate fallback','Redundant chart prose'])expect(host.textContent).not.toContain(text);
 expect(host.querySelectorAll('.chart-stub')).toHaveLength(1);expect(host.textContent).toContain('Business findings');
 expect(host.querySelector('.report-code')).toBeNull();expect(host.querySelector('.report-full-text')).toBeNull();
 expect(report.markdown).toBe('Private duplicate body');expect(report.execution.codeArtifact.fileId).toBe('archived-source');
});
it('preserves exact calculated values, displays partial failures and hides duplicate full text',async()=>{
 host=document.createElement('div');document.body.append(host);const props=reactive({report:null,fallbackMarkdown:'Existing text answer'});
 app=createApp({render:()=>h(PythonVisualReport,props)});app.mount(host);await nextTick();expect(host.textContent).toContain('正在整理');
 props.report={schemaVersion:'python_visual_report.v1',status:'PARTIAL',title:'Actual analysis',summary:'Computed summary',metrics:[{id:'total',label:'Total',value:'9007199254740993.01',unit:'units'}],tables:[{id:'table',title:'Computed rows',columns:['value'],rows:[['9007199254740993.01']]}],charts:[{id:'chart',title:'Actual PNG'}],validation:{warnings:['Plotly dependency missing']}};await nextTick();
 expect(host.querySelector('dd').textContent).toContain('9007199254740993.01');expect(host.querySelector('td').textContent).toBe('9007199254740993.01');expect(host.textContent).toContain('部分完成');expect(host.textContent).not.toContain('Existing text answer');expect(host.textContent).toContain('Plotly dependency missing');
 props.report={status:'FAILED',title:'Failed',validation:{warnings:['Python execution failed']}};await nextTick();expect(host.textContent).toContain('校验失败');expect(host.textContent).toContain('Python execution failed');expect(host.textContent).not.toContain('Existing text answer');
 props.report={status:'NO_DATA',title:'Empty',markdown:'No records were available'};await nextTick();expect(host.textContent).toContain('没有可分析数据');
});
it('marks a delivery partial when a chart cannot render without rewriting immutable execution facts',async()=>{
 host=document.createElement('div');document.body.append(host);const report={status:'SUCCESS',title:'Verified calculation',charts:[{id:'chart',title:'Chart'}]};
 app=createApp({render:()=>h(PythonVisualReport,{report})});app.mount(host);await nextTick();host.querySelector('.chart-stub').click();await nextTick();
 expect(host.querySelector('.python-visual-report').dataset.status).toBe('PARTIAL');expect(host.textContent).toContain('部分完成');expect(report.status).toBe('SUCCESS');
});
it('distinguishes unresolved business checks from failed and passed checks',async()=>{
 host=document.createElement('div');document.body.append(host);
 const report={status:'PARTIAL',title:'Bounded analysis',markdown:'Retained authored text',validation:{checks:[{name:'computed',status:'PASSED'},{name:'reconciled',status:'UNRESOLVED',detail:'Evidence is insufficient'},{name:'mismatch',status:'FAILED'}]}};
 app=createApp({render:()=>h(PythonVisualReport,{report})});app.mount(host);await nextTick();
 expect(host.textContent).toContain('尚未解决');expect(host.textContent).toContain('检查失败');expect(host.textContent).toContain('分析程序检查通过');
 expect(host.textContent).not.toContain('Retained authored text');expect(report.validation.checks[1].status).toBe('UNRESOLVED');
});
