import { defineComponent, computed } from "vue";

export default defineComponent({
  name: "AppPagination",
  props: {
    page: { type: Number, default: 1 },
    pageSize: { type: Number, default: 10 },
    total: { type: Number, default: 0 },
    pageCount: { type: Number, default: 0 },
    disabled: { type: Boolean, default: false },
    ariaLabel: { type: String, default: "分页" },
  },
  emits: ["change"],
  setup(componentProps, { expose: exposeComponent }) {
    exposeComponent();
    const props = componentProps;

    const normalizedPageCount = computed(() =>
      Math.max(1, props.pageCount || Math.ceil(props.total / props.pageSize))
    );
    const currentPage = computed(() =>
      Math.min(Math.max(1, props.page), normalizedPageCount.value)
    );
    const start = computed(() => (props.total ? (currentPage.value - 1) * props.pageSize + 1 : 0));
    const end = computed(() => Math.min(currentPage.value * props.pageSize, props.total));
    return { normalizedPageCount, currentPage, start, end };
  },
});
