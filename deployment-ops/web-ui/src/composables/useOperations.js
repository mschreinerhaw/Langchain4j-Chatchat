import { ref } from 'vue';
import { infrastructureApi } from '../api/infrastructure';
const operation = ref(null), error = ref(''), busy = ref(false);
export function useOperations() {
  return { operation, error, busy,
    async preview(node, spec) {
      busy.value=true; error.value='';
      try { operation.value={node,spec,plan:await infrastructureApi.plan(node,spec)}; }
      catch(e){error.value=e.message;throw e;} finally{busy.value=false;}
    },
    async execute(confirmation) {
      busy.value=true;error.value='';
      try { const job=await infrastructureApi.execute(operation.value.node,operation.value.plan,confirmation);const node=operation.value.node;operation.value=null;return {job,node}; }
      catch(e){error.value=e.message;throw e;}finally{busy.value=false;}
    },
    close(){if(!busy.value)operation.value=null;},
  };
}
