import { Check, ChevronDown } from '@lucide/vue';
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue';

export default {
  inheritAttrs: false,
  components: { Check, ChevronDown },
  props: {
    options: { type: Array, default: () => [] },
    label: { type: String, required: true },
    minWidth: { type: Number, default: 122 },
    hint: String,
    value: String,
    disabled: Boolean,
    unavailableLabel: { type: String, default: '不可用' },
  },
  emits: ['change'],
  setup(props, { emit }) {
    const listId = `toolbar-options-${useId()}`;
    const trigger = ref(null), menu = ref(null), open = ref(false), active = ref(0);
    const menuPosition = ref({});
    const options = computed(() => props.options);
    const selectedIndex = computed(() => options.value.findIndex(option => option.value === props.value));
    const selectedOption = computed(() => options.value[selectedIndex.value]);
    const selectedLabel = computed(() => selectedOption.value?.label ?? props.unavailableLabel);
    const shortLabel = computed(() => selectedOption.value?.shortLabel ?? selectedOption.value?.label ?? '不可用');
    const unavailable = computed(() => props.disabled || !options.value.length);

    const gap = 6, edge = 8;
    function positionMenu(anchor) {
      if (!menu.value) return;
      const spaceAbove = anchor.top - gap - edge;
      const spaceBelow = window.innerHeight - anchor.bottom - gap - edge;
      const contentHeight = menu.value.scrollHeight + 2;
      const upward = spaceAbove >= contentHeight || spaceAbove >= spaceBelow;
      const maxHeight = Math.max(0, upward ? spaceAbove : spaceBelow);
      const top = upward ? anchor.top - gap - Math.min(contentHeight, maxHeight) : anchor.bottom + gap;
      menuPosition.value = {...menuPosition.value, visibility:'visible', top:`${top}px`, maxHeight:`${maxHeight}px`};
    }

    function outside(event) {
      if (!trigger.value?.contains(event.target) && !menu.value?.contains(event.target)) close();
    }
    function scrolled(event) {
      if (!(event.target instanceof Node) || !menu.value?.contains(event.target)) close();
    }
    function close() {
      if (!open.value) return;
      open.value = false;
      document.removeEventListener('pointerdown', outside);
      window.removeEventListener('scroll', scrolled, true);
      window.removeEventListener('resize', close);
    }
    async function show(index = Math.max(0, selectedIndex.value)) {
      if (unavailable.value || !trigger.value) return;
      trigger.value.focus({ preventScroll: true });
      const anchor = trigger.value.getBoundingClientRect();
      const width = Math.max(0, Math.min(Math.max(anchor.width, props.minWidth), window.innerWidth - edge * 2));
      const left = Math.max(edge, Math.min(anchor.left, window.innerWidth - width - edge));
      active.value = index;
      menuPosition.value = { visibility:'hidden', left:`${left}px`, top:'0px', width:`${width}px` };
      open.value = true;
      document.addEventListener('pointerdown', outside);
      window.addEventListener('scroll', scrolled, true);
      window.addEventListener('resize', close);
      await nextTick();
      positionMenu(anchor);
    }
    function toggle() {
      if (open.value) close();
      else show();
    }
    function choose(index) {
      const option = options.value[index];
      if (!unavailable.value && option) emit('change', option.value);
      close();
    }
    function keyDown(event) {
      if (event.isComposing || unavailable.value) return;
      if (event.key === 'Tab') { close(); return; }
      if (open.value && event.key === 'Escape') {
        event.preventDefault();
        event.stopPropagation();
        close();
        return;
      }
      if (open.value && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault();
        choose(active.value);
        return;
      }
      if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const last = options.value.length - 1;
      const index = event.key === 'Home' ? 0 : event.key === 'End' ? last
        : open.value ? Math.min(last, Math.max(0, active.value + (event.key === 'ArrowDown' ? 1 : -1)))
          : selectedIndex.value >= 0 ? selectedIndex.value : event.key === 'ArrowUp' ? last : 0;
      if (!open.value) show(index);
      else active.value = index;
      nextTick(() => menu.value?.querySelectorAll('[role=option]')[index]?.scrollIntoView?.({ block: 'nearest' }));
    }
    watch([options, unavailable], close);
    onBeforeUnmount(close);
    return { listId, trigger, menu, open, active, options, selectedIndex, selectedLabel, shortLabel, unavailable, menuPosition, toggle, close, keyDown, choose };
  },
};
