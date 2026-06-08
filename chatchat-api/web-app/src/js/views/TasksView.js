import "../../styles/pages/skill-hub.css";

export default {
  name: "TasksView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  data() {
    return {
      tasks: []
    };
  }
};
