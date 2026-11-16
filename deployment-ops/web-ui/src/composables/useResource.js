import { ref, onMounted, onUnmounted } from 'vue';
export function useResource(loader, { initial = null, interval = 0 } = {}) {
  const data = ref(initial), loading = ref(false), error = ref(''), updatedAt = ref(null);
  let controller, timer, disposed = false;
  async function refresh() {
    if (loading.value || disposed) return;
    controller = new AbortController(); loading.value = true;
    try { data.value = await loader(controller.signal); error.value = ''; updatedAt.value = Date.now(); }
    catch (e) { if (e.name !== 'AbortError') error.value = e.message; }
    finally { loading.value = false; }
  }
  onMounted(() => { refresh(); if (interval) timer = setInterval(refresh, interval); });
  onUnmounted(() => { disposed = true; controller?.abort(); clearInterval(timer); });
  return { data, loading, error, updatedAt, refresh };
}
