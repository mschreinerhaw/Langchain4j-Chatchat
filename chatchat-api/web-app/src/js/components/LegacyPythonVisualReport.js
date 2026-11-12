import ReportRenderer from '../../components/ReportRenderer.vue';
import HtmlReportFrame from '../../components/HtmlReportFrame.vue';

// Existing report mounts keep their props; rendering has one implementation.
export default {
  name: 'PythonVisualReport',
  components: {ReportRenderer,HtmlReportFrame},
  props: {report:{type:Object,default:null},fallbackMarkdown:{type:String,default:''}}
};
