import "../../styles/pages/reports.css";

export default {
  name: "ReportsView",
  props: {
    userId: {
      type: String,
      default: "default-user"
    }
  },
  data() {
    return {
      reports: []
    };
  }
};
