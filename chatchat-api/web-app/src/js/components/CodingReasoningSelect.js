import { SlidersHorizontal } from '@lucide/vue';
import ToolbarOptionPicker from '../../components/ToolbarOptionPicker.vue';
import { reasoningOptions } from '../utils/codingReasoning';

export default {
  inheritAttrs: false,
  components: { SlidersHorizontal, ToolbarOptionPicker },
  props: {
    efforts: { type: Array, default: () => [] },
    value: String,
    disabled: Boolean,
    toolbar: Boolean,
    unavailableLabel: { type: String, default: '当前模型不支持' },
  },
  emits: ['change'],
  computed: {
    options() { return reasoningOptions(this.efforts); },
  },
};
