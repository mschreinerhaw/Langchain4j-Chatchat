export default {
  name: "InfoCard",
  props: {
    title: {
      type: String,
      required: true
    },
    action: {
      type: String,
      default: ""
    },
    collapsible: {
      type: Boolean,
      default: false
    },
    defaultCollapsed: {
      type: Boolean,
      default: false
    }
  },
  emits: ["action"],
  data() {
    return {
      collapsed: this.defaultCollapsed
    };
  },
  methods: {
    toggleCollapsed() {
      this.collapsed = !this.collapsed;
    }
  }
};
