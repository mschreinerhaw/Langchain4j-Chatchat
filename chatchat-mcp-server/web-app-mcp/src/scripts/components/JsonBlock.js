import { prettyJson } from '../../utils/json';

export default {
  name: 'JsonBlock',
  props: {
    value: { type: null, default: null }
  },
  computed: {
    content() {
      return typeof this.value === 'string' ? this.value : prettyJson(this.value, null);
    }
  }
};



