import ReportRenderer from '../../components/ReportRenderer.vue';

// Existing report mounts keep their props; rendering has one implementation.
export default {
  name: 'PythonVisualReport',
  components: {ReportRenderer},
  props: {report:{type:Object,default:null},fallbackMarkdown:{type:String,default:''}}
};
