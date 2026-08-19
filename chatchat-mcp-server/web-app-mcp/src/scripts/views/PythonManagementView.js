import { pythonApi } from '../../services/api';
import '../../styles/views/python-management.css';

const emptyForm=()=>({id:'',name:'',description:'',dockerImage:'python:3.12-slim',pythonVersion:'3.12',cpuLimit:'2',memoryLimit:'4g',timeoutSeconds:300,networkEnabled:false});
export default {
  name:'PythonManagementView',emits:['notify','error'],
  data:()=>({busy:false,tab:'environments',environments:[],templates:[],dialogOpen:false,resultOpen:false,resultText:'',form:emptyForm()}),
  mounted(){this.load();},
  methods:{
    async load(){this.busy=true;try{[this.environments,this.templates]=await Promise.all([pythonApi.environments(),pythonApi.templates()]);}catch(e){this.$emit('error',e);}finally{this.busy=false;}},
    openEnvironment(row){this.form=row?{...row}:emptyForm();this.dialogOpen=true;},
    async saveEnvironment(){if(!this.form.name||!this.form.dockerImage){this.$emit('error',new Error('环境名称和 Docker 镜像不能为空'));return;}await this.perform(async()=>{await pythonApi.saveEnvironment(this.form);this.dialogOpen=false;await this.load();},'环境配置已保存，发布后 API 用户才可选择');},
    async toggleEnvironment(row){await this.perform(async()=>{await pythonApi.publishEnvironment(row.id,row.status!=='PUBLISHED');await this.load();},row.status==='PUBLISHED'?'环境已停用':'环境已发布');},
    async toggleTemplate(row){await this.perform(async()=>{await pythonApi.setTemplateEnabled(row.id,row.status!=='PUBLISHED');await this.load();},row.status==='PUBLISHED'?'模板已停用':'模板已启用');},
    async testTemplate(row){await this.perform(async()=>{const result=await pythonApi.executeTemplate(row.id,{});this.resultText=JSON.stringify(result,null,2);this.resultOpen=true;},'模板试运行完成');},
    async perform(fn,message){this.busy=true;try{await fn();this.$emit('notify',{title:'操作成功',message,type:'success'});}catch(e){this.$emit('error',e);}finally{this.busy=false;}}
  }
};
