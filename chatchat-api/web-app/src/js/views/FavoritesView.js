import "../../styles/pages/favorites.css";
import {
  createUserFavoriteCategory,
  fetchWorkbenchShortcuts,
  removeUserFavorite,
  updateUserFavoriteCategory
} from "../../services/api";
import {
  isDocumentOnlinePreviewSupported,
  UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE
} from "../utils/documentPreview.js";

const DEFAULT_CATEGORY = "默认";

export default {
  name: "FavoritesView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    },
    tenantId: {
      type: String,
      default: ""
    }
  },
  emits: ["open-favorite"],
  data() {
    return {
      favorites: [],
      favoriteCategories: [],
      keyword: "",
      activeCategory: "all",
      activeType: "all",
      loading: false,
      error: "",
      message: "",
      categoryDialogOpen: false,
      categoryDialogError: "",
      newCategoryName: "",
      categorySaving: false,
      categoryUpdatingIds: {}
    };
  },
  computed: {
    effectiveTenantId() {
      return this.tenantId || this.userId;
    },
    availableCategoryNames() {
      const names = new Set([DEFAULT_CATEGORY]);
      this.favoriteCategories.forEach((category) => names.add(category?.name || category?.categoryName || category));
      this.favorites.forEach((favorite) => names.add(this.favoriteCategory(favorite)));
      return [...names].filter(Boolean);
    },
    categoryOptions() {
      const counts = new Map(this.availableCategoryNames.map((category) => [category, 0]));
      this.favorites.forEach((favorite) => {
        const category = this.favoriteCategory(favorite);
        counts.set(category, (counts.get(category) || 0) + 1);
      });
      return [
        { value: "all", label: "全部分类", count: this.favorites.length },
        ...[...counts.entries()].map(([category, count]) => ({ value: category, label: category, count }))
      ];
    },
    typeOptions() {
      const count = (type) => this.favorites.filter((favorite) => String(favorite?.targetType || "").toUpperCase() === type).length;
      return [
        { value: "all", label: "全部收藏", count: this.favorites.length },
        { value: "SESSION", label: "历史会话", count: count("SESSION") },
        { value: "DOCUMENT", label: "文档", count: count("DOCUMENT") }
      ];
    },
    filteredFavorites() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.favorites.filter((favorite) => {
        const category = this.favoriteCategory(favorite);
        const type = String(favorite?.targetType || "").toUpperCase();
        if (this.activeCategory !== "all" && category !== this.activeCategory) {
          return false;
        }
        if (this.activeType !== "all" && type !== this.activeType) {
          return false;
        }
        if (!keyword) {
          return true;
        }
        return [favorite.title, favorite.targetId, favorite.targetType, category]
          .some((value) => String(value || "").toLowerCase().includes(keyword));
      });
    }
  },
  mounted() {
    this.loadFavorites();
  },
  methods: {
    async loadFavorites() {
      this.loading = true;
      this.error = "";
      try {
        const payload = await fetchWorkbenchShortcuts({
          tenantId: this.effectiveTenantId,
          userId: this.userId,
          limit: 200
        });
        this.favorites = Array.isArray(payload?.favorites) ? payload.favorites : [];
        this.favoriteCategories = Array.isArray(payload?.favoriteCategories) ? payload.favoriteCategories : [];
        this.normalizeCategory();
      } catch (error) {
        this.error = error.message || "收藏夹加载失败";
      } finally {
        this.loading = false;
      }
    },
    openCategoryDialog() {
      this.newCategoryName = "";
      this.categoryDialogError = "";
      this.categoryDialogOpen = true;
      this.$nextTick(() => this.$refs.categoryNameInput?.focus());
    },
    closeCategoryDialog() {
      if (this.categorySaving) return;
      this.categoryDialogOpen = false;
      this.categoryDialogError = "";
    },
    async createCategory() {
      const name = this.newCategoryName.trim();
      if (!name || this.categorySaving) return;
      if (this.availableCategoryNames.some((category) => category.toLowerCase() === name.toLowerCase())) {
        this.categoryDialogError = "该分类已经存在";
        return;
      }
      this.categorySaving = true;
      this.categoryDialogError = "";
      try {
        const category = await createUserFavoriteCategory({
          tenantId: this.effectiveTenantId,
          userId: this.userId,
          name
        });
        this.favoriteCategories = [...this.favoriteCategories, category || { name }];
        this.activeCategory = name;
        this.categoryDialogOpen = false;
        this.message = `分类“${name}”已创建`;
      } catch (error) {
        this.categoryDialogError = error.message || "创建分类失败";
      } finally {
        this.categorySaving = false;
      }
    },
    async changeFavoriteCategory(favorite, category) {
      if (!favorite?.id || !category || category === this.favoriteCategory(favorite)) return;
      this.categoryUpdatingIds = { ...this.categoryUpdatingIds, [favorite.id]: true };
      this.error = "";
      try {
        const updated = await updateUserFavoriteCategory(favorite.id, {
          tenantId: this.effectiveTenantId,
          userId: this.userId,
          category
        });
        this.favorites = this.favorites.map((item) => item.id === favorite.id ? { ...item, ...updated, category } : item);
        this.message = `已移动到“${category}”`;
      } catch (error) {
        this.error = error.message || "修改收藏分类失败";
      } finally {
        const next = { ...this.categoryUpdatingIds };
        delete next[favorite.id];
        this.categoryUpdatingIds = next;
      }
    },
    async removeFavorite(favorite) {
      if (!favorite?.id) return;
      try {
        await removeUserFavorite(favorite.id);
        this.favorites = this.favorites.filter((item) => item.id !== favorite.id);
        this.normalizeCategory();
      } catch (error) {
        this.error = error.message || "取消收藏失败";
      }
    },
    openFavorite(favorite) {
      if (this.isUnsupportedDocumentFavorite(favorite)) {
        this.error = UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE;
        return;
      }
      this.$emit("open-favorite", favorite);
    },
    isUnsupportedDocumentFavorite(favorite) {
      return String(favorite?.targetType || "").toUpperCase() === "DOCUMENT"
        && !isDocumentOnlinePreviewSupported(favorite);
    },
    favoritePreviewTitle(favorite) {
      return this.isUnsupportedDocumentFavorite(favorite) ? UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE : "";
    },
    selectCategory(category) {
      this.activeCategory = category;
    },
    normalizeCategory() {
      if (this.activeCategory !== "all" && !this.availableCategoryNames.includes(this.activeCategory)) {
        this.activeCategory = "all";
      }
    },
    favoriteCategory(favorite) {
      return favorite?.category || favorite?.extra?.category || DEFAULT_CATEGORY;
    },
    favoriteTypeClass(favorite) {
      return `type-${String(favorite?.targetType || "favorite").toLowerCase()}`;
    },
    formatFavoriteTime(value) {
      if (!value) return "";
      const date = new Date(value);
      return Number.isNaN(date.getTime()) ? "" : date.toLocaleString("zh-CN", { hour12: false });
    },
    formatType(type) {
      return ({ AGENT: "Agent", DOCUMENT: "文档", SESSION: "历史会话", TASK: "任务", TOOL: "工具" })[
        String(type || "").toUpperCase()
      ] || "收藏";
    }
  }
};
