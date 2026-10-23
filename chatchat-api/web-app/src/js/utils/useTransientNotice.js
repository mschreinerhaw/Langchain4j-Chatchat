import { watch } from "vue";

export function useTransientNotice(notice, failed, duration = 3500) {
  watch([notice, failed], ([message, isError], _previous, onCleanup) => {
    if (!message || isError) return;
    const timer = setTimeout(() => { notice.value = ""; }, duration);
    onCleanup(() => clearTimeout(timer));
  }, { flush: "sync" });
}
