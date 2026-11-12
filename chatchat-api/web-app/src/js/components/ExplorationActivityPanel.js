import {computed,ref,watch,onBeforeUnmount} from 'vue';
import {LoaderCircle} from '@lucide/vue';
import {executionWorkbench} from '../utils/runtimeObservationPresentation';
import {explorationLifecycleStatus} from '../utils/explorationWorkspace';
export default {
  name:'ExplorationActivityPanel',components:{LoaderCircle},
  props:{events:{type:Array,default:()=>[]},receipt:{type:Object,default:()=>({})},running:Boolean,status:{type:String,default:''},startedAt:String,finishedAt:String},
  setup(props){
    const now=ref(Date.now());let timer;
    watch(()=>props.running,running=>{clearInterval(timer);now.value=Date.now();if(running)timer=setInterval(()=>now.value=Date.now(),1000);},{immediate:true});
    onBeforeUnmount(()=>clearInterval(timer));
    const workbench=computed(()=>executionWorkbench(props.events,{canonicalState:props.status||(props.running?'RUNNING':'COMPLETED'),createTime:props.startedAt,finishedAt:props.finishedAt},now.value));
    // Presentation follows genuine events; technical evidence stays in monitoring.
    const businessAction=computed(()=>props.status==='STOPPING'?'正在停止分析…':({
      SUBMITTED:'正在准备分析…',DISCOVERING:'正在查找相关资料…',DATA_READY:'资料已就绪，正在继续分析…',
      ANALYZING:'正在分析资料、整理发现…',EXECUTING:'正在计算和核对数据…',REPORTING:'正在整理分析报告…',
      DELIVERED:'分析报告已就绪',PARTIAL:'已有部分分析结果',INPUT_REQUIRED:'需要补充资料',FAILED:'本次分析未完成'
    }[explorationLifecycleStatus(props.events,props.receipt).code]||'正在分析资料、整理发现…'));
    return {workbench,businessAction};
  }
};
