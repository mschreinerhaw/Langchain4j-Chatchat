// @vitest-environment jsdom
import {afterEach,expect,it,vi} from 'vitest';
import {createApp,h,nextTick} from 'vue';
import {artifactPresentationSpec} from './presentation.js';
import EnterpriseUiArtifactRenderer from '../../components/EnterpriseUiArtifactRenderer.vue';
import {fetchUiArtifact,fetchUiArtifactResource} from '../../services/api.js';
vi.mock('../../services/api.js',()=>({fetchUiArtifact:vi.fn(),fetchUiArtifactResource:vi.fn()}));

function legacySpec(bodyType='Markdown') {
 return {root:'root',elements:{root:{type:'Report',props:{},children:['visual','answer','note']},
  visual:{type:'VisualReport',props:{resourceId:'visual-report'},children:[]},
  answer:{type:bodyType,props:{resourceId:bodyType==='Html'?'report':'answer'},children:[]},
  note:{type:'Markdown',props:{resourceId:'independent-note'},children:[]}}};
}

it.each(['Markdown','Html'])('projects legacy %s duplicates without modifying persisted layout or resources',bodyType=>{
 const spec=legacySpec(bodyType),original=JSON.stringify(spec);const projected=artifactPresentationSpec(spec);
 expect(projected.elements.root.children).toEqual(['visual','note']);
 expect(JSON.stringify(spec)).toBe(original);expect(projected.elements.answer).toBe(spec.elements.answer);
});

it('retains normal text reports and independently scoped report bodies',()=>{
 const spec=legacySpec();spec.elements.root.children=['answer','note'];
 expect(artifactPresentationSpec(spec)).toBe(spec);
 spec.elements.other={type:'Report',props:{},children:['visual']};
 expect(artifactPresentationSpec(spec)).toBe(spec);
 expect(artifactPresentationSpec(null)).toBeNull();
});

let app,host;afterEach(()=>{app?.unmount();host?.remove();vi.clearAllMocks();});
it('renders a historical manifest through the real JSON renderer with a single report and no duplicate-answer fetch',async()=>{
 const spec=legacySpec(),original=JSON.stringify(spec);
 const report={schemaVersion:'python_visual_report.v1',artifactValidationStatus:'PASSED',status:'PARTIAL',title:'Single analysis',
  sections:[{title:'Findings',narrative:'Computed findings'}],validation:{warnings:['Business limitation']}};
 fetchUiArtifact.mockResolvedValue({schemaVersion:'enterprise_ui_artifact_v1',spec,resources:{'visual-report':{},answer:{},'independent-note':{}}});
 fetchUiArtifactResource.mockImplementation(async(id,resource)=>resource==='visual-report'?report:resource==='answer'?'Duplicate full answer':'Independent note');
 host=document.createElement('div');document.body.append(host);app=createApp({render:()=>h(EnterpriseUiArtifactRenderer,{artifact:{artifactId:'history'}})});app.mount(host);
 await vi.waitFor(()=>expect(host.querySelectorAll('.python-visual-report')).toHaveLength(1));await nextTick();
 expect(host.textContent).toContain('Computed findings');expect(host.textContent).toContain('Business limitation');
 expect(host.textContent).toContain('Independent note');expect(host.textContent).not.toContain('Duplicate full answer');
 expect(fetchUiArtifactResource.mock.calls.map(call=>call[1])).toEqual(['visual-report','independent-note']);
 expect(JSON.stringify(spec)).toBe(original);
});
