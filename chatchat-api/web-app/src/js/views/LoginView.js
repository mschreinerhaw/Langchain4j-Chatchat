import { BadgeCheck, LockKeyhole, LogIn, ShieldCheck, UserRound } from "@lucide/vue";
import "../../styles/pages/login.css";
import { loginEnterprise } from "../../services/api";

const REMEMBER_KEY = "chatchat.login.remember";
const CYCLE_FEATURE_INTERVAL = 30 * 1000;
const CYCLE_FEATURES = [
  {
    title: "数据",
    detail: "连接数据库、指标、报表和业务资产，支持自然语言查询与结果追溯。",
    tags: ["SQL 查询", "指标解释", "结果引用"]
  },
  {
    title: "知识",
    detail: "检索制度、文档、知识库和操作手册，形成有引用依据的回答。",
    tags: ["知识库", "制度问答", "证据引用"]
  },
  {
    title: "办公",
    detail: "自动生成日报、周报、分析报告、会议纪要和业务说明材料。",
    tags: ["报告", "纪要", "材料生成"]
  },
  {
    title: "协同",
    detail: "对接待办、审批、消息和业务系统，把 AI 结果沉淀到流程中。",
    tags: ["待办", "审批", "流程协同"]
  }
];

export default {
  name: "LoginView",
  components: {
    BadgeCheck,
    LockKeyhole,
    LogIn,
    ShieldCheck,
    UserRound
  },
  emits: ["login-success"],
  data() {
    return {
      loading: false,
      error: "",
      captchaCode: "",
      cycleFeatureIndex: 1,
      cycleFeatureTimer: null,
      cycleFeatures: CYCLE_FEATURES,
      form: {
        username: "",
        password: "",
        captcha: "",
        rememberAccount: false
      }
    };
  },
  computed: {
    activeCycleFeature() {
      return this.cycleFeatures[this.cycleFeatureIndex] || this.cycleFeatures[0];
    }
  },
  mounted() {
    this.restoreRememberedLogin();
    this.refreshCaptcha();
    this.startCycleFeatureTimer();
  },
  beforeUnmount() {
    this.stopCycleFeatureTimer();
  },
  methods: {
    startCycleFeatureTimer() {
      this.stopCycleFeatureTimer();
      this.cycleFeatureTimer = window.setInterval(() => {
        this.cycleFeatureIndex = (this.cycleFeatureIndex + 1) % this.cycleFeatures.length;
      }, CYCLE_FEATURE_INTERVAL);
    },
    stopCycleFeatureTimer() {
      if (!this.cycleFeatureTimer) {
        return;
      }
      window.clearInterval(this.cycleFeatureTimer);
      this.cycleFeatureTimer = null;
    },
    setCycleFeature(index) {
      this.cycleFeatureIndex = index;
      this.startCycleFeatureTimer();
    },
    restoreRememberedLogin() {
      try {
        const remembered = JSON.parse(localStorage.getItem(REMEMBER_KEY) || "null");
        if (!remembered) {
          return;
        }
        this.form.username = remembered.username || this.form.username;
        this.form.password = "";
        this.form.rememberAccount = true;
      } catch (error) {
        localStorage.removeItem(REMEMBER_KEY);
      }
    },
    refreshCaptcha() {
      const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
      this.captchaCode = Array.from({ length: 4 }, () => alphabet[Math.floor(Math.random() * alphabet.length)]).join("");
      this.form.captcha = "";
    },
    syncRememberedLogin() {
      if (this.form.rememberAccount) {
        localStorage.setItem(REMEMBER_KEY, JSON.stringify({
          username: this.form.username
        }));
        return;
      }
      localStorage.removeItem(REMEMBER_KEY);
    },
    async submitLogin() {
      if (!this.form.username || !this.form.password) {
        this.error = "请输入账号和密码";
        return;
      }
      if (!this.form.captcha || this.form.captcha.trim().toUpperCase() !== this.captchaCode) {
        this.error = "验证码不正确";
        this.refreshCaptcha();
        return;
      }
      this.loading = true;
      this.error = "";
      try {
        const session = await loginEnterprise({
          username: this.form.username,
          password: this.form.password
        });
        this.syncRememberedLogin();
        this.$emit("login-success", session);
      } catch (error) {
        this.error = error.message || "登录失败";
        this.refreshCaptcha();
      } finally {
        this.loading = false;
      }
    }
  }
};
