import { describe, expect, it } from "vitest";
import FavoritesView from "./FavoritesView.js";

describe("FavoritesView", () => {
  it("uses the real tenant id instead of the user id", () => {
    expect(FavoritesView.computed.effectiveTenantId.call({
      tenantId: "tenant-1001",
      userId: "alice"
    })).toBe("tenant-1001");
  });

  it("keeps empty persisted categories visible", () => {
    const context = {
      favoriteCategories: [{ id: "category-1", name: "项目资料" }],
      favorites: [],
      favoriteCategory: FavoritesView.methods.favoriteCategory
    };
    context.availableCategoryNames = FavoritesView.computed.availableCategoryNames.call(context);
    const options = FavoritesView.computed.categoryOptions.call(context);

    expect(options).toContainEqual({ value: "项目资料", label: "项目资料", count: 0 });
  });

  it("filters history conversations and documents independently", () => {
    const context = {
      favorites: [
        { targetType: "SESSION", title: "季度经营分析", category: "重点会话" },
        { targetType: "DOCUMENT", title: "季度报告.pdf", category: "项目资料" }
      ],
      keyword: "",
      activeCategory: "all",
      activeType: "SESSION",
      favoriteCategory: FavoritesView.methods.favoriteCategory
    };

    expect(FavoritesView.computed.filteredFavorites.call(context)).toHaveLength(1);
    expect(FavoritesView.computed.filteredFavorites.call(context)[0].targetType).toBe("SESSION");
  });

  it("sizes the category selector from the current category name", () => {
    const context = { favoriteCategory: FavoritesView.methods.favoriteCategory };

    expect(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "默认" })).toBe("120px");
    expect(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "livegateway部署文档" })).toBe("187px");
    expect(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "超长分类名称".repeat(20) })).toBe("280px");
  });
});
