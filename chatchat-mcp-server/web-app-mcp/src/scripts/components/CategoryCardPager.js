export default {
  name: "CategoryCardPager",
  props: {
    cards: { type: Array, default: () => [] },
    selectedKey: { type: String, default: "" },
    ariaLabel: { type: String, default: "业务分类" },
  },
  emits: ["select"],
  data() {
    return {
      page: 1,
      visibleColumns: 6,
      gridObserver: null,
    };
  },
  computed: {
    pageSize() {
      return Math.max(2, this.visibleColumns * 2);
    },
    pageCount() {
      return Math.max(1, Math.ceil(this.cards.length / this.pageSize));
    },
    pagedCards() {
      const page = Math.min(this.page, this.pageCount);
      const start = (page - 1) * this.pageSize;
      return this.cards.slice(start, start + this.pageSize);
    },
  },
  watch: {
    pageCount() {
      this.normalizePage();
    },
    pageSize() {
      this.revealSelected();
    },
    selectedKey: {
      immediate: true,
      handler(value) {
        this.revealSelected(value);
      },
    },
    cards() {
      this.revealSelected();
    },
  },
  mounted() {
    this.$nextTick(this.observeGrid);
  },
  beforeUnmount() {
    this.gridObserver?.disconnect();
    window.removeEventListener("resize", this.measureGrid);
  },
  methods: {
    categoryKey(category) {
      return category?.id || category?.code || "";
    },
    cardKey(category, index) {
      return this.categoryKey(category) || `all-${index}`;
    },
    cardTitle(category) {
      return [category?.name, category?.description].filter(Boolean).join("\n");
    },
    normalizePage() {
      if (this.page > this.pageCount) this.page = this.pageCount;
      if (this.page < 1) this.page = 1;
    },
    revealSelected(value = this.selectedKey) {
      const index = this.cards.findIndex((card) => this.categoryKey(card) === value);
      if (index >= 0) {
        this.page = Math.floor(index / this.pageSize) + 1;
      } else {
        this.normalizePage();
      }
    },
    observeGrid() {
      this.gridObserver?.disconnect();
      if (typeof ResizeObserver === "function" && this.$refs.grid) {
        this.gridObserver = new ResizeObserver(() => this.measureGrid());
        this.gridObserver.observe(this.$refs.grid);
      } else {
        window.addEventListener("resize", this.measureGrid);
      }
      this.measureGrid();
    },
    measureGrid() {
      const width = this.$refs.grid?.clientWidth || 0;
      if (!width) return;
      const minimumCardWidth = 220;
      const gap = 12;
      this.visibleColumns = Math.max(
        1,
        Math.min(6, Math.floor((width + gap) / (minimumCardWidth + gap)))
      );
    },
  },
};
