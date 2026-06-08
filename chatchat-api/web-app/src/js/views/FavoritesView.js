import "../../styles/pages/favorites.css";

export default {
  name: "FavoritesView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  data() {
    return {
      favorites: []
    };
  }
};
