export default {
  name: 'ModalPanel',
  props: {
    open: Boolean,
    title: { type: String, default: '' },
    subtitle: { type: String, default: '' },
    wide: Boolean
  },
  emits: ['close']
};



