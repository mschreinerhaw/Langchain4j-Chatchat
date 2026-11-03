
import {ref, watch, nextTick} from 'vue';
import {X, Ellipsis, Trash2} from '@lucide/vue';
import AppConfirmDialog from '../../../components/AppConfirmDialog.vue';
export default { components:{AppConfirmDialog,X,Ellipsis,Trash2}, props:{open:Boolean,label:{type:String,default:'历史会话'},entries:{type:Array,default:()=>[]},currentId:String,busy:Boolean,loading:Boolean,error:String,hasMore:Boolean,remove:{type:Function,required:true}}, emits:['close','select','more'], setup(props,{emit}){

const dialog=ref(null),menuId=ref(null),selected=ref(null),deleting=ref(false),failure=ref('');
function close(){if(!deleting.value){selected.value=null;menuId.value=null;emit('close');}}
function askDelete(entry){menuId.value=null;failure.value='';selected.value=entry;}
async function confirmDelete(){
  if(deleting.value || !selected.value)return;
  deleting.value=true;
  try{await props.remove(selected.value.id);selected.value=null;}
  catch(error){failure.value=error.message || '删除失败，请重试';selected.value=null;}
  finally{deleting.value=false;}
}
watch(()=>props.open,async open=>{
  await nextTick();const node=dialog.value;if(!node)return;
  if(open){failure.value='';if(!node.open){if(node.showModal)node.showModal();else node.setAttribute('open','');}}
  else{if(node.open)node.close?.();node.removeAttribute('open');menuId.value=null;selected.value=null;}
},{immediate:true});

return {dialog,menuId,selected,deleting,failure,close,askDelete,confirmDelete};
}};
