import { createPythonAsset, executePythonScript, fetchMcpPythonEnvironments, fetchPythonWorkbench, publishPythonScript, savePythonScript } from "../../services/api";
import { errorMessage, formatDateTime } from "../utils/uiFormatters";

const starter = `import json\nimport os\n\n# Agent 参数通过环境变量传入\nparams = json.loads(os.environ.get("CHATCHAT_INPUT_JSON", "{}"))\n\ndef main(data):\n    return {"received": data, "message": "hello from isolated Python"}\n\nprint(json.dumps(main(params), ensure_ascii=False))\n`;
export default {
  name: "DataScienceView",
  data: () => ({ tabs:[{id:"environment",label:"Python环境"},{id:"develop",label:"Python开发"},{id:"scripts",label:"我的脚本"}],tab:"environment",loading:true,busy:false,error:"",message:"",assets:[],scripts:[],executions:[],environmentCatalog:[],assetOpen:false,publishOpen:false,consoleText:"",parametersText:"{}",assetForm:{name:"",description:"",environmentId:""},form:{id:"",assetId:"",fileName:"analysis.py",title:"",sourceCode:starter,status:"DRAFT"},publishForm:{templateName:"",scenario:"",description:"",keywords:"",domain:"",version:"1.0.0",inputSchema:'{"type":"object","properties":{}}',outputSchema:'{"type":"object"}'}}),
  computed:{readyAssets(){return this.assets.filter(a=>a.status==="READY");},selectedEnvironment(){return this.environmentCatalog.find(e=>e.id===this.assetForm.environmentId);},canPublish(){return this.form.id&&this.form.status==="TESTED";}},
  mounted(){this.load();},
  methods:{
    async load(){this.loading=true;this.error="";try{const [data,environments]=await Promise.all([fetchPythonWorkbench(),fetchMcpPythonEnvironments()]);this.assets=data?.assets||[];this.scripts=data?.scripts||[];this.executions=data?.executions||[];this.environmentCatalog=environments||[];if(!this.assetForm.environmentId&&this.environmentCatalog[0])this.assetForm.environmentId=this.environmentCatalog[0].id;if(!this.form.assetId&&this.readyAssets[0])this.form.assetId=this.readyAssets[0].id;}catch(e){this.error=errorMessage(e,"工作台加载失败");}finally{this.loading=false;}},
    openAssetDialog(){this.assetOpen=true;this.error="";},
    async createAsset(){await this.action(async()=>{const asset=await createPythonAsset(this.assetForm);this.assetOpen=false;this.message=asset.status==="READY"?"Python Asset 已就绪":"环境创建失败，请检查 Docker 服务和镜像配置";await this.load();});},
    newScript(){this.form={id:"",assetId:this.readyAssets[0]?.id||"",fileName:"analysis.py",title:"",sourceCode:starter,status:"DRAFT"};this.consoleText="";},
    selectScript(s){this.form={id:s.id,assetId:s.assetId,fileName:s.fileName,title:s.title,sourceCode:s.sourceCode,status:s.status};this.tab="develop";this.consoleText="";},
    async save(){await this.action(async()=>{const saved=await savePythonScript(this.form);this.form={...this.form,...saved};this.message="脚本已保存为新版本";await this.load();});},
    async run(){await this.action(async()=>{let parameters;try{parameters=JSON.parse(this.parametersText||"{}");}catch{throw new Error("执行参数必须是合法 JSON");}const result=await executePythonScript(this.form.id,parameters);this.consoleText=[result.stdout,result.stderr].filter(Boolean).join("\n");this.message=result.status==="SUCCEEDED"?"测试执行成功，可以发布模板":"测试执行失败";await this.load();const refreshed=this.scripts.find(s=>s.id===this.form.id);if(refreshed)this.selectScript(refreshed);});},
    async publish(){await this.action(async()=>{await publishPythonScript(this.form.id,this.publishForm);this.publishOpen=false;this.message="Python 模板已发布并注册到 Agent Runtime";await this.load();});},
    async action(fn){this.busy=true;this.error="";this.message="";try{await fn();}catch(e){this.error=errorMessage(e,"操作失败");}finally{this.busy=false;}},
    assetName(id){return this.assets.find(a=>a.id===id)?.name||id;},statusClass(s){return String(s||"").toLowerCase();},formatTime(v){return formatDateTime(v,"-");}
  }
};
