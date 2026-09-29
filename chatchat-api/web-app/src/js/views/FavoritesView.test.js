import { describe, expect, it, vi } from "vitest";
import * as api from "../../services/api.js";
import FavoritesView from "./FavoritesView.js";

vi.mock("../../services/api.js", () => ({
  createUserFavoriteCategory: vi.fn(),
  deleteUserFavoriteCategory: vi.fn(),
  fetchWorkbenchShortcuts: vi.fn(),
  removeUserFavorite: vi.fn(),
  renameUserFavoriteCategory: vi.fn(),
  updateUserFavoriteCategory: vi.fn()
}));

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
      searchKeyword: "",
      activeCategory: "all",
      activeType: "SESSION",
      favoriteCategory: FavoritesView.methods.favoriteCategory
    };

    expect(FavoritesView.computed.filteredFavorites.call(context)).toHaveLength(1);
    expect(FavoritesView.computed.filteredFavorites.call(context)[0].targetType).toBe("SESSION");
  });

  it("applies the entered keyword when searching", () => {
    const context = { keyword: "  季度报告  ", searchKeyword: "" };

    FavoritesView.methods.searchFavorites.call(context);

    expect(context.searchKeyword).toBe("季度报告");
  });

  it("sizes the category selector from the current category name", () => {
    const context = { favoriteCategory: FavoritesView.methods.favoriteCategory };
    const shortWidth = Number.parseInt(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "默认" }), 10);
    const mixedWidth = Number.parseInt(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "livegateway部署文档" }), 10);
    const longWidth = Number.parseInt(FavoritesView.methods.favoriteCategorySelectWidth.call(context, { category: "超长分类名称".repeat(50) }), 10);

    expect(shortWidth).toBe(96);
    expect(mixedWidth).toBeGreaterThan(shortWidth);
    expect(longWidth).toBe(520);
  });

  it("renames a category and keeps its favorites and filter in sync", async () => {
    api.renameUserFavoriteCategory.mockResolvedValue({ id: "category-1", name: "客户资料" });
    const context = {
      newCategoryName: " 客户资料 ", editingCategoryName: "项目资料", categorySaving: false,
      categoryDialogOpen: true, categoryDialogError: "", message: "", activeCategory: "项目资料",
      effectiveTenantId: "tenant-1", userId: "alice", availableCategoryNames: ["默认", "项目资料"],
      favoriteCategories: [{ id: "category-1", name: "项目资料" }],
      favorites: [{ id: "favorite-1", category: "项目资料" }],
      favoriteCategory: FavoritesView.methods.favoriteCategory
    };

    await FavoritesView.methods.saveCategory.call(context);

    expect(api.renameUserFavoriteCategory).toHaveBeenCalledWith("项目资料", {
      tenantId: "tenant-1", userId: "alice", name: "客户资料"
    });
    expect(context.activeCategory).toBe("客户资料");
    expect(context.favorites[0].category).toBe("客户资料");
  });

  it("deletes a category and moves local favorites to default", async () => {
    api.deleteUserFavoriteCategory.mockResolvedValue(true);
    const context = {
      categoryDeleteItem: { value: "项目资料", label: "项目资料", count: 1 },
      categoryDeleteSubmitting: false, categoryDeleteDialogOpen: true,
      effectiveTenantId: "tenant-1", userId: "alice", error: "", message: "",
      activeCategory: "项目资料", favoriteCategories: [{ name: "项目资料" }],
      favorites: [{ id: "favorite-1", category: "项目资料" }],
      favoriteCategory: FavoritesView.methods.favoriteCategory,
      isMutableCategory: FavoritesView.methods.isMutableCategory
    };

    await FavoritesView.methods.deleteCategory.call(context);

    expect(api.deleteUserFavoriteCategory).toHaveBeenCalledWith("项目资料", {
      tenantId: "tenant-1", userId: "alice"
    });
    expect(context.activeCategory).toBe("all");
    expect(context.favorites[0].category).toBe("默认");
  });
});
