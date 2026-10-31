import { ref, onBeforeUnmount } from 'vue';

// Every pending choice resolves explicitly, including Escape and component navigation.
export function useAppConfirmation() {
  const confirmation = ref(null);
  let resolve;
  function settleConfirmation(accepted) {
    const pending = resolve; resolve = null; confirmation.value = null;
    pending?.(accepted === true);
  }
  function confirmAction(message, options = {}) {
    settleConfirmation(false);
    confirmation.value = { title:'操作确认', message, confirmLabel:'确定', busyLabel:'处理中…', tone:'primary', ...options };
    return new Promise(done => { resolve = done; });
  }
  onBeforeUnmount(() => settleConfirmation(false));
  return { confirmation, confirmAction, settleConfirmation };
}
