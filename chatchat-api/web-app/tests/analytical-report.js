import { createApp, h } from 'vue';
import * as echarts from 'echarts';
import EnterpriseUiArtifactRenderer from '../src/components/EnterpriseUiArtifactRenderer.vue';
import '../src/styles/pages/chat-assistant.css';

document.body.style.cssText = 'margin:0;padding:16px;background:#f8fafc;font-family:system-ui';
document.querySelector('#app').style.cssText = 'max-width:960px;margin:auto';
createApp({ render: () => h(EnterpriseUiArtifactRenderer, { artifact: { artifactId: 'test-report' } }) }).mount('#app');
window.chartOption = () => echarts.getInstanceByDom(document.querySelector('.visualization-echart'))?.getOption();
