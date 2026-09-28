import "../../styles/pages/favorites.css";
import { MoreHorizontal, Pencil, Plus, Trash2 } from "@lucide/vue";
import {
  createUserFavoriteCategory,
  deleteUserFavoriteCategory,
  fetchWorkbenchShortcuts,
  removeUserFavorite,
  renameUserFavoriteCategory,
  updateUserFavoriteCategory
} from "../../services/api";
import {
  isDocumentOnlinePreviewSupported,
  UNSUPPORTED_DOCUMENT_PREVIEW_MESSAGE
} from "../utils/documentPreview.js";

const DEFAULT_CATEGORY = "默认";
const CATEGORY_SELECT_MIN_WIDTH = 96;
const CATEGORY_SELECT_CHROME_WIDTH = 54;
const CATEGORY_SELECT_MAX_WIDTH = 520;
let categoryMeasureContext = null;

function categoryTextWidth(value) {
  const text = String(value || "");
  if (typeof document !== "undefined") {
    try {
      if (!categoryMeasureContext) {
        const canvas = document.createElement("canvas");
        categoryMeasureContext = canvas.getContext("2d");
      }
      if (categoryMeasureContext) {
        categoryMeasureContext.font = '600 13px Inter, "PingFang SC", "Microsoft YaHei", sans-serif';
        return categoryMeasureContext.measureText(text).width;
      }
    } catch {
      categoryMeasureContext = null;
    }
  }
  return Array.from(text).reduce((width, character) => {
    if (/[^\u0000-\u00ff]/.test(character)) return width + 13;
    if (/[MWmw@%]/.test(character)) return width + 10.5;
    if (/[ilI1.,'`|]/.test(character)) return width + 4.5;
    return width + 7.2;
  }, 0);
}

export default {
  name: "FavoritesView",
  components: {
    MoreHorizontal,
    Pencil,
    Plus,
    Trash2
  },
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
      editingCategoryName: "",
      categorySaving: false,
      openCategoryActionName: "",
      categoryDeleteItem: null,
      categoryDeleteDialogOpen: false,
      categoryDeleteSubmitting: false,
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
    openCategoryDialog(category = null) {
      this.closeCategoryActions();
      this.editingCategoryName = this.isMutableCategory(category?.value) ? category.value : "";
      this.newCategoryName = this.editingCategoryName;
      this.categoryDialogError = "";
      this.categoryDialogOpen = true;
      this.$nextTick(() => this.$refs.categoryNameInput?.focus());
    },
    closeCategoryDialog() {
      if (this.categorySaving) return;
      this.categoryDialogOpen = false;
      this.categoryDialogError = "";
      this.editingCategoryName = "";
      this.newCategoryName = "";
    },
    async saveCategory() {
      const name = this.newCategoryName.trim();
      if (!name || this.categorySaving) return;
      if (this.availableCategoryNames.some((category) =>
        category.toLowerCase() === name.toLowerCase() && category !== this.editingCategoryName
      )) {
        this.categoryDialogError = "该分类已经存在";
        return;
      }
      this.categorySaving = true;
      this.categoryDialogError = "";
      try {
        const payload = {
          tenantId: this.effectiveTenantId,
          userId: this.userId,
          name
        };
        if (this.editingCategoryName) {
          const originalName = this.editingCategoryName;
          const category = await renameUserFavoriteCategory(originalName, payload);
          this.favoriteCategories = this.favoriteCategories.map((item) => {
            const itemName = item?.name || item?.categoryName || item;
            return itemName === originalName ? (category || { name }) : item;
          });
          this.favorites = this.favorites.map((favorite) =>
            this.favoriteCategory(favorite) === originalName ? { ...favorite, category: name } : favorite
          );
          this.message = `分类“${name}”已更新`;
        } else {
          const category = await createUserFavoriteCategory(payload);
          this.favoriteCategories = [...this.favoriteCategories, category || { name }];
          this.message = `分类“${name}”已创建`;
        }
        this.activeCategory = name;
        this.categoryDialogOpen = false;
        this.editingCategoryName = "";
        this.newCategoryName = "";
      } catch (error) {
        this.categoryDialogError = error.message || (this.editingCategoryName ? "修改分类失败" : "创建分类失败");
      } finally {
        this.categorySaving = false;
      }
    },
    isMutableCategory(category) {
      return Boolean(category) && category !== "all" && category !== DEFAULT_CATEGORY;
    },
    toggleCategoryActions(category) {
      this.openCategoryActionName = this.openCategoryActionName === category ? "" : category;
    },
    closeCategoryActions() {
      this.openCategoryActionName = "";
    },
    openCategoryDeleteDialog(category) {
      if (!this.isMutableCategory(category?.value)) return;
      this.closeCategoryActions();
      this.categoryDeleteItem = category;
      this.categoryDeleteDialogOpen = true;
      this.error = "";
    },
    closeCategoryDeleteDialog() {
      if (this.categoryDeleteSubmitting) return;
      this.categoryDeleteDialogOpen = false;
      this.categoryDeleteItem = null;
    },
    async deleteCategory() {
      const name = this.categoryDeleteItem?.value;
      if (!this.isMutableCategory(name) || this.categoryDeleteSubmitting) return;
      this.categoryDeleteSubmitting = true;
      this.error = "";
      try {
        await deleteUserFavoriteCategory(name, {
          tenantId: this.effectiveTenantId,
          userId: this.userId
        });
        this.favoriteCategories = this.favoriteCategories.filter((item) =>
          (item?.name || item?.categoryName || item) !== name
        );
        this.favorites = this.favorites.map((favorite) =>
          this.favoriteCategory(favorite) === name ? { ...favorite, category: DEFAULT_CATEGORY } : favorite
        );
        if (this.activeCategory === name) this.activeCategory = "all";
        this.categoryDeleteDialogOpen = false;
        this.categoryDeleteItem = null;
        this.message = `分类“${name}”已删除，原收藏已移至“默认”`;
      } catch (error) {
        this.error = error.message || "删除分类失败";
      } finally {
        this.categoryDeleteSubmitting = false;
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
        await removeUserFavorite(favorite.id, {
          tenantId: this.effectiveTenantId,
          userId: this.userId
        });
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
      this.closeCategoryActions();
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
    favoriteCategorySelectWidth(favorite) {
      const category = String(this.favoriteCategory(favorite) || DEFAULT_CATEGORY);
      const width = Math.ceil(categoryTextWidth(category) + CATEGORY_SELECT_CHROME_WIDTH);
      return `${Math.max(CATEGORY_SELECT_MIN_WIDTH, Math.min(CATEGORY_SELECT_MAX_WIDTH, width))}px`;
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
