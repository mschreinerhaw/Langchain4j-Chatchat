export default {
  props: { tab: String, problemCount: Number, error: String, message: String, result: Object, generations: Array },
  emits: ['tab', 'close', 'generation'],
  setup() {
    return { tabs: [
      { id: 'problems', label: 'Problems' },
      { id: 'output', label: 'Output' },
      { id: 'tasks', label: 'Tasks' },
      { id: 'validation', label: 'Validation' }
    ] };
  }
};
