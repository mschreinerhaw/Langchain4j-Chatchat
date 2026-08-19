import { pythonApi } from '../../services/api';
import '../../styles/views/python-management.css';

const emptyForm=()=>({id:'',name:'',description:'',dockerImage:'chatchat-python-runtime:3.11-v1',pythonVersion:'3.11',cpuLimit:'2',memoryLimit:'4g',diskLimit:'2g',tmpfsLimit:'512m',runtimeUser:'10001:10001',networkPolicy:'NONE',networkName:'',requirementsText:'numpy==2.1.0\npandas==2.2.2\nscipy==1.14.0\nscikit-learn==1.5.1\npyarrow==17.0.0\nopenpyxl==3.1.5\nrequests==2.32.3\npydantic==2.8.2\nmatplotlib==3.9.1',timeoutSeconds:300});
export default {
  name:'PythonManagementView',emits:['notify','error'],
  data:()=>({busy:false,tab:'environments',environments:[],templates:[],dialogOpen:false,resultOpen:false,resultText:'',form:emptyForm()}),
  mounted(){this.load();},
  methods:{
    async load(){this.busy=true;try{[this.environments,this.templates]=await Promise.all([pythonApi.environments(),pythonApi.templates()]);}catch(e){this.$emit('error',e);}finally{this.busy=false;}},
    openEnvironment(row){this.form=row?{...row,requirementsText:this.requirementsText(row.requirementsJson)}:emptyForm();this.dialogOpen=true;},
    requirementsText(value){try{return JSON.parse(value||'[]').join('\n');}catch{return ''; }},
    async saveEnvironment(){if(!this.form.name||!this.form.dockerImage){this.$emit('error',new Error('环境名称和 Docker 镜像不能为空'));return;}const payload={...this.form,requirements:String(this.form.requirementsText||'').split(/\r?\n/).map(v=>v.trim()).filter(Boolean)};delete payload.requirementsText;await this.perform(async()=>{await pythonApi.saveEnvironment(payload);this.dialogOpen=false;await this.load();},'环境配置已保存，发布后 API 用户才可选择');},
    async toggleEnvironment(row){await this.perform(async()=>{await pythonApi.publishEnvironment(row.id,row.status!=='PUBLISHED');await this.load();},row.status==='PUBLISHED'?'环境已停用':'环境已发布');},
    async toggleTemplate(row){await this.perform(async()=>{await pythonApi.setTemplateEnabled(row.id,row.status!=='PUBLISHED');await this.load();},row.status==='PUBLISHED'?'模板已停用':'模板已启用');},
    async testTemplate(row){await this.perform(async()=>{const result=await pythonApi.executeTemplate(row.id,{});this.resultText=JSON.stringify(result,null,2);this.resultOpen=true;},'模板试运行完成');},
    async perform(fn,message){this.busy=true;try{await fn();this.$emit('notify',{title:'操作成功',message,type:'success'});}catch(e){this.$emit('error',e);}finally{this.busy=false;}}
  }
};
