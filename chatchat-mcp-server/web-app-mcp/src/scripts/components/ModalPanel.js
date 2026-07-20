export default {
  name: 'ModalPanel',
  data() {
    return {
      maximized: false
    };
  },
  props: {
    open: Boolean,
    title: { type: String, default: '' },
    subtitle: { type: String, default: '' },
    wide: Boolean,
    workbench: Boolean,
    maximizable: Boolean
  },
  emits: ['close'],
  methods: {
    toggleMaximized() {
      this.maximized = !this.maximized;
    },
    handleClose() {
      this.maximized = false;
      this.$emit('close');
    }
  }
};
