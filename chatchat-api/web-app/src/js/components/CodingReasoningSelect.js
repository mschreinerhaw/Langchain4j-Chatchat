import { ChevronDown, SlidersHorizontal } from '@lucide/vue';
import { reasoningOptions } from '../utils/codingReasoning';

export default {
  components: { ChevronDown, SlidersHorizontal },
  props: {
    efforts: { type: Array, default: () => [] },
    value: String,
    disabled: Boolean,
    toolbar: Boolean,
    unavailableLabel: { type: String, default: '当前模型不支持' },
  },
  emits: ['change'],
  computed: {
    options() {
      return reasoningOptions(this.efforts);
    },
    selectedLabel() {
      return this.options.find(option => option.value === this.value)?.label ?? this.options[0]?.label ?? '不可用';
    },
  },
};
