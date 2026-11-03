import {computed} from 'vue';
import {Plus,PanelLeftClose,ChevronDown,ChevronRight,LoaderCircle,CircleCheck,CircleAlert,CircleMinus,ChartColumn} from '@lucide/vue';
import {explorationTreeRows,explorationNodeState} from '../utils/explorationWorkspace';
export default {
  name:'ExplorationChain',components:{Plus,PanelLeftClose,ChevronDown,ChevronRight,LoaderCircle,CircleCheck,CircleAlert,CircleMinus,ChartColumn},
  props:{nodes:{type:Array,default:()=>[]},selectedId:{type:String,default:''},collapsed:{type:Array,default:()=>[]},runningId:{type:String,default:''}},
  emits:['select','toggle','new-root','collapse'],
  setup(props){return {rows:computed(()=>explorationTreeRows(props.nodes,props.collapsed).map(node=>({...node,state:explorationNodeState(node,props.runningId)})))};}
};
