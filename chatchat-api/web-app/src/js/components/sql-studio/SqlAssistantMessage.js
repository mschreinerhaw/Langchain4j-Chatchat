import {computed,onBeforeUnmount,ref} from 'vue';
import {Copy} from '@lucide/vue';
import {renderReportNarrative} from '../../utils/reportNarrative';
import {sqlAssistantParts,highlightAssistantSql} from '../../utils/sqlAssistantContent';

export default {
  components:{Copy},
  props:{content:String,sql:String,insertable:Boolean,replaceable:Boolean},
  emits:['insert','replace'],
  setup(props){
    const parts=computed(()=>props.sql!=null?[{type:'sql',content:props.sql}]:sqlAssistantParts(props.content));
    const copied=ref(null),copyError=ref('');let timer;
    async function copy(text,index){
      copyError.value='';let success=false;
      try{if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(text);success=true;}}catch{/* HTTP/permission fallback */}
      if(!success){
        const previous=document.activeElement,field=document.createElement('textarea');field.value=text;
        field.style.cssText='position:fixed;left:-9999px;top:0';document.body.append(field);field.select();
        try{success=document.execCommand?.('copy')===true;}catch{/* keep source selectable */}
        finally{field.remove();previous?.focus?.();}
      }
      if(success){copied.value=index;clearTimeout(timer);timer=setTimeout(()=>{copied.value=null;},1800);}
      else copyError.value='复制未完成，请选中 SQL 后手动复制。';
    }
    onBeforeUnmount(()=>clearTimeout(timer));
    return{parts,copied,copyError,copy,renderReportNarrative,highlightAssistantSql};
  }
};
