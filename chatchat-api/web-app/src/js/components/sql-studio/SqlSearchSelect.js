import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue';
import { Check, ChevronDown } from '@lucide/vue';

export default {
  components: { Check, ChevronDown },
  props: { modelValue: { type:String, default:'' }, options: { type:Array, default:()=>[] },
    label: String, placeholder: String, disabled: Boolean, loading: Boolean, hasMore: Boolean },
  emits: ['update:modelValue', 'change', 'more'],
  setup(props, { emit }) {
    const root=ref(null), input=ref(null), open=ref(false), query=ref(''), active=ref(-1), listId=`sql-choice-${useId()}`;
    const selectedLabel=computed(()=>props.options.find(item=>item.value===props.modelValue)?.label || '');
    const filtered=computed(()=>props.options.filter(item=>item.label.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase())));
    function close(){open.value=false;query.value='';active.value=-1;}
    function show(){if(props.disabled || open.value)return;open.value=true;query.value='';active.value=filtered.value.findIndex(item=>item.value===props.modelValue);}
    function toggle(){if(open.value)close();else {show();input.value?.focus();}}
    function search(value){if(props.disabled)return;open.value=true;query.value=value;active.value=filtered.value.length?0:-1;}
    function choose(option){if(props.disabled || !option)return;emit('update:modelValue',option.value);emit('change',option.value);close();input.value?.focus();}
    function keyDown(event){
      if(event.isComposing || props.disabled)return;
      if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();return;}
      if(event.key==='Tab'){close();return;}
      if(event.key==='Enter') {if(open.value){event.preventDefault();choose(filtered.value[active.value]);}return;}
      if(!['ArrowDown','ArrowUp','Home','End'].includes(event.key))return;
      event.preventDefault();show();
      const count=filtered.value.length;
      active.value=count?event.key==='Home'?0:event.key==='End'?count-1:Math.max(0,Math.min(count-1,active.value+(event.key==='ArrowUp'?-1:1))):-1;
      nextTick(()=>document.getElementById(`${listId}-${active.value}`)?.scrollIntoView?.({block:'nearest'}));
    }
    function outside(event){if(!root.value?.contains(event.target))close();}
    function focusOut(event){if(!root.value?.contains(event.relatedTarget))close();}
    onMounted(()=>{document.addEventListener('pointerdown',outside);root.value?.addEventListener('focusout',focusOut);});
    onBeforeUnmount(()=>{document.removeEventListener('pointerdown',outside);root.value?.removeEventListener('focusout',focusOut);});
    watch(()=>props.disabled,value=>{if(value)close();});
    watch(()=>props.modelValue,close);
    watch(filtered,values=>{if(active.value>=values.length)active.value=values.length?0:-1;});
    return {root,input,open,query,active,listId,selectedLabel,filtered,show,toggle,search,choose,keyDown};
  }
};
