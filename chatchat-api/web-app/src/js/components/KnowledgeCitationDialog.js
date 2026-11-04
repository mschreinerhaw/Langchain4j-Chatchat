import { ref, watch, computed } from 'vue';
import { apiRequest } from '../../services/api.js';
export default {
  props: { citationId: { type: String, required: true } }, emits: ['close'],
  setup(props) {
    const source = ref(null), loading = ref(false), error = ref('');
    watch(() => props.citationId, async (id, previous, onCleanup) => {
      const controller = new AbortController(); onCleanup(() => controller.abort());
      source.value = null; error.value = ''; loading.value = true;
      try {
        if (!/^[0-9a-f]{64}$/.test(id)) throw new Error('引用定位无效');
        const result = await apiRequest(`/agent/knowledge/citations/${id}`, { signal: controller.signal, cache: 'no-store' });
        if (!controller.signal.aborted) source.value = result;
      } catch (failure) {
        if (!controller.signal.aborted) error.value = '无法读取此引用：访问权限或文档版本可能已失效，请重新提问获取当前证据。';
      } finally { if (!controller.signal.aborted) loading.value = false; }
    }, { immediate: true });
    const sourceLink=computed(()=>{try{const u=new URL(source.value?.source_url);return ['http:','https:'].includes(u.protocol)&&!u.username&&!u.password?u.href:null;}catch{return null;}});
    const marketRows=computed(()=>source.value?.resource_id==='financial_market'&&Array.isArray(source.value?.source_metadata?.items)?source.value.source_metadata.items:[]);
    const marketColumns=computed(()=>[...new Set(marketRows.value.flatMap(row=>Object.keys(row)))]);
    const formatValue=value=>value==null?'—':typeof value==='object'?JSON.stringify(value):String(value);
    return { source, loading, error, sourceLink, marketRows, marketColumns, formatValue };
  },
};
