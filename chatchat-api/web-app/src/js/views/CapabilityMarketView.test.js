import { describe, expect, it } from "vitest";
import CapabilityMarketView from "./CapabilityMarketView.js";

const categoryOptions = Array.from({ length: 12 }, (_, index) => ({
  value: index === 0 ? "all" : `category-${index}`,
  label: index === 0 ? "全部业务" : `分类 ${index}`,
  count: index + 1
}));

describe("CapabilityMarketView category folding", () => {
  it("shows a compact category list by default", () => {
    const visible = CapabilityMarketView.computed.visibleCategoryOptions.call({
      categoryOptions,
      categoriesExpanded: false,
      categoryFilter: "all"
    });

    expect(visible).toHaveLength(8);
    expect(visible[0].value).toBe("all");
  });

  it("keeps a selected hidden category visible while collapsed", () => {
    const visible = CapabilityMarketView.computed.visibleCategoryOptions.call({
      categoryOptions,
      categoriesExpanded: false,
      categoryFilter: "category-11"
    });

    expect(visible).toHaveLength(8);
    expect(visible.some((category) => category.value === "category-11")).toBe(true);
  });

  it("shows all categories after expansion", () => {
    const visible = CapabilityMarketView.computed.visibleCategoryOptions.call({
      categoryOptions,
      categoriesExpanded: true,
      categoryFilter: "all"
    });

    expect(visible).toEqual(categoryOptions);
  });
});
