import { BadgeCheck, LockKeyhole, LogIn, ShieldCheck, UserRound } from "@lucide/vue";
import "../../styles/pages/login.css";
import { loginEnterprise } from "../../services/api";

const REMEMBER_KEY = "chatchat.login.remember";
const CYCLE_FEATURE_INTERVAL = 30 * 1000;
const CYCLE_FEATURES = [
  {
    title: "数据洞察",
    detail: "融合经营数据、指标和业务资产，形成可追溯的分析结论与趋势判断。",
    tags: ["经营分析", "指标洞察", "结果追溯"]
  },
  {
    title: "知识智能",
    detail: "理解制度、文档与企业知识，形成有依据、可验证的专业回答。",
    tags: ["制度检索", "知识问答", "证据引用"]
  },
  {
    title: "决策辅助",
    detail: "综合数据事实与企业知识，辅助风险研判、方案比较和经营决策。",
    tags: ["风险研判", "方案比较", "决策建议"]
  },
  {
    title: "智能协同",
    detail: "连接待办、审批和业务流程，让分析结论形成可执行、可审计的闭环。",
    tags: ["任务协同", "流程治理", "闭环执行"]
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
      const randomValues = new Uint32Array(4);
      window.crypto.getRandomValues(randomValues);
      this.captchaCode = Array.from(randomValues, value => alphabet[value % alphabet.length]).join("");
      this.form.captcha = "";
      this.$nextTick(() => this.drawCaptcha());
    },
    drawCaptcha() {
      const canvas = this.$refs.captchaCanvas;
      const context = canvas?.getContext?.("2d");
      if (!canvas || !context) {
        return;
      }
      const width = canvas.width;
      const height = canvas.height;
      context.clearRect(0, 0, width, height);
      const background = context.createLinearGradient(0, 0, width, height);
      background.addColorStop(0, "#eff6ff");
      background.addColorStop(1, "#ecfdf5");
      context.fillStyle = background;
      context.fillRect(0, 0, width, height);

      const random = () => {
        const value = new Uint32Array(1);
        window.crypto.getRandomValues(value);
        return value[0] / 0xffffffff;
      };
      for (let index = 0; index < 5; index += 1) {
        context.beginPath();
        context.moveTo(random() * width, random() * height);
        context.bezierCurveTo(random() * width, random() * height, random() * width, random() * height, random() * width, random() * height);
        context.strokeStyle = index % 2 === 0 ? "rgba(37, 99, 235, 0.22)" : "rgba(20, 184, 166, 0.24)";
        context.lineWidth = 2 + random() * 2;
        context.stroke();
      }
      for (let index = 0; index < 26; index += 1) {
        context.beginPath();
        context.arc(random() * width, random() * height, 1 + random() * 2.2, 0, Math.PI * 2);
        context.fillStyle = index % 2 === 0 ? "rgba(29, 78, 216, 0.24)" : "rgba(13, 148, 136, 0.24)";
        context.fill();
      }
      context.textAlign = "center";
      context.textBaseline = "middle";
      context.font = "800 39px 'Segoe UI', Arial, sans-serif";
      Array.from(this.captchaCode).forEach((character, index) => {
        context.save();
        context.translate(35 + index * 49, height / 2 + (random() - 0.5) * 8);
        context.rotate((random() - 0.5) * 0.28);
        context.fillStyle = index % 2 === 0 ? "#1e3a8a" : "#0f766e";
        context.fillText(character, 0, 0);
        context.restore();
      });
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
