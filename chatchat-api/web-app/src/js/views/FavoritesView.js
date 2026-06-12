import "../../styles/pages/favorites.css";
import {
  fetchWorkbenchShortcuts,
  removeUserFavorite
} from "../../services/api";

export default {
  name: "FavoritesView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  emits: ["open-favorite"],
  data() {
    return {
      favorites: [],
      keyword: "",
      activeCategory: "all",
      loading: false,
      error: ""
    };
  },
  computed: {
    categoryOptions() {
      const counts = new Map();
      this.favorites.forEach((favorite) => {
        const category = this.favoriteCategory(favorite);
        counts.set(category, (counts.get(category) || 0) + 1);
      });
      return [
        { value: "all", label: "全部收藏", count: this.favorites.length },
        ...[...counts.entries()]
          .sort((left, right) => left[0].localeCompare(right[0], "zh-CN"))
          .map(([category, count]) => ({ value: category, label: category, count }))
      ];
    },
    filteredFavorites() {
      const keyword = this.keyword.trim().toLowerCase();
      return this.favorites.filter((favorite) => {
        const category = this.favoriteCategory(favorite);
        if (this.activeCategory !== "all" && category !== this.activeCategory) {
          return false;
        }
        if (!keyword) {
          return true;
        }
        return [
          favorite.title,
          favorite.targetId,
          favorite.targetType,
          category
        ].some((value) => String(value || "").toLowerCase().includes(keyword));
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
          tenantId: this.userId,
          userId: this.userId,
          limit: 100
        });
        this.favorites = Array.isArray(payload?.favorites) ? payload.favorites : [];
        this.normalizeCategory();
      } catch (error) {
        this.error = error.message || "收藏夹加载失败";
      } finally {
        this.loading = false;
      }
    },
    async removeFavorite(favorite) {
      if (!favorite?.id) {
        return;
      }
      try {
        await removeUserFavorite(favorite.id);
        this.favorites = this.favorites.filter((item) => item.id !== favorite.id);
      } catch (error) {
        this.error = error.message || "取消收藏失败";
      }
    },
    openFavorite(favorite) {
      this.$emit("open-favorite", favorite);
    },
    selectCategory(category) {
      this.activeCategory = category;
    },
    normalizeCategory() {
      if (this.activeCategory === "all") {
        return;
      }
      if (!this.favorites.some((favorite) => this.favoriteCategory(favorite) === this.activeCategory)) {
        this.activeCategory = "all";
      }
    },
    favoriteCategory(favorite) {
      return favorite?.category || favorite?.extra?.category || this.formatType(favorite?.targetType);
    },
    formatType(type) {
      return (
        {
          AGENT: "Agent",
          DOCUMENT: "文档",
          SESSION: "会话",
          TASK: "任务",
          TOOL: "工具"
        }[String(type || "").toUpperCase()] || "收藏"
      );
    }
  }
};
