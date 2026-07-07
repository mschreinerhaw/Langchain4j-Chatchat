import { BadgeCheck, LockKeyhole, LogIn, RefreshCw, ShieldCheck, UserRound } from "@lucide/vue";
import "../../styles/pages/login.css";
import { loginEnterprise } from "../../services/api";

const REMEMBER_KEY = "chatchat.login.remember";

export default {
  name: "LoginView",
  components: {
    BadgeCheck,
    LockKeyhole,
    LogIn,
    RefreshCw,
    ShieldCheck,
    UserRound
  },
  emits: ["login-success"],
  data() {
    return {
      loading: false,
      error: "",
      captchaCode: "",
      form: {
        username: "admin",
        password: "",
        captcha: "",
        rememberAccount: false
      }
    };
  },
  mounted() {
    this.restoreRememberedLogin();
    this.refreshCaptcha();
  },
  methods: {
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
